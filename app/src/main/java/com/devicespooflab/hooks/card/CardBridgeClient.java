package com.devicespooflab.hooks.card;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * App-side client for {@link CardDaemon}.
 *
 * <p>NekokoLPA2 asks its {@code ee.nekoko.nbridge.provider} for card operations.
 * {@link CardBridgeProvider} answers those calls here, and this class forwards
 * them over loopback to the root daemon. The daemon is started on demand with
 * {@code su}; its port and token are kept in the app's private files so a later
 * provider process can reconnect instead of starting another one.
 */
final class CardBridgeClient {

    private static final String TAG = "SpoofMyDeviceCard";
    private static final String MODULE_PACKAGE = "com.spoofmydevice";
    private static final int CONNECT_TIMEOUT_MS = 4_000;
    private static final int READ_TIMEOUT_MS = 120_000;
    private static final int START_TIMEOUT_MS = 30_000;

    private final Context context;
    private int port;
    private String token;
    private Process process;
    private volatile long startedAt;

    CardBridgeClient(Context context) {
        this.context = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
    }

    // --- NBridge protocol -----------------------------------------------------

    Bundle listSlots() throws Exception {
        return toBundle(request("listSlots", new JSONObject()));
    }

    Bundle connectLogicalChannel(Bundle extras) throws Exception {
        String slotId = extras == null ? null : extras.getString("slotId");
        ArrayList<String> aids = extras == null
                ? null
                : extras.getStringArrayList("aids");
        int[] slot = parseSlotId(slotId);

        JSONObject request = new JSONObject();
        request.put("slot", slot[0]);
        request.put("port", slot[1]);
        JSONArray array = new JSONArray();
        if (aids != null) {
            for (String aid : aids) {
                array.put(aid);
            }
        }
        request.put("aids", array);
        return toBundle(request("open", request));
    }

    Bundle transmitLogical(Bundle extras) throws Exception {
        JSONObject request = new JSONObject();
        request.put("connectionId", extras == null ? "" : extras.getString("connectionId", ""));
        request.put("apdu", extras == null ? "" : extras.getString("apdu", ""));
        return toBundle(request("transmitLogical", request));
    }

    Bundle transmitBasic(Bundle extras) throws Exception {
        String slotId = extras == null ? null : extras.getString("slotId");
        int[] slot = parseSlotId(slotId);
        JSONObject request = new JSONObject();
        request.put("slot", slot[0]);
        request.put("port", slot[1]);
        request.put("apdu", extras == null ? "" : extras.getString("apdu", ""));
        return toBundle(request("transmitBasic", request));
    }

    Bundle closeLogicalChannel(Bundle extras) throws Exception {
        JSONObject request = new JSONObject();
        request.put("connectionId", extras == null ? "" : extras.getString("connectionId", ""));
        return toBundle(request("close", request));
    }

    Bundle diagnostics() throws Exception {
        return toBundle(request("diag", new JSONObject()));
    }

    Bundle probe(Bundle extras) throws Exception {
        JSONObject request = new JSONObject();
        request.put("slot", extras == null ? 0 : extras.getInt("slot", 0));
        request.put("port", extras == null ? 0 : extras.getInt("port", 0));
        return toBundle(request("probe", request));
    }

    // --- daemon lifecycle -----------------------------------------------------

    private synchronized JSONObject request(String op, JSONObject arguments) throws Exception {
        ensureStarted();
        JSONObject request = new JSONObject();
        request.put("op", op);
        request.put("token", token);
        if (arguments != null) {
            java.util.Iterator<String> keys = arguments.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                request.put(key, arguments.get(key));
            }
        }

