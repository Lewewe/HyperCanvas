package com.os4.canvas;
import android.content.Context;
import io.github.libxposed.service.*;
import java.util.*;

/** Scope approval belongs to LSPosed, never to the host app or a database edit. */
final class ScopeRequester {
    private static volatile XposedService service;
    private static volatile boolean pending;
    private static volatile String status = "enable-module";
    static void init(Context context) {
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override public void onServiceBind(XposedService value) {
                service = value; status = "ready";
                if (context.getSharedPreferences("canvas", 0).getBoolean("enabled", false)) request();
            }
            @Override public void onServiceDied(XposedService value) {
                if (service == value) { service = null; pending = false; status = "enable-module"; }
            }
        });
    }
    static synchronized void request() {
        XposedService current = service;
        if (current == null) { status = "enable-module"; return; }
        if (pending) return;
        try {
            List<String> missing = new ArrayList<>(Arrays.asList("com.spotify.music", "com.android.systemui"));
            missing.removeAll(current.getScope());
            if (missing.isEmpty()) { status = "ready"; return; }
            pending = true; status = "awaiting-approval";
            current.requestScope(missing, new XposedService.OnScopeEventListener() {
                @Override public void onScopeRequestApproved(List<String> scope) { pending = false; status = "restart-scopes"; }
                @Override public void onScopeRequestFailed(String message) { pending = false; status = "approval-failed"; }
            });
        } catch (Throwable ignored) { pending = false; status = "approval-failed"; }
    }
    static String state() { return status; }
}
