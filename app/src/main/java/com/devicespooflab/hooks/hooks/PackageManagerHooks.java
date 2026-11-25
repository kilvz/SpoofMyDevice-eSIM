package com.devicespooflab.hooks.hooks;

import com.devicespooflab.hooks.utils.ConfigManager;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hooks PackageManager system feature queries so the target profile can present as
 * a tablet or a phone. For SM-X900, telephony features must disappear.
 */
public class PackageManagerHooks {

    private static final String TAG = "DeviceSpoofLab-PackageManager";

    private static final Set<String> EMULATOR_FEATURES = new HashSet<>(Arrays.asList(
        "android.hardware.sensor.emulator",
        "goldfish"
    ));

    private static final String FEATURE_TELEPHONY_EUICC = "android.hardware.telephony.euicc";
    private static final String FEATURE_TELEPHONY_EUICC_MEP = "android.hardware.telephony.euicc.mep";

    private static final Set<String> TELEPHONY_FEATURES = new HashSet<>(Arrays.asList(
        "android.hardware.telephony",
        "android.hardware.telephony.gsm",
        "android.hardware.telephony.cdma",
        "android.hardware.telephony.ims",
        "android.hardware.telephony.euicc",
        "android.hardware.telephony.mbms"
    ));

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> appPackageManagerClass = XposedHelpers.findClassIfExists(
                "android.app.ApplicationPackageManager",
                lpparam.classLoader
            );

            if (appPackageManagerClass != null) {
                hookHasSystemFeature(appPackageManagerClass);
                hookGetSystemAvailableFeatures(appPackageManagerClass);
            }
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": Failed to hook PackageManager: " + throwable.getMessage());
        }
    }

    private static void hookHasSystemFeature(Class<?> pmClass) {
        try {
            XposedHelpers.findAndHookMethod(pmClass, "hasSystemFeature",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String feature = (String) param.args[0];
                        if (feature == null) {
                            return;
                        }
                        Boolean override = overrideFeature(feature);
                        if (override != null) {
                            param.setResult(override);
                        }
                    }
                });
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": Failed to hook hasSystemFeature(String): " + throwable.getMessage());
        }

        try {
            XposedHelpers.findAndHookMethod(pmClass, "hasSystemFeature",
                String.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String feature = (String) param.args[0];
                        if (feature == null) {
                            return;
                        }
                        Boolean override = overrideFeature(feature);
                        if (override != null) {
                            param.setResult(override);
                        }
                    }
                });
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": Failed to hook hasSystemFeature(String, int): " + throwable.getMessage());
        }
    }

    private static void hookGetSystemAvailableFeatures(Class<?> pmClass) {
        try {
            XposedHelpers.findAndHookMethod(pmClass, "getSystemAvailableFeatures",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object[] features = (Object[]) param.getResult();
                        if (features == null) {
                            return;
                        }

                        Class<?> featureInfoClass = features.getClass().getComponentType();
                        List<Object> filtered = new ArrayList<>();

                        for (Object feature : features) {
                            try {
                                String name = (String) XposedHelpers.getObjectField(feature, "name");
                                if (name == null) {
                                    filtered.add(feature);
                                    continue;
                                }

                                Boolean override = overrideFeature(name);
                                if (override == null || override) {
                                    filtered.add(feature);
                                }
                            } catch (Throwable ignored) {
                                filtered.add(feature);
                            }
                        }

                        if (ConfigManager.shouldReportEsim()) {
                            addFeatureIfMissing(filtered, featureInfoClass, FEATURE_TELEPHONY_EUICC);
                            addFeatureIfMissing(filtered, featureInfoClass, FEATURE_TELEPHONY_EUICC_MEP);
                        }

                        Object typedArray = Array.newInstance(featureInfoClass, filtered.size());
                        for (int index = 0; index < filtered.size(); index++) {
                            Array.set(typedArray, index, filtered.get(index));
                        }
                        param.setResult(typedArray);
                    }
                });
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": Failed to hook getSystemAvailableFeatures(): " + throwable.getMessage());
        }
    }

    private static void addFeatureIfMissing(List<Object> features, Class<?> featureInfoClass,
                                            String name) {
        for (Object feature : features) {
            try {
                if (name.equals(XposedHelpers.getObjectField(feature, "name"))) {
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            Object info = featureInfoClass.getConstructor().newInstance();
            XposedHelpers.setObjectField(info, "name", name);
            XposedHelpers.setIntField(info, "version", 1);
            features.add(info);
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": Failed to advertise feature " + name + ": "
                    + throwable.getMessage());
        }
    }

    private static Boolean overrideFeature(String featureName) {
        String normalized = featureName.toLowerCase(Locale.US);

        for (String denied : EMULATOR_FEATURES) {
            if (normalized.contains(denied.toLowerCase(Locale.US))) {
                return false;
            }
        }

        // eSIM support is its own switch: an eSIM profile must advertise the
        // eUICC features even when the rest of the telephony profile is off.
        if (FEATURE_TELEPHONY_EUICC.equals(featureName)) {
            return ConfigManager.shouldReportEsim() || ConfigManager.shouldExposeTelephony();
        }
        if (FEATURE_TELEPHONY_EUICC_MEP.equals(featureName)) {
            return ConfigManager.shouldReportEsim();
        }

        if (TELEPHONY_FEATURES.contains(featureName)) {
            return ConfigManager.shouldExposeTelephony();
        }

        if (ConfigManager.isTabletProfile() && "android.hardware.type.pc".equals(featureName)) {
            return false;
        }

        return null;
    }
}