        try {
            return send(request);
        } catch (IOException first) {
            Log.w(TAG, "Daemon connection lost, restarting", first);
            stopDaemon();
            ensureStarted();
            request.put("token", token);
            return send(request);
        }
    }

    private JSONObject send(JSONObject request) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port),
                    CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8), true);
            writer.println(request.toString());

            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null) {
                throw new IOException("Card daemon closed the connection");
            }
            return new JSONObject(line);
        }
    }

    private synchronized void ensureStarted() throws Exception {
        if (port > 0 && token != null && ping()) {
            return;
        }
        port = 0;
        token = null;
        process = null;

        if (loadState() && ping()) {
            return;
        }

        startDaemon();
    }

    private boolean ping() {
        try {
            JSONObject request = new JSONObject();
            request.put("op", "ping");
            request.put("token", token);
            JSONObject response = send(request);
            if (!response.optBoolean("ok", false)) {
                return false;
            }
            if (response.optInt("daemon", 0) != CardDaemon.DAEMON_VERSION) {
                // A daemon from an older APK is still running; stop it so the
                // new code is used.
                killDaemon(response.optInt("pid", 0));
                port = 0;
                token = null;
                return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void killDaemon(int pid) {
        if (pid <= 0) {
            return;
        }
        try {
            Process killer = startRootShell("kill -9 " + pid);
            killer.waitFor(3, TimeUnit.SECONDS);
        } catch (Throwable ignored) {
        }
    }

    private void startDaemon() throws Exception {
        if (System.currentTimeMillis() - startedAt < 5_000) {
            // A just-started daemon may still be coming up; do not fork another.
            throw new IOException("Card daemon is starting");
        }
        startedAt = System.currentTimeMillis();

        token = randomToken();
        String command = "CLASSPATH=" + shellQuote(moduleApkPath())
                + " /system/bin/app_process / "
                + CardDaemon.class.getName()
                + " " + token
                + " " + shellQuote(context.getPackageName());
        Log.i(TAG, "Starting card daemon: " + command);

        process = startRootShell(command);

        LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Process started = process;
        Thread pump = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    started.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.offer(line);
                }
            } catch (Throwable ignored) {
            }
        }, "cardd-output");
        pump.setDaemon(true);
        pump.start();

        long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
        StringBuilder output = new StringBuilder();
        while (System.currentTimeMillis() < deadline) {
            String line = lines.poll(500, TimeUnit.MILLISECONDS);
            if (line == null) {
                continue;
            }
            output.append(line).append(' ');
            if (line.startsWith("READY ")) {
                port = Integer.parseInt(line.substring("READY ".length()).trim());
                saveState();
                Log.i(TAG, "Card daemon ready on port " + port);
                // Keep draining so the daemon never blocks on a full stdout pipe.
                Thread drain = new Thread(() -> {
                    try {
                        String ignored;
                        while ((ignored = lines.poll(60, TimeUnit.SECONDS)) != null
                                || started.isAlive()) {
                            if (ignored != null) {
                                Log.d(TAG, "cardd: " + ignored);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }, "cardd-drain");
                drain.setDaemon(true);
                drain.start();
                return;
            }
            Log.i(TAG, "cardd: " + line);
        }

        String detail = started.isAlive()
                ? "timed out waiting for the root card daemon"
                : "the root card daemon exited";
        stopDaemon();
        String tail = output.toString().trim();
        throw new IOException(detail
                + (tail.isEmpty() ? "" : ": " + tail)
                + "; grant root access to SpoofMyDevice and retry");
    }

    private static Process startRootShell(String command) throws IOException {
        String[] candidates = {
                "su",
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/debug_ramdisk/su",
        };
        IOException failure = null;
        for (String candidate : candidates) {
            try {
                ProcessBuilder builder = new ProcessBuilder(candidate, "-c", command);
                builder.redirectErrorStream(true);
                return builder.start();
            } catch (IOException exception) {
                failure = exception;
            }
        }
        throw failure != null ? failure : new IOException("su not found");
    }

    private void stopDaemon() {
        if (process != null) {
            try {
                process.destroy();
            } catch (Throwable ignored) {
            }
        }
        process = null;
        port = 0;
        token = null;
    }

    // --- state ----------------------------------------------------------------

    private File stateFile() {
        return new File(context.getFilesDir(), CardDaemon.STATE_FILE);
    }

    private boolean loadState() {
        File file = stateFile();
        if (!file.isFile()) {
            return false;
        }
        Properties properties = new Properties();
        try (FileInputStream in = new FileInputStream(file)) {
            properties.load(in);
            port = Integer.parseInt(properties.getProperty("port", "0"));
            token = properties.getProperty("token", "");
            return port > 0 && token != null && !token.isEmpty();
        } catch (Throwable throwable) {
            return false;
        }
    }

    private void saveState() {
        Properties properties = new Properties();
        properties.setProperty("port", String.valueOf(port));
        properties.setProperty("token", token == null ? "" : token);
        try (FileOutputStream out = new FileOutputStream(stateFile())) {
            properties.store(out, "SpoofMyDevice NekokoLPA bridge");
        } catch (Throwable throwable) {
            Log.w(TAG, "Could not persist card daemon state", throwable);
        }
    }

    // --- helpers --------------------------------------------------------------

    private String moduleApkPath() {
        PackageManager manager = context.getPackageManager();
        try {
            String path = manager.getApplicationInfo(MODULE_PACKAGE, 0).sourceDir;
            if (path != null && new File(path).isFile()) {
                return path;
            }
        } catch (Throwable ignored) {
        }
        try {
            android.content.pm.ProviderInfo provider =
                    manager.resolveContentProvider(CardBridgeProvider.AUTHORITY, 0);
            if (provider != null && MODULE_PACKAGE.equals(provider.packageName)
                    && provider.applicationInfo != null
                    && provider.applicationInfo.sourceDir != null
                    && new File(provider.applicationInfo.sourceDir).isFile()) {
                return provider.applicationInfo.sourceDir;
            }
        } catch (Throwable ignored) {
        }
        String own = context.getApplicationInfo().sourceDir;
        if (MODULE_PACKAGE.equals(context.getPackageName())) {
            return own;
        }
        throw new IllegalStateException(
                "Cannot locate the SpoofMyDevice APK to start the card daemon");
    }

    private static String randomToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.US, "%02x", value & 0xFF));
        }
        return builder.toString();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    static int[] parseSlotId(String slotId) {
        if (slotId == null) {
            throw new IllegalArgumentException("slotId is required");
        }
        String value = slotId.trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:tmapi:)?(?:slot:)?(\\d+)(?::port:?|:|p)(\\d+)",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(value);
        if (matcher.find()) {
            return new int[]{Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2))};
        }
        matcher = java.util.regex.Pattern
                .compile("(?:tmapi:)?(?:slot:)?(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(value);
        if (matcher.find()) {
            return new int[]{Integer.parseInt(matcher.group(1)), 0};
        }
        throw new IllegalArgumentException("Invalid slotId: " + slotId);
    }

    private static Bundle toBundle(JSONObject json) throws Exception {
        Bundle bundle = new Bundle();
        java.util.Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = json.get(key);
            if (value instanceof Boolean) {
                bundle.putBoolean(key, (Boolean) value);
            } else if (value instanceof Integer) {
                bundle.putInt(key, (Integer) value);
            } else if (value instanceof Long) {
                bundle.putLong(key, (Long) value);
            } else if (value instanceof Double) {
                bundle.putDouble(key, (Double) value);
            } else if (value instanceof JSONObject) {
                bundle.putBundle(key, toBundle((JSONObject) value));
            } else if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                ArrayList<Parcelable> items = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    Object item = array.get(i);
                    if (item instanceof JSONObject) {
                        items.add(toBundle((JSONObject) item));
                    }
                }
                bundle.putParcelableArrayList(key, items);
            } else if (value != JSONObject.NULL) {
                bundle.putString(key, String.valueOf(value));
            }
        }
        return bundle;
    }
}
