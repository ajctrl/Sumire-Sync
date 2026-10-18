package com.ajctrl.sumiresync.settings;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30, shadows = AppSettingsTest.TestSecrets.class)
public class SettingsCommitConcurrencyTest {
    @Test public void workerReadsAndUnrelatedWritesWaitUntilFailedSaveIsRolledBack() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        AppSettings settings = new AppSettings(context);
        settings.save("https://old.example.test", "alice", false, "old-password");
        long generation = settings.uploadGeneration();
        SharedPreferences prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        CountDownLatch published = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean failNextCommit = new AtomicBoolean(true);
        // Model the interval after commitToMemory but before commit reports failure.
        // Real disk-write failure and reload are covered by AppSettingsTest.
        SharedPreferences controlled = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    Object result;
                    try { result = method.invoke(prefs, args); }
                    catch (InvocationTargetException error) { throw error.getCause(); }
                    if (!method.getName().equals("edit")) return result;
                    SharedPreferences.Editor editor = (SharedPreferences.Editor) result;
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class}, (editProxy, editMethod, editArgs) -> {
                                Object value;
                                try { value = editMethod.invoke(editor, editArgs); }
                                catch (InvocationTargetException error) { throw error.getCause(); }
                                if (editMethod.getName().equals("commit") && failNextCommit.getAndSet(false)) {
                                    published.countDown();
                                    assertTrue(release.await(10, TimeUnit.SECONDS));
                                    return false;
                                }
                                return value == editor ? editProxy : value;
                            });
                });
        Context wrapped = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return name.equals("settings") ? controlled : super.getSharedPreferences(name, mode);
            }
        };
        AppSettings saving = new AppSettings(wrapped);
        ExecutorService threads = Executors.newFixedThreadPool(3);
        try {
            Future<?> save = threads.submit(() -> assertThrows(IllegalStateException.class,
                    () -> saving.save("https://new.example.test", "bob", true, "new-password")));
            assertTrue(published.await(10, TimeUnit.SECONDS));
            assertEquals(generation + 1, prefs.getLong("upload_generation", 0));
            CountDownLatch reading = new CountDownLatch(1), writing = new CountDownLatch(1);
            Future<Long> read = threads.submit(() -> { reading.countDown(); return settings.uploadGeneration(); });
            Future<?> flag = threads.submit(() -> { writing.countDown(); settings.setFlaggingEnabled(true); });
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> read.get(100, TimeUnit.MILLISECONDS));
            assertThrows(TimeoutException.class, () -> flag.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            save.get(10, TimeUnit.SECONDS);
            assertEquals(Long.valueOf(generation), read.get(10, TimeUnit.SECONDS));
            flag.get(10, TimeUnit.SECONDS);
            assertEquals("https://old.example.test", settings.uploadSettings().url);
            assertEquals("old-password", settings.uploadSettings().password);
            assertTrue(settings.flaggingEnabled());
        } finally {
            release.countDown();
            threads.shutdownNow();
            assertTrue(threads.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
