package com.os4.canvas;
import android.app.Application;
public final class CanvasApplication extends Application {
    @Override public void onCreate() { super.onCreate(); ScopeRequester.init(this); }
}
