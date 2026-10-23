package com.repack.appfactory;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.Toast;

/**
 * Fail-closed entry gate: while the module has not confirmed that its
 * word-limit bridge is actually swapped in, keep the app on its launch
 * (splash) activity. Any started/resumed non-launch activity of the target
 * package is hidden and finished, so an unhooked main page can never stay on
 * screen, and the first read of the hooked word-limit getter happens after the
 * swap.
 *
 * Implementation notes:
 *   - the bounce throttle only spaces relaunches; every intruder is finished,
 *     so a fast splash-to-main transition cannot leave the unhooked main page
 *     in place;
 *   - the gate intercepts onActivityStarted (before the first frame) and hides
 *     the decor view, so the original value is not painted during the bounce;
 *   - the gate is installed by a poller started before the engine chain, so it
 *     lands at the first attached application even when engine init is slow;
 *   - a readiness poll releases the gate even when the splash sits idle, and a
 *     post-release check re-posts the launch activity as a fresh entry when
 *     the last bounce left the splash (or the launcher) on screen.
 *
 * Everything here is plain framework calls (lifecycle callbacks +
 * startActivity + Toast): no hook dispatch involved, so the gate still works
 * when the engine/bridges/module stages of boot() failed -- those stages leave
 * the bridge signal false forever, which is exactly the fail-closed state. The
 * module lifts the gate through XposedBridge.markBusinessReady, but the bridge
 * only opens after ArtHookEngine has swapped b_word's entrypoint.
 *
 * Known corner: a fresh install whose privacy consent is a standalone activity
 * (not a dialog over the launch activity) will bounce while the module is
 * down. That is the intended fail-closed semantics -- an app whose module
 * cannot start must not present its main experience -- and it self-heals once
 * the module arms.
 */
final class SplashGate {

    private static final String TARGET_PACKAGE = RootlessBoot.TARGET_PACKAGE;
    /** Minimum spacing between launch-intent re-posts. Finish is never throttled. */
    private static final long BOUNCE_BASE_INTERVAL_MS = 1_000;
    private static final long BOUNCE_INTERVAL_STEP_MS = 500;
    private static final long BOUNCE_INTERVAL_CAP_MS = 4_000;
    /** "still not ready" toast cadence while holding. */
    private static final long TOAST_REPEAT_MS = 15_000;
    /**
     * Healthy cold starts arm the word bridge in well under this; do not flash
     * a false "module not ready" toast while that handshake is still running.
     */
    private static final long TOAST_GRACE_MS = 1_500;
    /** Splash may finish itself right after starting main; re-check after teardown. */
    private static final long SPLASH_TEARDOWN_RECHECK_MS = 400;
    /** Only self-heal a splash that died close to one of our bounces. */
    private static final long HOLD_RELAUNCH_WINDOW_MS = 5_000;
    /** Readiness can flip while the splash sits idle; release without an event. */
    private static final long READY_POLL_MS = 500;
    /** Let the post-release lifecycle settle before deciding to re-enter. */
    private static final long POST_RELEASE_RECHECK_MS = 400;

    private static final String WAITING_MESSAGE = "FreeMOMO 模块未就绪，停留在启动页等待…";

    private final Application app;
    private final ComponentName launchComponent;
    private final String launchClass;
    private final Intent launchIntent;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Application.ActivityLifecycleCallbacks callbacks = new GateCallbacks();

    private long lastBounceAt;
    private int bounceCount;
    private long lastToastAt;
    private final long gateInstalledAt = SystemClock.uptimeMillis();
    private long lastRelaunchAt;
    private int launchActivitiesAlive;
    private int startedActivities;
    private android.app.Activity resumedActivity;
    private boolean holding;
    private boolean heldIntruder;
    private boolean relaunchScheduled;
    private boolean released;

    private SplashGate(Application app, ComponentName launchComponent,
                       String launchClass, Intent launchIntent) {
        this.app = app;
        this.launchComponent = launchComponent;
        this.launchClass = launchClass;
        this.launchIntent = launchIntent;
    }

