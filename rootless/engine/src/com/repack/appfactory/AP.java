package com.repack.appfactory;

import android.app.Activity;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.util.Log;

/**
 * Manifest-renamed replacement for the shell's own AppComponentFactory.
 *
 * <p>It keeps delegating every instantiation call to the original
 * {@code com.secneo.apkwrapper.AP}, so the shell's bootstrap stays intact, and
 * adds a static initializer that arms the hook engine. All payload (engine,
 * Xposed compatibility layer, baked bridges and the module dex) lives in the
 * split APK's normal code entries (classes.dex / classes2.dex); no dynamic
 * class loading and no extra packaged assets are involved.
 */
public class AP extends android.app.AppComponentFactory {

    static {
        try {
            com.repack.appfactory.RootlessBoot.boot();
        } catch (Throwable t) {
            Log.e("FreeMOMO-Rootless", "factory <clinit> bootstrap failed", t);
        }
    }

    private final com.secneo.apkwrapper.AP delegate = new com.secneo.apkwrapper.AP();

    @Override
    public Application instantiateApplication(ClassLoader cl, String className)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        return delegate.instantiateApplication(cl, className);
    }

    @Override
    public ClassLoader instantiateClassLoader(ClassLoader cl, ApplicationInfo info) {
        return delegate.instantiateClassLoader(cl, info);
    }

    @Override
    public Activity instantiateActivity(ClassLoader cl, String className, Intent intent)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        return delegate.instantiateActivity(cl, className, intent);
    }

    @Override
    public Service instantiateService(ClassLoader cl, String className, Intent intent)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        return delegate.instantiateService(cl, className, intent);
    }

    @Override
    public ContentProvider instantiateProvider(ClassLoader cl, String className)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        return delegate.instantiateProvider(cl, className);
    }

    @Override
    public BroadcastReceiver instantiateReceiver(ClassLoader cl, String className, Intent intent)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        return delegate.instantiateReceiver(cl, className, intent);
    }
}
