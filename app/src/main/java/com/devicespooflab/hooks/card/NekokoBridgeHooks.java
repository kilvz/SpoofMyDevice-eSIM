package com.devicespooflab.hooks.card;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import java.lang.reflect.Field;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Optional in-process shortcut for NekokoLPA2.
 *
 * <p>The exported provider is enough on its own. This hook exists for the case
 * where another NBridge helper (OTBridge) is installed as a system app and wins
 * the provider authority while still being unable to reach the card. NekokoLPA2
 * then calls this module's root daemon directly, no matter which provider
 * PackageManager would have picked.
 */
public final class NekokoBridgeHooks {

    private static final String TAG = "SpoofMyDevice";
    private static final String PACKAGE = "ee.nekoko.nlpa2";
    private static final String OPEN_PACKAGE = "ee.nekoko.nlpa2.open";

    private NekokoBridgeHooks() {
    }

    public static void hook(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (!PACKAGE.equals(loadPackageParam.packageName)
                && !OPEN_PACKAGE.equals(loadPackageParam.packageName)) {
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(
                    PACKAGE + ".NBridgePlugin",
                    loadPackageParam.classLoader,
                    "isProviderAvailable",
                    String.class,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            return true;
                        }
                    });
            XposedBridge.log(TAG + ": Nekoko NBridge provider check bypassed");
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": NBridge plugin hook skipped: " + throwable);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    ContentResolver.class,
                    "call",
                    Uri.class,
                    String.class,
                    String.class,
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Uri uri = (Uri) param.args[0];
                            if (uri == null
                                    || !CardBridgeProvider.AUTHORITY.equals(uri.getAuthority())) {
                                return;
                            }
                            Context context = resolverContext(param.thisObject);
                            if (context == null) {
                                context = currentApplication();
                            }
                            if (context == null) {
                                return;
                            }
                            param.setResult(CardBridge.call(
                                    context.getApplicationContext(),
                                    (String) param.args[1],
                                    (Bundle) param.args[3]));
                        }
                    });
            XposedBridge.log(TAG + ": Nekoko NBridge calls routed to root card daemon");
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": NBridge resolver hook skipped: " + throwable);
        }
    }

    private static Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object value = activityThread.getMethod("currentApplication").invoke(null);
            return value instanceof Context ? (Context) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Context resolverContext(Object resolver) {
        try {
            Field field = ContentResolver.class.getDeclaredField("mContext");
            field.setAccessible(true);
            Object value = field.get(resolver);
            return value instanceof Context ? (Context) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
