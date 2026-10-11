package com.os4.canvas;
import android.content.Context;
import android.os.SystemClock;
import io.github.libxposed.service.*;
import java.util.*;

/** Scope approval belongs to LSPosed, never to the host app or a database edit. */
final class ScopeRequester {
    private static volatile XposedService service;
    private static volatile boolean pending;
    private static volatile boolean requested;
    private static volatile long requestedAt;
    private static volatile String status = "enable-module";
    static void init(Context context) {
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override public void onServiceBind(XposedService value) {
                service = value;
                if (requested || context.getSharedPreferences("canvas", 0).getBoolean("enabled", false)) request();
            }
            @Override public void onServiceDied(XposedService value) {
                if (service == value) { service = null; pending = false; status = "enable-module"; }
            }
        });
    }
    static synchronized void request() {
        requested = true;
        XposedService current = service;
        if (current == null) { status = "enable-module"; return; }
        if (pending && SystemClock.elapsedRealtime() - requestedAt < 60000L) return;
        try {
            List<String> missing = new ArrayList<>(Arrays.asList("com.spotify.music", "com.android.systemui"));
            missing.removeAll(current.getScope());
            if (missing.isEmpty()) { pending = false; status = "ready"; return; }
            pending = true; requestedAt = SystemClock.elapsedRealtime(); status = "awaiting-approval";
            current.requestScope(missing, new XposedService.OnScopeEventListener() {
                @Override public void onScopeRequestApproved(List<String> scope) { pending = false; status = "restart-scopes"; }
                @Override public void onScopeRequestFailed(String message) { pending = false; status = "approval-failed"; }
            });
        } catch (Throwable ignored) { pending = false; status = "approval-failed"; }
    }
    static boolean connected() { return service != null; }
    static boolean granted() {
        XposedService current = service;
        if (current == null) return false;
        try { return current.getScope().containsAll(Arrays.asList("com.spotify.music", "com.android.systemui")); }
        catch (Throwable ignored) { return false; }
    }
    static boolean systemLoaded() {
        XposedService current = service;
        if (current == null) return false;
        try {
            for (HookedTarget target : current.getRunningTargets()) {
                if ("com.android.systemui".equals(target.getProcessName())
                        && target.getState() == HookedTarget.State.UP_TO_DATE
                        && target.getLoadedVersionCode() == BuildConfig.VERSION_CODE) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }
    static String state() {
        if (service == null) return "enable-module";
        if (granted()) { pending = false; return "ready"; }
        if (pending && SystemClock.elapsedRealtime() - requestedAt >= 60000L) { pending = false; status = "approval-failed"; }
        return "ready".equals(status) || "restart-scopes".equals(status) ? "request-scopes" : status;
    }
}
