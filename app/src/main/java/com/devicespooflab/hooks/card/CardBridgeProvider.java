package com.devicespooflab.hooks.card;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

/**
 * The bridge provider NekokoLPA2 looks for.
 *
 * <p>NekokoLPA2 lists and drives removable eUICCs through
 * {@code content://ee.nekoko.nbridge.provider} when it is installed. Its own
 * helper (OTBridge) needs either OMAPI or a privileged Telephony grant; this
 * provider answers the same protocol from a root daemon instead
 * ({@link CardDaemon}), so a ROM with neither still gets a working reader.
 *
 * <p>The provider is exported because NekokoLPA2 is a different app, but it
 * only serves NekokoLPA2 callers and the shell user. Everything else is
 * rejected before any card operation happens.
 */
public class CardBridgeProvider extends ContentProvider {

    public static final String AUTHORITY = "ee.nekoko.nbridge.provider";

    private static final String TAG = "SpoofMyDeviceCard";
    private static final String LPA_PACKAGE = "ee.nekoko.nlpa2";
    private static final String LPA_OPEN_PACKAGE = "ee.nekoko.nlpa2.open";
    private static final String BRIDGE_PACKAGE = "ee.nekoko.nbridge";
    private static final int SHELL_UID = 2000;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!isAllowedCaller()) {
            Log.w(TAG, "Rejected NBridge call from uid " + Binder.getCallingUid());
            return CardBridge.error("DENIED", "This bridge only serves NekokoLPA2");
        }
        Log.i(TAG, "NBridge call " + method);
        return CardBridge.call(getContext(), method, extras);
    }

    private boolean isAllowedCaller() {
        int uid = Binder.getCallingUid();
        // Root is already omnipotent; allowing it keeps `su -c content call`
        // diagnostics working from a terminal (shell uid is allowed too).
        if (uid == 0 || uid == Process.myUid() || uid == SHELL_UID) {
            return true;
        }
        Context context = getContext();
        if (context == null) {
            return false;
        }
        try {
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            if (packages != null) {
                for (String packageName : packages) {
                    if (LPA_PACKAGE.equals(packageName)
                            || LPA_OPEN_PACKAGE.equals(packageName)
                            || BRIDGE_PACKAGE.equals(packageName)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
