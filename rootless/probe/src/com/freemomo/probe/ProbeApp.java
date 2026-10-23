package com.freemomo.probe;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Probe application: arms the engine from the Application static initializer
 * and hooks Application.attach the same way the production factory does, so a
 * hook firing here proves the boot-class and timing pattern the module relies
 * on.
 */
public class ProbeApp extends Application {

    static {
        // <clinit> runs during instantiateApplication -- BEFORE the framework
        // calls attach, so the attach hook arms in time (same timing as the
        // production factory's static initializer).
        ProbeBoot.arm();
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        ProbeBoot.app = this;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i("FreeMOMO-Probe", "ProbeApp.onCreate reached");
    }

    static void onAttachFired(Context context) {
        ProbeBoot.attachFired = true;
        ProbeBoot.attachContextClass = context == null ? "null" : context.getClass().getName();
        ProbeBoot.attachClassLoader = context == null ? null : context.getClassLoader();
        Log.i("FreeMOMO-Probe", "PROOF attach hook fired: context=" + ProbeBoot.attachContextClass
                + " loader=" + ProbeBoot.attachClassLoader);
    }
}
