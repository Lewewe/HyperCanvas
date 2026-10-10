package com.os4.canvas;

import android.content.*;

/** Restores bridge visibility after an APK update or device restart, without changing scopes. */
public final class BridgeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!"com.os4.canvas.REQUEST_BRIDGE".equals(intent.getAction())) return;
        String[] packages = context.getPackageManager().getPackagesForUid(getSentFromUid());
        if (packages == null) return;
        for (String name : packages) {
            if ("com.spotify.music".equals(name) || "com.android.systemui".equals(name)) {
                CanvasProvider.grantBridgeVisibility(context);
                return;
            }
        }
    }
}
