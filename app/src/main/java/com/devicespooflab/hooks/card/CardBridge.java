package com.devicespooflab.hooks.card;

import android.content.Context;
import android.os.Bundle;

/**
 * Public entry point used by both the provider and the LSPosed hook.
 *
 * <p>Keeping the protocol handling here means the same root daemon serves
 * NekokoLPA2 whether the call arrives through the exported provider or is
 * intercepted in NekokoLPA2's own process.
 */
public final class CardBridge {

    private static CardBridgeClient client;

    private CardBridge() {
    }

    public static synchronized Bundle call(Context context, String method, Bundle extras) {
        if (context == null) {
            return error("NO_CONTEXT", "Context unavailable");
        }
        if (client == null) {
            client = new CardBridgeClient(context);
        }
        try {
            switch (method == null ? "" : method) {
                case "listSlots":
                    return client.listSlots();
                case "listActiveConnections":
                    return emptyConnections();
                case "connectLogicalChannel":
                    return client.connectLogicalChannel(extras);
                case "transmitLogical":
                    return client.transmitLogical(extras);
                case "transmitBasic":
                    return client.transmitBasic(extras);
                case "closeLogicalChannel":
                    return client.closeLogicalChannel(extras);
                case "ping":
                case "start":
                case "diag":
                    return client.diagnostics();
                default:
                    return error("NOT_IMPLEMENTED", "Unknown method " + method);
            }
        } catch (Throwable throwable) {
            return error(throwable.getClass().getSimpleName(), message(throwable));
        }
    }

    public static synchronized void reset() {
        client = null;
    }

    private static Bundle emptyConnections() {
        Bundle bundle = new Bundle();
        bundle.putBoolean("ok", true);
        bundle.putParcelableArrayList("activeConnections", new java.util.ArrayList<>());
        return bundle;
    }

    static Bundle error(String code, String message) {
        Bundle bundle = new Bundle();
        bundle.putBoolean("ok", false);
        bundle.putString("errorCode", code);
        bundle.putString("errorMessage", message);
        return bundle;
    }

    private static String message(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String text = root.getMessage();
        return text == null || text.isEmpty()
                ? root.getClass().getSimpleName()
                : text;
    }
}
