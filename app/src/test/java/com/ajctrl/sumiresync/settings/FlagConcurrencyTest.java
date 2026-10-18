package com.ajctrl.sumiresync.settings;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30)
public class FlagConcurrencyTest {
    @Test public void expiredWorkerReadPreservesNewlyEnabledFlag() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
        prefs.edit().clear().putLong("flag_expires_at", System.currentTimeMillis() - 60_000).commit();
        CountDownLatch oldExpiryRead = new CountDownLatch(1);
        CountDownLatch allowWorkerToFinishRead = new CountDownLatch(1);
        SharedPreferences pausedPrefs = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    Object result;
                    try { result = method.invoke(prefs, args); }
                    catch (InvocationTargetException error) { throw error.getCause(); }
                    if (method.getName().equals("getLong") && args[0].equals("flag_expires_at")
                            && Thread.currentThread().getName().equals("sync-worker")) {
                        oldExpiryRead.countDown();
                        if (!allowWorkerToFinishRead.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("UI did not enable flag");
                        }
                    }
                    return result;
                });
        Context wrapped = new ContextWrapper(app) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return name.equals("settings") ? pausedPrefs : super.getSharedPreferences(name, mode);
            }
        };
        AppSettings workerSettings = new AppSettings(wrapped);
        AppSettings uiSettings = new AppSettings(app);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { assertFalse(workerSettings.flaggingEnabled()); }
            catch (Throwable error) { failure.set(error); }
        }, "sync-worker");
        worker.start();
        try {
            assertTrue(oldExpiryRead.await(10, TimeUnit.SECONDS));
            uiSettings.setFlaggingEnabled(true);
            long enabledUntil = prefs.getLong("flag_expires_at", 0);
            assertTrue(enabledUntil > System.currentTimeMillis());
            allowWorkerToFinishRead.countDown();
            worker.join(10_000);
            assertFalse("worker completes", worker.isAlive());
            if (failure.get() != null) throw new AssertionError(failure.get());
            assertEquals(enabledUntil, prefs.getLong("flag_expires_at", 0));
            assertTrue("subsequent items retain the newly enabled flag", uiSettings.flaggingEnabled());
        } finally {
            allowWorkerToFinishRead.countDown();
            worker.join(10_000);
        }
    }

    @Test public void expiredFlagIsOffAndCanBeEnabledAndDisabledAgain() {
        Context app = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
        prefs.edit().putLong("flag_expires_at", 1).commit();
        AppSettings settings = new AppSettings(app);
        assertFalse(settings.flaggingEnabled());
        assertEquals(0, settings.flagExpiresAt());
        settings.setFlaggingEnabled(true);
        assertTrue(settings.flaggingEnabled());
        settings.setFlaggingEnabled(false);
        assertFalse(settings.flaggingEnabled());
    }
}
