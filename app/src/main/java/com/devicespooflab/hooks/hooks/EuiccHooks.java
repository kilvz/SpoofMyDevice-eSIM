package com.devicespooflab.hooks.hooks;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.devicespooflab.hooks.utils.ConfigManager;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hooks the eUICC surface so a spoofed phone profile appears to have an embedded SIM.
 *
 * <p>When an app asks the system to download a profile through {@link
 * android.telephony.euicc.EuiccManager}, the request is forwarded to NekokoLPA as an
 * {@code lpa:1$...} deep link. NekokoLPA already understands that URI, so no changes are
 * needed in NekokoLPA itself.</p>
 */
public final class EuiccHooks {

    private static final String TAG = "SpoofMyDevice-Euicc";
    private static final int SYNTHETIC_CARD_ID = 1;
    private static final int SYNTHETIC_PHYSICAL_SLOT_INDEX = 0;
    private static final int SYNTHETIC_PORT_INDEX = 0;
    private static final int SYNTHETIC_LOGICAL_SLOT_INDEX = 0;
    private static final int EUICC_RESULT_OK = 0;

    private static final String NEKOKO_ACTIVITY = "ee.nekoko.nlpa2.MainActivity";
    private static final String[] NEKOKO_PACKAGES = {
        "ee.nekoko.nlpa2.open",
        "ee.nekoko.nlpa2"
    };

    /**
     * Apps that talk to a real eUICC card. They must see the framework's real card,
     * subscription and EID data, so telephony/eUICC spoofing is skipped for them.
     */
    private static final String[] PHYSICAL_EUICC_PACKAGES = {
        "ee.nekoko.nlpa2",
        "ee.nekoko.nlpa2.open",
        "ee.nekoko.nlpa2.community",
        "ee.nekoko.nbridge",
        "im.angry.openeuicc",
        "im.angry.easyeuicc"
    };

    private static final List<WeakReference<Object>> SYNTHETIC_CARDS = new ArrayList<>();
    private static final List<WeakReference<Object>> SYNTHETIC_PORTS = new ArrayList<>();

    private EuiccHooks() {
    }

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> euiccManager = XposedHelpers.findClassIfExists(
                "android.telephony.euicc.EuiccManager",
                lpparam.classLoader
        );
        Class<?> euiccInfo = XposedHelpers.findClassIfExists(
                "android.telephony.euicc.EuiccInfo",
                lpparam.classLoader
        );
        Class<?> uiccCardInfo = XposedHelpers.findClassIfExists(
                "android.telephony.UiccCardInfo",
                lpparam.classLoader
        );
        Class<?> uiccPortInfo = XposedHelpers.findClassIfExists(
                "android.telephony.UiccPortInfo",
                lpparam.classLoader
        );

