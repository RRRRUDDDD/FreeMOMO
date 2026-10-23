package com.freemomo.probe;

import android.app.Application;
import android.app.AppComponentFactory;
import android.content.Intent;
import android.util.Log;

/**
 * Replicates the production factory timing: arm the engine (and the
 * Application.attach hook) from the factory static initializer, i.e. inside
 * createAppFactory, so the probe exercises the same bootstrap window --
 * including the release (non-debuggable) runtime mode.
 */
public class ProbeFactory extends AppComponentFactory {

    static {
        ProbeBoot.arm();
        Log.i("FreeMOMO-Probe", "ProbeFactory <clinit> armed");
    }

    @Override
    public Application instantiateApplication(ClassLoader cl, String className) {
        try {
            return (Application) cl.loadClass(className).getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @Override
    public android.app.Activity instantiateActivity(ClassLoader cl, String className, Intent intent) {
        try {
            return (android.app.Activity) cl.loadClass(className).getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