    /**
     * Installs the gate on the given application. Returns silently without
     * registering anything when the launch activity cannot be resolved -- that
     * is fail-open for the gate itself and logged loudly.
     */
    static void install(Application app) {
        try {
            Intent launch = app.getPackageManager().getLaunchIntentForPackage(TARGET_PACKAGE);
            if (launch == null || launch.getComponent() == null) {
                android.util.Log.w(RootlessBoot.TAG,
                        "splash gate: launch activity unresolvable; gate disabled");
                return;
            }
            ComponentName component = launch.getComponent();
            SplashGate gate = new SplashGate(app, component, component.getClassName(), launch);
            app.registerActivityLifecycleCallbacks(gate.callbacks);
            gate.main.postDelayed(gate.readinessPoll, READY_POLL_MS);
            android.util.Log.i(RootlessBoot.TAG,
                    "splash gate armed on " + gate.launchClass
                            + " (holds entry until the word bridge is armed)");
        } catch (Throwable t) {
            android.util.Log.e(RootlessBoot.TAG, "splash gate install failed", t);
        }
    }

    private final class GateCallbacks implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            if (released) return;
            if (isLaunchActivity(activity)) launchActivitiesAlive++;
        }

        @Override
        public void onActivityStarted(Activity activity) {
            startedActivities++;
            if (readyOrRelease()) return;
            if (!isTarget(activity)) return;
            if (isLaunchActivity(activity)) {
                maybeToast();
                return;
            }
            hold(activity);
        }

        @Override
        public void onActivityResumed(Activity activity) {
            resumedActivity = activity;
            // Backstop for lifecycle orders that never deliver started() to us.
            if (readyOrRelease()) return;
            if (!isTarget(activity)) return;
            if (isLaunchActivity(activity)) {
                maybeToast();
                return;
            }
            hold(activity);
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            if (resumedActivity == activity) resumedActivity = null;
            if (isLaunchActivity(activity) && launchActivitiesAlive > 0) {
                launchActivitiesAlive--;
            }
            if (released || de.robv.android.xposed.XposedBridge.isBusinessReady()) return;
            if (!holding || launchActivitiesAlive > 0) return;
            // The shell often destroys its splash immediately after starting
            // main. If that happened right after our bounce, bring the splash
            // back instead of leaving the user on the launcher.
            if (SystemClock.uptimeMillis() - lastBounceAt <= HOLD_RELAUNCH_WINDOW_MS) {
                scheduleRelaunch();
            }
        }

        @Override
        public void onActivityPaused(Activity activity) {}

        @Override
        public void onActivityStopped(Activity activity) {
            if (startedActivities > 0) startedActivities--;
            if (resumedActivity == activity) resumedActivity = null;
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
    }

    private boolean isTarget(Activity activity) {
        return TARGET_PACKAGE.equals(activity.getPackageName());
    }

    /** Component compare covers both a direct activity and a launcher alias. */
    private boolean isLaunchActivity(Activity activity) {
        if (launchComponent.equals(activity.getComponentName())) return true;
        return launchClass.equals(activity.getClass().getName());
    }

    private boolean readyOrRelease() {
        if (!de.robv.android.xposed.XposedBridge.isBusinessReady()) return false;
        release();
        return true;
    }

    private void release() {
        if (released) return;
        released = true;
        main.removeCallbacks(relaunchRunnable);
        main.removeCallbacks(teardownCheck);
        main.removeCallbacks(readinessPoll);
        relaunchScheduled = false;
        try {
            app.unregisterActivityLifecycleCallbacks(callbacks);
        } catch (Throwable t) {
            android.util.Log.w(RootlessBoot.TAG, "splash gate unregister failed", t);
        }
        android.util.Log.i(RootlessBoot.TAG,
                "splash gate open: module ready (bounces=" + bounceCount + ")");
        if (heldIntruder) {
            // A held main may have taken the splash down with it. Let the
            // pending lifecycle settle, then re-enter only if the app is on
            // the splash, between activities, or still close to the bounce.
            main.postDelayed(postReleaseCheck, POST_RELEASE_RECHECK_MS);
        }
    }

    /**
     * Releases without a lifecycle event: readiness can arrive while the
     * splash sits idle, and otherwise the gate would stay registered and the
     * shell would never be nudged to continue.
     */
    private final Runnable readinessPoll = new Runnable() {
        @Override
        public void run() {
            if (released) return;
            if (!de.robv.android.xposed.XposedBridge.isBusinessReady()) {
                main.postDelayed(this, READY_POLL_MS);
                return;
            }
            release();
        }
    };

    private final Runnable postReleaseCheck = new Runnable() {
        @Override
        public void run() {
            Activity current = resumedActivity;
            boolean onLaunch = current != null && isLaunchActivity(current)
                    && !current.isFinishing();
            boolean betweenActivities = current == null || current.isFinishing();
            if (startedActivities > 0 && (onLaunch || betweenActivities)) {
                // The shell is looking at the splash (or a finished activity).
                // A fresh launch makes it run its entry flow again, now with
                // the word bridge armed.
                startLaunch(true);
                return;
            }
            if (startedActivities <= 0
                    && SystemClock.uptimeMillis() - lastBounceAt <= HOLD_RELAUNCH_WINDOW_MS) {
                // The bounce left the app on the launcher; bring it back while
                // the transition is still fresh.
                startLaunch(true);
            }
        }
    };

    /** Every non-launch intruder is hidden and finished; only re-posting is throttled. */
    private void hold(Activity activity) {
        if (activity.isFinishing()) return;
        holding = true;
        heldIntruder = true;
        lastBounceAt = SystemClock.uptimeMillis();
        bounceCount++;
        try {
            activity.getWindow().getDecorView().setVisibility(View.INVISIBLE);
        } catch (Throwable ignored) {
            // Window may not exist on an already-finishing activity.
        }
        maybeToast();
        android.util.Log.i(RootlessBoot.TAG,
                "splash gate: held " + activity.getClass().getName()
                        + " (word bridge not armed, hold #" + bounceCount + ")");
        activity.finish();
        if (launchActivitiesAlive > 0) {
            // Splash is still beneath this activity; finishing main reveals it.
            // Re-check after the shell's own splash teardown in case it dies now.
            main.removeCallbacks(teardownCheck);
            main.postDelayed(teardownCheck, SPLASH_TEARDOWN_RECHECK_MS);
        } else {
            scheduleRelaunch();
        }
    }

    private final Runnable teardownCheck = new Runnable() {
        @Override
        public void run() {
            if (released || de.robv.android.xposed.XposedBridge.isBusinessReady()) return;
            if (launchActivitiesAlive <= 0) scheduleRelaunch();
        }
    };

    private void scheduleRelaunch() {
        if (released || de.robv.android.xposed.XposedBridge.isBusinessReady()) return;
        long now = SystemClock.uptimeMillis();
        long interval = Math.min(
                BOUNCE_BASE_INTERVAL_MS + (long) bounceCount * BOUNCE_INTERVAL_STEP_MS,
                BOUNCE_INTERVAL_CAP_MS);
        long due = lastRelaunchAt == 0 ? now : lastRelaunchAt + interval;
        long delay = Math.max(0, due - now);
        if (delay == 0) {
            startLaunch();
            return;
        }
        if (relaunchScheduled) return;
        relaunchScheduled = true;
        main.postDelayed(relaunchRunnable, delay);
    }

    private final Runnable relaunchRunnable = new Runnable() {
        @Override
        public void run() {
            relaunchScheduled = false;
            if (released || de.robv.android.xposed.XposedBridge.isBusinessReady()) return;
            if (launchActivitiesAlive > 0) return; // already looking at the splash
            startLaunch();
        }
    };

    private void startLaunch() {
        startLaunch(false);
    }

    private void startLaunch(boolean freshEntry) {
        lastRelaunchAt = SystemClock.uptimeMillis();
        android.util.Log.i(RootlessBoot.TAG,
                "splash gate: re-posting launch activity (hold #" + bounceCount + ")"
                        + (freshEntry ? " as a fresh entry" : ""));
        try {
            Intent relaunch = new Intent(launchIntent);
            int flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED;
            if (!freshEntry) flags |= Intent.FLAG_ACTIVITY_SINGLE_TOP;
            relaunch.addFlags(flags);
            app.startActivity(relaunch);
        } catch (Throwable t) {
            android.util.Log.e(RootlessBoot.TAG, "splash gate relaunch failed", t);
        }
    }

    private void maybeToast() {
        long now = SystemClock.uptimeMillis();
        if (now - gateInstalledAt < TOAST_GRACE_MS) return;
        if (now - lastToastAt < TOAST_REPEAT_MS) return;
        lastToastAt = now;
        main.post(() -> {
            try {
                Toast.makeText(app, WAITING_MESSAGE, Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                android.util.Log.w(RootlessBoot.TAG, "splash gate toast failed", t);
            }
        });
    }
}
