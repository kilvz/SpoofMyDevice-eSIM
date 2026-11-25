package com.devicespooflab.hooks.card;

import android.content.Context;
import android.os.Build;
import android.os.Looper;
import android.telephony.TelephonyManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Root-side card daemon for NekokoLPA2.
 *
 * <p>NekokoLPA2 normally reaches a removable eUICC through OMAPI or through its
 * privileged Telephony (TMAPI) reader. Both paths are dead on a ROM that ships
 * neither the secure-element service nor the privileged telephony grant. This
 * daemon runs as uid 0 (started by {@link CardBridgeClient} through {@code su}),
 * so the platform's {@code MODIFY_PHONE_STATE} checks pass, and serves the small
 * NBridge protocol that NekokoLPA2 already speaks over
 * {@code content://ee.nekoko.nbridge.provider}.
 *
 * <p>It deliberately lives in its own process: a ContentProvider cannot run as
 * root, and loading this class into the app process would only produce another
 * unprivileged caller.
 */
public final class CardDaemon {

    static final String TAG = "SpoofMyDeviceCard";
    static final String STATE_FILE = "nbridge-daemon.properties";
    static final String LOG_FILE = "/data/local/tmp/spoofmydevice-cardd.log";

    private static final Object CARD_LOCK = new Object();
    private static final Map<String, Connection> CONNECTIONS = new ConcurrentHashMap<>();
    private static final Set<String> TERMINAL_CAPABILITIES_SENT = new HashSet<>();

    private static TelephonyManager telephony;
    private static Context systemContext;
    private static String token = "";

    private CardDaemon() {
    }

    public static void main(String[] args) {
        try {
            token = args.length > 0 ? args[0] : "";
            try {
                Looper.prepareMainLooper();
            } catch (Throwable ignored) {
                // A looper may already exist when started from an unusual parent.
            }

            systemContext = createSystemContext();
            telephony = (TelephonyManager) systemContext.getSystemService(Context.TELEPHONY_SERVICE);
            if (telephony == null) {
                throw new IllegalStateException("TelephonyManager unavailable");
            }

            log("daemon start uid=" + android.os.Process.myUid()
                    + " sdk=" + Build.VERSION.SDK_INT
                    + " device=" + Build.DEVICE);

            ServerSocket server = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
            System.out.println("READY " + server.getLocalPort());
            System.out.flush();
            log("listening on 127.0.0.1:" + server.getLocalPort());

            while (true) {
                Socket socket = server.accept();
                Thread worker = new Thread(() -> handle(socket), "cardd-request");
                worker.setDaemon(true);
                worker.start();
            }
        } catch (Throwable throwable) {
            log("fatal: " + stackTrace(throwable));
            System.err.println("FATAL " + throwable);
            System.exit(1);
        }
    }

    // --- request handling ----------------------------------------------------

    private static void handle(Socket socket) {
        try (Socket closeable = socket) {
            closeable.setSoTimeout(120_000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(closeable.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter writer = new PrintWriter(
                    new java.io.OutputStreamWriter(closeable.getOutputStream(), StandardCharsets.UTF_8),
                    true);

            String line = reader.readLine();
            if (line == null) {
                return;
            }
            JSONObject request = new JSONObject(line);
            JSONObject response;
            try {
                response = dispatch(request);
            } catch (Throwable throwable) {
                Log.w(TAG, "request failed", throwable);
                log("request failed: " + stackTrace(throwable));
                response = error("ERROR", readable(throwable));
            }
            writer.println(response.toString());
        } catch (Throwable throwable) {
            log("connection failed: " + stackTrace(throwable));
        }
    }

    private static JSONObject dispatch(JSONObject request) throws Exception {
        if (!token.equals(request.optString("token", ""))) {
            return error("AUTH", "Invalid bridge token");
        }

        String op = request.optString("op", "");
        switch (op) {
            case "ping":
                return ok().put("uid", android.os.Process.myUid())
                        .put("pid", android.os.Process.myPid())
                        .put("sdk", Build.VERSION.SDK_INT);
            case "diag":
                return diagnostics();
            case "listSlots":
                return listSlots();
            case "open":
                return openLogicalChannel(
                        request.getInt("slot"),
                        request.optInt("port", 0),
                        request.getJSONArray("aids"));
            case "transmitLogical":
                return transmitLogical(
                        request.getString("connectionId"),
                        request.getString("apdu"));
            case "transmitBasic":
                return transmitBasic(
                        request.getInt("slot"),
                        request.optInt("port", 0),
                        request.getString("apdu"));
            case "close":
                return closeLogicalChannel(request.getString("connectionId"));
            default:
                return error("NOT_IMPLEMENTED", "Unknown operation " + op);
        }
    }

    // --- slots ---------------------------------------------------------------

    private static JSONObject listSlots() throws Exception {
        JSONArray slots = new JSONArray();
        int modemCount = activeModemCount();
        Map<Integer, Integer> cardStates = readSlotCardStates();
        List<CardSnapshot> cards = readCards();

        Set<String> emitted = new HashSet<>();
        for (CardSnapshot card : cards) {
            if (card.ports.isEmpty()) {
                slots.put(slotJson(card.slotIndex, 0, card, cardStates, modemCount));
                emitted.add(card.slotIndex + ":0");
            } else {
                for (PortSnapshot port : card.ports) {
                    slots.put(slotJson(card.slotIndex, port.portIndex, card, cardStates, modemCount));
                    emitted.add(card.slotIndex + ":" + port.portIndex);
                }
            }
        }

        // Even when UiccCardInfo is unavailable, expose every modem slot so a
        // removable eUICC the ROM failed to classify can still be tried.
        for (int slot = 0; slot < modemCount; slot++) {
            if (!emitted.contains(slot + ":0")) {
                slots.put(slotJson(slot, 0, null, cardStates, modemCount));
            }
        }
        if (slots.length() == 0) {
            slots.put(slotJson(0, 0, null, cardStates, modemCount));
        }

        return ok().put("slots", slots);
    }

    private static JSONObject slotJson(
            int slotIndex,
            int portIndex,
            CardSnapshot card,
            Map<Integer, Integer> cardStates,
            int modemCount) throws Exception {
        int simState = simState(slotIndex);
        Integer cardState = cardStates.get(slotIndex);
        boolean present;
        if (cardState != null) {
            present = cardState.intValue() != 1; // 1 == CARD_STATE_INFO_ABSENT
        } else {
            present = simState != TelephonyManager.SIM_STATE_ABSENT
                    && simState != TelephonyManager.SIM_STATE_UNKNOWN;
        }
        boolean multiplePorts = card != null && card.ports.size() > 1;
        String displayName = "T-SIM" + (slotIndex + 1)
                + (multiplePorts ? "p" + portIndex : "");

        JSONObject json = new JSONObject();
        try {
            json.put("id", "tmapi:slot:" + slotIndex + ":port:" + portIndex);
            json.put("transport", "tmapi");
            json.put("displayName", displayName);
            json.put("present", present);
            json.put("slotIndex", slotIndex);
            json.put("portIndex", portIndex);
            json.put("logicalSlotIndex", slotIndex);
            json.put("simState", simState);
            json.put("isEuicc", card != null && card.isEuicc);
            if (cardState != null) {
                json.put("cardState", cardState);
            }
            json.put("modemCount", modemCount);
        } catch (Exception ignored) {
            // JSONObject only rejects non-finite numbers; these are all integers.
        }
        return json;
    }

    private static JSONObject diagnostics() throws Exception {
        JSONObject result = ok();
        result.put("uid", android.os.Process.myUid());
        result.put("sdk", Build.VERSION.SDK_INT);
        result.put("device", Build.DEVICE);
        result.put("model", Build.MODEL);
        result.put("activeModemCount", activeModemCount());
        result.put("slots", listSlots().optJSONArray("slots"));

        JSONObject methods = new JSONObject();
        for (Method method : TelephonyManager.class.getMethods()) {
            String name = method.getName();
            if (name.startsWith("iccOpenLogicalChannel")
                    || name.startsWith("iccCloseLogicalChannel")
                    || name.startsWith("iccTransmitApdu")) {
                try {
                    methods.put(name, signature(method));
                } catch (Exception ignored) {
                }
            }
        }
        result.put("telephonyMethods", methods);
        return result;
    }

    private static String signature(Method method) {
        StringBuilder builder = new StringBuilder("(");
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(types[i].getSimpleName());
        }
        return builder.append(')').toString();
    }

    // --- card operations -----------------------------------------------------

    private static JSONObject openLogicalChannel(int slot, int port, JSONArray aids) throws Exception {
        if (aids.length() == 0) {
            return error("NO_AID", "No AID supplied");
        }
        synchronized (CARD_LOCK) {
            sendTerminalCapabilities(slot, port);

            Exception last = null;
            int lastStatus = -1;
            for (int i = 0; i < aids.length(); i++) {
                String aid = aids.getString(i);
                if (aid == null || aid.trim().isEmpty()) {
                    continue;
                }
                try {
                    Object response = open(slot, port, aid.trim());
                    int status = responseInt(response, "getStatus", -1);
                    int channel = responseInt(response, "getChannel", -1);
                    if (status != 0 || channel <= 0) {
                        // IccOpenLogicalChannelResponse.STATUS_NO_ERROR == 0
                        lastStatus = status;
                        continue;
                    }
                    String connectionId = "tmapi:" + UUID.randomUUID();
                    String selectResponse = responseHex(response, "getSelectResponse");
                    CONNECTIONS.put(connectionId, new Connection(slot, port, channel, aid));
                    log("open slot=" + slot + " port=" + port + " aid=" + aid
                            + " channel=" + channel + " status=" + status);
                    return ok()
                            .put("connectionId", connectionId)
                            .put("selectedAid", aid)
                            .put("selectResponse", selectResponse)
                            .put("channelNumber", channel);
                } catch (Throwable throwable) {
                    last = asException(throwable);
                    log("open failed slot=" + slot + " port=" + port + " aid=" + aid
                            + ": " + readable(throwable));
                }
            }
            String message = last != null
                    ? readable(last)
                    : "No AID opened a channel (last status " + lastStatus + ")";
            return error("CHANNEL_OPEN_FAILED", message);
        }
    }

    private static JSONObject transmitLogical(String connectionId, String apduHex) throws Exception {
        Connection connection = CONNECTIONS.get(connectionId);
        if (connection == null) {
            return error("NO_CONNECTION", "Unknown connectionId");
        }
        synchronized (CARD_LOCK) {
            String response = transmitLogical(
                    connection.slotIndex,
                    connection.portIndex,
                    connection.channel,
                    Apdu.parse(apduHex));
            if (response == null) {
                return error("TRANSMIT_FAILED", "The platform returned no response");
            }
            return ok().put("responseApdu", response);
        }
    }

    private static JSONObject transmitBasic(int slot, int port, String apduHex) throws Exception {
        synchronized (CARD_LOCK) {
            String response = transmitBasic(slot, port, Apdu.parse(apduHex));
            if (response == null) {
                return error("TRANSMIT_FAILED", "The platform returned no response");
            }
            return ok().put("responseApdu", response);
        }
    }

    private static JSONObject closeLogicalChannel(String connectionId) {
        Connection connection = CONNECTIONS.remove(connectionId);
        if (connection != null) {
            synchronized (CARD_LOCK) {
                try {
                    close(connection.slotIndex, connection.portIndex, connection.channel);
                    log("close slot=" + connection.slotIndex + " channel=" + connection.channel);
                } catch (Throwable throwable) {
                    log("close failed: " + readable(throwable));
                }
            }
        }
        return ok();
    }

    private static void sendTerminalCapabilities(int slot, int port) {
        String key = slot + ":" + port;
        if (!TERMINAL_CAPABILITIES_SENT.add(key)) {
            return;
        }
        try {
            // UICC TERMINAL PROFILE. Harmless when the card does not use it.
            Apdu apdu = new Apdu(0x80, 0xAA, 0x00, 0x00, 10, "a9088100820101830107");
            transmitBasic(slot, port, apdu);
        } catch (Throwable throwable) {
            log("terminal capabilities ignored: " + readable(throwable));
        }
    }

    // --- telephony reflection -------------------------------------------------

    private static Object open(int slot, int port, String aid) throws Exception {
        Method method = firstMethod(
                "iccOpenLogicalChannelByPort",
                "iccOpenLogicalChannelBySlot",
                "iccOpenLogicalChannel");
        if (method == null) {
            throw new NoSuchMethodException("No ICC logical channel API on this ROM");
        }
        Object[] args = openArgs(method, slot, port, aid);
        if (args == null) {
            throw new NoSuchMethodException("Unsupported " + method.getName()
                    + signature(method));
        }
        return invoke(method, args);
    }

    private static Object[] openArgs(Method method, int slot, int port, String aid) {
        Class<?>[] types = method.getParameterTypes();
        String name = method.getName();
        if ("iccOpenLogicalChannelByPort".equals(name)) {
            if (types.length == 4 && types[0] == int.class && types[1] == int.class
                    && types[2] == String.class) {
                return new Object[]{slot, port, aid, 0};
            }
            if (types.length == 4 && types[0] == int.class && types[1] == String.class) {
                return new Object[]{port, aid, 0, 0};
            }
            if (types.length == 3 && types[0] == int.class && types[1] == String.class) {
                return new Object[]{port, aid, 0};
            }
        }
        if ("iccOpenLogicalChannelBySlot".equals(name)) {
            if (types.length == 3 && types[0] == int.class && types[1] == String.class) {
                return new Object[]{slot, aid, 0};
            }
            if (types.length == 4 && types[0] == int.class && types[1] == String.class
                    && types[2] == String.class) {
                return new Object[]{slot, "android", aid, 0};
            }
        }
        if ("iccOpenLogicalChannel".equals(name)) {
            if (types.length == 2 && types[0] == String.class) {
                return new Object[]{aid, 0};
            }
            if (types.length == 3 && types[0] == String.class && types[1] == String.class) {
                return new Object[]{"android", aid, 0};
            }
        }
        return null;
    }

    private static String transmitLogical(int slot, int port, int channel, Apdu apdu)
            throws Exception {
        Method method = firstMethod(
                "iccTransmitApduLogicalChannelByPort",
                "iccTransmitApduLogicalChannelBySlot",
                "iccTransmitApduLogicalChannel");
        if (method == null) {
            throw new NoSuchMethodException("No ICC logical transmit API on this ROM");
        }
        Object[] args = transmitLogicalArgs(method, slot, port, channel, apdu);
        if (args == null) {
            throw new NoSuchMethodException("Unsupported " + method.getName()
                    + signature(method));
        }
        return (String) invoke(method, args);
    }

    private static String transmitBasic(int slot, int port, Apdu apdu) throws Exception {
        Method method = firstMethod(
                "iccTransmitApduBasicChannelByPort",
                "iccTransmitApduBasicChannelBySlot",
                "iccTransmitApduBasicChannel");
        if (method == null) {
            throw new NoSuchMethodException("No ICC basic transmit API on this ROM");
        }
        Object[] args = transmitBasicArgs(method, slot, port, apdu);
        if (args == null) {
            throw new NoSuchMethodException("Unsupported " + method.getName()
                    + signature(method));
        }
        return (String) invoke(method, args);
    }

    private static void close(int slot, int port, int channel) throws Exception {
        Method method = firstMethod(
                "iccCloseLogicalChannelByPort",
                "iccCloseLogicalChannelBySlot",
                "iccCloseLogicalChannel");
        if (method == null) {
            throw new NoSuchMethodException("No ICC close API on this ROM");
        }
        Class<?>[] types = method.getParameterTypes();
        Object[] args = null;
        String name = method.getName();
        if ("iccCloseLogicalChannelByPort".equals(name)) {
            if (types.length == 3) {
                args = new Object[]{slot, port, channel};
            } else if (types.length == 2) {
                args = new Object[]{port, channel};
            }
        } else if ("iccCloseLogicalChannelBySlot".equals(name)) {
            if (types.length == 2) {
                args = new Object[]{slot, channel};
            }
        } else if ("iccCloseLogicalChannel".equals(name)) {
            if (types.length == 1) {
                args = new Object[]{channel};
            }
        }
        if (args == null) {
            throw new NoSuchMethodException("Unsupported " + name + signature(method));
        }
        invoke(method, args);
    }

    private static Object[] transmitLogicalArgs(
            Method method,
            int slot,
            int port,
            int channel,
            Apdu apdu) {
        Class<?>[] t = method.getParameterTypes();
        String name = method.getName();
        if ("iccTransmitApduLogicalChannelByPort".equals(name)) {
            if (t.length == 9 && t[1] == int.class) {
                return new Object[]{slot, port, channel,
                        apdu.cla, apdu.ins, apdu.p1, apdu.p2, apdu.p3, apdu.data};
            }
            if (t.length == 8) {
                return new Object[]{port, channel,
                        apdu.cla, apdu.ins, apdu.p1, apdu.p2, apdu.p3, apdu.data};
            }
            if (t.length == 9 && t[1] == String.class) {
                return new Object[]{slot, "android", channel,
                        apdu.cla, apdu.ins, apdu.p1, apdu.p2, apdu.p3, apdu.data};
            }
        }
        if ("iccTransmitApduLogicalChannelBySlot".equals(name)) {
            if (t.length == 8) {
                return new Object[]{slot, channel,
                        apdu.cla, apdu.ins, apdu.p1, apdu.p2, apdu.p3, apdu.data};
            }
            if (t.length == 9 && t[1] == String.class) {
                return new Object[]{slot, "android", channel,
                        apdu.cla, apdu.ins, apdu.p1, apdu.p2, apdu.p3, apdu.data};
            }
        }
        if ("iccTransmitApduLogicalChannel".equals(name)) {
            if (t.length == 7) {
                return new Object[]{channel,
                        apdu.cla, apdu.ins, apdu.p1, apdu.p2, apdu.p3, apdu.data};
            }
            if (t.length == 8 && t[0] == String.class) {
                return new Object[]{"android", channel,
                        apdu.cla, apdu.ins, apdu.p1, apdu.p2, apdu.p3, apdu.data};
            }
        }
        return null;
    }

    private static Object[] transmitBasicArgs(Method method, int slot, int port, Apdu apdu) {
        Class<?>[] t = method.getParameterTypes();
        String name = method.getName();
        if ("iccTransmitApduBasicChannelByPort".equals(name)) {
            if (t.length == 8 && t[1] == int.class) {
                return new Object[]{slot, port,
                        apdu.cla, apdu.ins, apdu.p1, apdu.p2, apdu.p3, apdu.data};
            }
            if (t.length == 7) {
                return new Object[]{port,
                        apdu.cla, apdu.ins, apdu.p1, apdu.p2, apdu.p3, apdu.data};
            }
            if (t.length == 8 && t[1] == String.class) {
                return new Object[]{slot, "android",
                        apdu.cla, apdu.ins, apdu.p1, apdu.p2, apdu.p3, apdu.data};
            }
        }
        if ("iccTransmitApduBasicChannelBySlot".equals(name)) {
            if (t.length == 7) {
                return new Object[]{slot,
                        apdu.cla, apdu.ins, apdu.p1, apdu.p2, apdu.p3, apdu.data};
            }
            if (t.length == 8 && t[1] == String.class) {
                return new Object[]{slot, "android",
                        apdu.cla, apdu.ins, apdu.p1, apdu.p2, apdu.p3, apdu.data};
            }
        }
        if ("iccTransmitApduBasicChannel".equals(name)) {
            if (t.length == 6) {
                return new Object[]{
                        apdu.cla, apdu.ins, apdu.p1, apdu.p2, apdu.p3, apdu.data};
            }
            if (t.length == 7 && t[0] == String.class) {
                return new Object[]{"android",
                        apdu.cla, apdu.ins, apdu.p1, apdu.p2, apdu.p3, apdu.data};
            }
        }
        return null;
    }

    private static Method firstMethod(String... names) {
        for (String name : names) {
            for (Method method : TelephonyManager.class.getMethods()) {
                if (method.getName().equals(name)) {
                    return method;
                }
            }
        }
        return null;
    }

    private static Object invoke(Method method, Object[] args) throws Exception {
        try {
            return method.invoke(telephony, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    // --- slot metadata --------------------------------------------------------

    private static int activeModemCount() {
        for (String name : new String[]{"getActiveModemCount", "getPhoneCount",
                "getSupportedModemCount"}) {
            try {
                Method method = TelephonyManager.class.getMethod(name);
                Object value = method.invoke(telephony);
                if (value instanceof Integer && (Integer) value > 0) {
                    return (Integer) value;
                }
            } catch (Throwable ignored) {
            }
        }
        return 1;
    }

    private static int simState(int slot) {
        try {
            return telephony.getSimState(slot);
        } catch (Throwable ignored) {
            return TelephonyManager.SIM_STATE_UNKNOWN;
        }
    }

    private static Map<Integer, Integer> readSlotCardStates() {
        Map<Integer, Integer> result = new HashMap<>();
        try {
            Method method = TelephonyManager.class.getMethod("getUiccSlotsInfo");
            Object value = method.invoke(telephony);
            if (value instanceof Object[]) {
                Object[] slots = (Object[]) value;
                for (int i = 0; i < slots.length; i++) {
                    Object slot = slots[i];
                    if (slot == null) {
                        continue;
                    }
                    Integer state = intMethod(slot, "getCardStateInfo");
                    if (state != null) {
                        result.put(i, state);
                    }
                }
            }
        } catch (Throwable throwable) {
            log("getUiccSlotsInfo unavailable: " + readable(throwable));
        }
        return result;
    }

    private static List<CardSnapshot> readCards() {
        List<CardSnapshot> result = new ArrayList<>();
        try {
            Method method = TelephonyManager.class.getMethod("getUiccCardsInfo");
            Object value = method.invoke(telephony);
            if (!(value instanceof Collection)) {
                return result;
            }
            for (Object card : (Collection<?>) value) {
                if (card == null) {
                    continue;
                }
                Integer slot = intMethod(card, "getPhysicalSlotIndex");
                if (slot == null) {
                    slot = intMethod(card, "getSlotIndex");
                }
                if (slot == null) {
                    continue;
                }
                Boolean euicc = boolMethod(card, "isEuicc");
                CardSnapshot snapshot = new CardSnapshot(slot, euicc != null && euicc);
                try {
                    Method portsMethod = card.getClass().getMethod("getPorts");
                    Object portsValue = portsMethod.invoke(card);
                    if (portsValue instanceof Collection) {
                        for (Object port : (Collection<?>) portsValue) {
                            if (port == null) {
                                continue;
                            }
                            Integer portIndex = intMethod(port, "getPortIndex");
                            Integer logical = intMethod(port, "getLogicalSlotIndex");
                            Boolean active = boolMethod(port, "isActive");
                            snapshot.ports.add(new PortSnapshot(
                                    portIndex == null ? 0 : portIndex,
                                    logical == null ? slot : logical,
                                    active != null && active));
                        }
                    }
                } catch (Throwable ignored) {
                }
                result.add(snapshot);
            }
        } catch (Throwable throwable) {
            log("getUiccCardsInfo unavailable: " + readable(throwable));
        }
        log("cards=" + result.size());
        return result;
    }

    private static Integer intMethod(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            Object value = method.invoke(target);
            return value instanceof Integer ? (Integer) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Boolean boolMethod(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            Object value = method.invoke(target);
            return value instanceof Boolean ? (Boolean) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int responseInt(Object response, String methodName, int fallback) {
        Integer value = intMethod(response, methodName);
        return value == null ? fallback : value;
    }

    private static String responseHex(Object response, String methodName) {
        try {
            Method method = response.getClass().getMethod(methodName);
            Object value = method.invoke(response);
            return value instanceof byte[] ? toHex((byte[]) value) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    // --- helpers --------------------------------------------------------------

    private static Context createSystemContext() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        return (Context) activityThread.getMethod("getSystemContext").invoke(thread);
    }

    static JSONObject ok() {
        JSONObject json = new JSONObject();
        try {
            json.put("ok", true);
        } catch (Exception ignored) {
        }
        return json;
    }

    static JSONObject error(String code, String message) {
        JSONObject json = new JSONObject();
        try {
            json.put("ok", false);
            json.put("errorCode", code);
            json.put("errorMessage", message);
        } catch (Exception ignored) {
        }
        return json;
    }

    static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.US, "%02x", value & 0xFF));
        }
        return builder.toString();
    }

    static String readable(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isEmpty()
                ? root.getClass().getSimpleName()
                : root.getClass().getSimpleName() + ": " + message;
    }

    private static Exception asException(Throwable throwable) {
        return throwable instanceof Exception
                ? (Exception) throwable
                : new RuntimeException(throwable);
    }

    static void log(String message) {
        Log.i(TAG, message);
        try {
            File file = new File(LOG_FILE);
            if (file.exists() && file.length() > 512 * 1024) {
                // Keep the newest half rather than growing without bound.
                byte[] keep = new byte[256 * 1024];
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
                    raf.seek(file.length() - keep.length);
                    raf.readFully(keep);
                }
                try (FileOutputStream out = new FileOutputStream(file, false)) {
                    out.write(keep);
                }
            }
            String timestamp = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(new Date());
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write((timestamp + " " + message + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    // --- model ----------------------------------------------------------------

    private static final class Connection {
        final int slotIndex;
        final int portIndex;
        final int channel;
        final String aid;

        Connection(int slotIndex, int portIndex, int channel, String aid) {
            this.slotIndex = slotIndex;
            this.portIndex = portIndex;
            this.channel = channel;
            this.aid = aid;
        }
    }

    private static final class CardSnapshot {
        final int slotIndex;
        final boolean isEuicc;
        final List<PortSnapshot> ports = new ArrayList<>();

        CardSnapshot(int slotIndex, boolean isEuicc) {
            this.slotIndex = slotIndex;
            this.isEuicc = isEuicc;
        }
    }

    private static final class PortSnapshot {
        final int portIndex;
        final int logicalSlotIndex;
        final boolean active;

        PortSnapshot(int portIndex, int logicalSlotIndex, boolean active) {
            this.portIndex = portIndex;
            this.logicalSlotIndex = logicalSlotIndex;
            this.active = active;
        }
    }

    /** One APDU split into the fields the telephony service accepts. */
    static final class Apdu {
        final int cla;
        final int ins;
        final int p1;
        final int p2;
        final int p3;
        final String data;

        Apdu(int cla, int ins, int p1, int p2, int p3, String data) {
            this.cla = cla;
            this.ins = ins;
            this.p1 = p1;
            this.p2 = p2;
            this.p3 = p3;
            this.data = data;
        }

        static Apdu parse(String hex) {
            String clean = hex == null ? "" : hex.replaceAll("[^0-9a-fA-F]", "");
            if ((clean.length() & 1) == 1) {
                throw new IllegalArgumentException("Odd-length APDU");
            }
            byte[] bytes = new byte[clean.length() / 2];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
            }
            if (bytes.length < 5) {
                throw new IllegalArgumentException("APDU shorter than a header");
            }
            int cla = bytes[0] & 0xFF;
            int ins = bytes[1] & 0xFF;
            int p1 = bytes[2] & 0xFF;
            int p2 = bytes[3] & 0xFF;
            int p3 = bytes[4] & 0xFF;

            // Extended length: 00 followed by a two-byte Lc/Le.
            if (p3 == 0 && bytes.length >= 7) {
                int extended = ((bytes[5] & 0xFF) << 8) | (bytes[6] & 0xFF);
                int body = bytes.length - 7;
                if (body >= extended) {
                    return new Apdu(cla, ins, p1, p2, extended,
                            toHexRange(bytes, 7, 7 + extended));
                }
                // Case 2E: only an extended Le follows.
                return new Apdu(cla, ins, p1, p2, extended, "");
            }

            int body = bytes.length - 5;
            if (body == 0) {
                // Case 1 (header only) or case 2S where P3 is Le.
                return new Apdu(cla, ins, p1, p2, p3, "");
            }
            if (body == p3) {
                // Case 3S: P3 is Lc and the body is the command data.
                return new Apdu(cla, ins, p1, p2, p3, toHexRange(bytes, 5, bytes.length));
            }
            if (body == p3 + 1) {
                // Case 4S: P3 is Lc, the body is data and one Le byte.
                return new Apdu(cla, ins, p1, p2, p3, toHexRange(bytes, 5, 5 + p3));
            }
            // Be forgiving like the upstream bridge: treat what follows as data.
            return new Apdu(cla, ins, p1, p2, body, toHexRange(bytes, 5, bytes.length));
        }

        private static String toHexRange(byte[] bytes, int start, int end) {
            StringBuilder builder = new StringBuilder((end - start) * 2);
            for (int i = start; i < end; i++) {
                builder.append(String.format(Locale.US, "%02x", bytes[i] & 0xFF));
            }
            return builder.toString();
        }
    }
}