        if (euiccManager != null) {
            hookEuiccManager(euiccManager, euiccInfo, lpparam.packageName);
        }
        if (uiccCardInfo != null) {
            hookUiccCardInfo(uiccCardInfo, uiccPortInfo);
        }
        hookTelephonyManager(lpparam, uiccCardInfo, uiccPortInfo);
    }

    private static void hookEuiccManager(Class<?> euiccManager, Class<?> euiccInfoClass, String packageName) {
        hookAfter(euiccManager, "isEnabled", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                // Only answer true when the real framework says there is no eUICC. A device
                // with a working eUICC (including a removable card exposed by the ROM) keeps
                // its native answer.
                if (param.getResult() instanceof Boolean && (Boolean) param.getResult()) {
                    return;
                }
                if (ConfigManager.shouldReportEsim()) {
                    param.setResult(true);
                }
            }
        });

        hookBefore(euiccManager, "getEid", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object original = invokeOriginal(param);
                if (original instanceof String && !((String) original).isEmpty()) {
                    param.setResult(original);
                    return;
                }
                if (!ConfigManager.shouldReportEsim()) {
                    return;
                }
                String eid = ConfigManager.getEid();
                if (eid != null && !eid.isEmpty()) {
                    param.setResult(eid);
                }
            }
        });

        hookAfter(euiccManager, "getEuiccInfo", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.getResult() != null) {
                    return;
                }
                if (!ConfigManager.shouldReportEsim()) {
                    return;
                }
                Object info = createEuiccInfo(euiccInfoClass);
                if (info != null) {
                    param.setResult(info);
                }
            }
        });

        hookDownloadSubscription(euiccManager, packageName, false);
        hookDownloadSubscription(euiccManager, packageName, true);
    }

    private static void hookDownloadSubscription(Class<?> euiccManager, String packageName, boolean withCallback) {
        XC_MethodHook callback = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!ConfigManager.shouldReportEsim() || !ConfigManager.shouldForwardEsim()) {
                    return;
                }
                if (isNekokoPackage(packageName)) {
                    return;
                }
                String code = extractActivationCode(param.args.length > 0 ? param.args[0] : null);
                if (code == null) {
                    return;
                }
                Context context = ConfigManager.getAppContext();
                if (context == null) {
                    XposedBridge.log(TAG + ": no context available to forward eSIM activation");
                    return;
                }
                if (!forwardToNekoko(context, code)) {
                    return;
                }

                completeCallback(param);
                param.setResult(null);
                XposedBridge.log(TAG + ": forwarded eSIM activation " + redact(code) + " to NekokoLPA");
            }
        };

        try {
            if (withCallback) {
                XposedHelpers.findAndHookMethod(
                        euiccManager,
                        "downloadSubscription",
                        "android.telephony.euicc.DownloadableSubscription",
                        boolean.class,
                        PendingIntent.class,
                        callback
                );
            } else {
                XposedHelpers.findAndHookMethod(
                        euiccManager,
                        "downloadSubscription",
                        "android.telephony.euicc.DownloadableSubscription",
                        boolean.class,
                        callback
                );
            }
        } catch (Throwable ignored) {
            // Method is unavailable on this Android version.
        }
    }

    private static void completeCallback(XC_MethodHook.MethodHookParam param) {
        if (param.args.length < 3 || !(param.args[2] instanceof PendingIntent)) {
            return;
        }
        try {
            // The caller only needs to know the request was accepted; installation continues
            // in NekokoLPA where the user confirms the profile.
            ((PendingIntent) param.args[2]).send(EUICC_RESULT_OK);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to complete activation callback: " + t);
        }
    }

    private static String extractActivationCode(Object subscription) {
        if (subscription == null) {
            return null;
        }

        String code = null;
        try {
            Object value = XposedHelpers.callMethod(subscription, "getEncodedActivationCode");
            if (value instanceof String) {
                code = (String) value;
            }
        } catch (Throwable ignored) {
        }
        if (code == null || code.trim().isEmpty()) {
            try {
                Object value = XposedHelpers.getObjectField(subscription, "encodedActivationCode");
                if (value instanceof String) {
                    code = (String) value;
                }
            } catch (Throwable ignored) {
            }
        }
        if (code == null) {
            return null;
        }

        code = code.trim();
        return code.isEmpty() ? null : code;
    }

    private static boolean forwardToNekoko(Context context, String code) {
        Uri uri = Uri.parse(normalizeActivationCode(code));
        for (String targetPackage : NEKOKO_PACKAGES) {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.setClassName(targetPackage, NEKOKO_ACTIVITY);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try {
                context.startActivity(intent);
                return true;
            } catch (Throwable ignored) {
                // Try the other NekokoLPA package name.
            }
        }
        XposedBridge.log(TAG + ": NekokoLPA is not installed or its launch activity is unavailable");
        return false;
    }

    private static String normalizeActivationCode(String code) {
        String trimmed = code.trim();
        int index = trimmed.indexOf("1$");
        if (index >= 0) {
            return "lpa:" + trimmed.substring(index);
        }
        if (trimmed.regionMatches(true, 0, "LPA:", 0, 4)) {
            return "lpa:" + trimmed.substring(4);
        }
        return "lpa:" + trimmed;
    }

    private static String redact(String code) {
        int index = code.indexOf("$");
        if (index < 0 || code.length() < index + 8) {
            return "lpa:***";
        }
        return code.substring(0, Math.min(index + 4, code.length())) + "***";
    }

    private static boolean isNekokoPackage(String packageName) {
        for (String target : NEKOKO_PACKAGES) {
            if (target.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isPhysicalEuiccPackage(String packageName) {
        for (String target : PHYSICAL_EUICC_PACKAGES) {
            if (target.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    private static void hookTelephonyManager(
            XC_LoadPackage.LoadPackageParam lpparam,
            Class<?> uiccCardInfoClass,
            Class<?> uiccPortInfoClass
    ) {
        Class<?> telephonyManager = XposedHelpers.findClassIfExists(
                "android.telephony.TelephonyManager",
                lpparam.classLoader
        );
        if (telephonyManager == null) {
            return;
        }

        hookBefore(telephonyManager, "getUiccCardsInfo", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!ConfigManager.shouldReportEsim()) {
                    return;
                }
                // Never hide cards the framework already reports. This is what lets a
                // privileged LPA (NekokoLPA2 / OpenEUICC) enumerate the user's removable
                // eUICC and open logical channels on it through TelephonyManager.
                Object original = invokeOriginal(param);
                if (original instanceof List && !((List<?>) original).isEmpty()) {
                    param.setResult(original);
                    return;
                }
                Object card = createUiccCardInfo(uiccCardInfoClass, uiccPortInfoClass);
                if (card != null) {
                    param.setResult(Collections.singletonList(card));
                }
            }
        });

        hookBefore(telephonyManager, "getCardIdForDefaultEuicc", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!ConfigManager.shouldReportEsim()) {
                    return;
                }
                Object original = invokeOriginal(param);
                if (original instanceof Integer && (Integer) original >= 0) {
                    param.setResult(original);
                    return;
                }
                param.setResult(SYNTHETIC_CARD_ID);
            }
        });
    }

    private static void hookUiccCardInfo(Class<?> uiccCardInfoClass, Class<?> uiccPortInfoClass) {
        hookSyntheticGetter(uiccCardInfoClass, "isEuicc", SYNTHETIC_CARDS, () -> true);
        hookSyntheticGetter(uiccCardInfoClass, "getEid", SYNTHETIC_CARDS, ConfigManager::getEid);
        hookSyntheticGetter(uiccCardInfoClass, "getCardId", SYNTHETIC_CARDS, () -> SYNTHETIC_CARD_ID);
        hookSyntheticGetter(uiccCardInfoClass, "getSlotIndex", SYNTHETIC_CARDS, () -> SYNTHETIC_PHYSICAL_SLOT_INDEX);
        hookSyntheticGetter(uiccCardInfoClass, "getPhysicalSlotIndex", SYNTHETIC_CARDS, () -> SYNTHETIC_PHYSICAL_SLOT_INDEX);
        hookSyntheticGetter(uiccCardInfoClass, "isRemovable", SYNTHETIC_CARDS, () -> false);
        hookSyntheticGetter(uiccCardInfoClass, "isMultipleEnabledProfilesSupported", SYNTHETIC_CARDS, () -> false);
        hookSyntheticGetter(uiccCardInfoClass, "getPorts", SYNTHETIC_CARDS, () -> {
            Object port = createUiccPortInfo(uiccPortInfoClass);
            return port == null ? Collections.emptyList() : Collections.singletonList(port);
        });

        if (uiccPortInfoClass != null) {
            hookSyntheticGetter(uiccPortInfoClass, "getPortIndex", SYNTHETIC_PORTS, () -> SYNTHETIC_PORT_INDEX);
            hookSyntheticGetter(uiccPortInfoClass, "getLogicalSlotIndex", SYNTHETIC_PORTS, () -> SYNTHETIC_LOGICAL_SLOT_INDEX);
            hookSyntheticGetter(uiccPortInfoClass, "isActive", SYNTHETIC_PORTS, () -> true);
            hookSyntheticGetter(uiccPortInfoClass, "getIccId", SYNTHETIC_PORTS, ConfigManager::getICCID);
        }
    }

    private static Object createUiccCardInfo(Class<?> cardClass, Class<?> portClass) {
        if (cardClass == null) {
            return null;
        }

        Object port = createUiccPortInfo(portClass);
        List<Object> ports = port == null
                ? Collections.emptyList()
                : Collections.singletonList(port);

        Object card = null;
        try {
            card = XposedHelpers.newInstance(
                    cardClass,
                    true,
                    SYNTHETIC_CARD_ID,
                    ConfigManager.getEid(),
                    SYNTHETIC_PHYSICAL_SLOT_INDEX,
                    false,
                    false,
                    ports
            );
        } catch (Throwable ignored) {
        }
        if (card == null) {
            card = allocateInstance(cardClass);
        }
        if (card != null) {
            track(SYNTHETIC_CARDS, card);
        }
        return card;
    }

    private static Object createUiccPortInfo(Class<?> portClass) {
        if (portClass == null) {
            return null;
        }
        Object port = null;
        try {
            port = XposedHelpers.newInstance(
                    portClass,
                    ConfigManager.getICCID(),
                    SYNTHETIC_PORT_INDEX,
                    SYNTHETIC_LOGICAL_SLOT_INDEX,
                    true
            );
        } catch (Throwable ignored) {
        }
        if (port == null) {
            port = allocateInstance(portClass);
        }
        if (port != null) {
            track(SYNTHETIC_PORTS, port);
        }
        return port;
    }

    private static Object createEuiccInfo(Class<?> euiccInfoClass) {
        if (euiccInfoClass == null) {
            return null;
        }
        try {
            return XposedHelpers.newInstance(euiccInfoClass, "2.2.0");
        } catch (Throwable ignored) {
            return allocateInstance(euiccInfoClass);
        }
    }

    private static void hookSyntheticGetter(
            Class<?> targetClass,
            String methodName,
            List<WeakReference<Object>> tracker,
            ValueProvider provider
    ) {
        hookBefore(targetClass, methodName, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isTracked(tracker, param.thisObject)) {
                    try {
                        param.setResult(provider.get());
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    private static Object invokeOriginal(XC_MethodHook.MethodHookParam param) {
        try {
            return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void hookBefore(Class<?> targetClass, String methodName, XC_MethodHook callback) {
        if (targetClass == null) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(targetClass, methodName, callback);
        } catch (Throwable ignored) {
            // Method or class is unavailable on this Android version.
        }
    }

    private static void hookAfter(Class<?> targetClass, String methodName, XC_MethodHook callback) {
        if (targetClass == null) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(targetClass, methodName, callback);
        } catch (Throwable ignored) {
            // Method or class is unavailable on this Android version.
        }
    }

    private static void track(List<WeakReference<Object>> tracker, Object value) {
        synchronized (tracker) {
            tracker.add(new WeakReference<>(value));
            if (tracker.size() > 32) {
                for (int i = tracker.size() - 1; i >= 0; i--) {
                    if (tracker.get(i).get() == null) {
                        tracker.remove(i);
                    }
                }
            }
        }
    }

    private static boolean isTracked(List<WeakReference<Object>> tracker, Object value) {
        synchronized (tracker) {
            for (int i = tracker.size() - 1; i >= 0; i--) {
                Object tracked = tracker.get(i).get();
                if (tracked == null) {
                    tracker.remove(i);
                } else if (tracked == value) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Object allocateInstance(Class<?> targetClass) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);
            Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
            return allocateInstance.invoke(unsafe, targetClass);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private interface ValueProvider {
        Object get();
    }
}
