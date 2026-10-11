package com.os4.canvas;

import android.app.Activity;
import android.os.Bundle;

/** Starts the module in the foreground so LSPosed can deliver its service and approval UI. */
public final class ActivationActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScopeRequester.request();
        finish();
    }
}
