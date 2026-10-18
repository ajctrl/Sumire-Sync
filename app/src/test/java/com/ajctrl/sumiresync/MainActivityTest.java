package com.ajctrl.sumiresync;

import static org.junit.Assert.*;
import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.TextView;
import android.content.Context;
import android.app.job.JobScheduler;
import com.ajctrl.sumiresync.data.SyncStateStore;
import com.ajctrl.sumiresync.data.SyncStatus;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.Shadows;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {30, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class MainActivityTest {
    @Test public void corruptStateShowsAnErrorOnLaunchAndManualSyncWithoutDeletingIt() throws Exception {
        assertUnavailableStateIsDisplayed("header");
    }

    @Test public void missingStateWithArchivesShowsAnErrorWithoutCreatingADatabase() throws Exception {
        assertUnavailableStateIsDisplayed("missing");
    }

    @Test public void emptyStateWithArchivesShowsAnErrorWithoutInitializingIt() throws Exception {
        assertUnavailableStateIsDisplayed("empty");
    }

    private void assertUnavailableStateIsDisplayed(String damage) throws Exception {
        var context = org.robolectric.RuntimeEnvironment.getApplication();
        try (var states = new com.ajctrl.sumiresync.data.SyncStateStore(context)) {
            states.requestSync();
            var source = new com.ajctrl.sumiresync.data.SyncStatus(1, "source", 1, 1);
            new com.ajctrl.sumiresync.data.ArchiveStore(context, states).save(source,
                    new com.ajctrl.sumiresync.data.ClipboardItem(1, "TEXT", 1790899200000L, false, "saved", android.net.Uri.EMPTY),
                    new java.io.ByteArrayInputStream(new byte[]{42}), null, null, false, () -> {});
        }
        var state = com.ajctrl.sumiresync.data.StoragePaths.database(context, "sync-state.sqlite");
        if (damage.equals("missing")) {
            assertTrue(state.delete());
        } else try (var file = new java.io.RandomAccessFile(state, "rw")) {
            if (damage.equals("empty")) file.setLength(0);
            else file.write(new byte[32]);
        }
        byte[] damaged = state.exists() ? java.nio.file.Files.readAllBytes(state.toPath()) : null;
        String expected = damage.equals("header") ? "同期管理DBの破損" : "同期管理情報がありません";
        try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            awaitStatus(activity);
            android.widget.TextView error = org.robolectric.util.ReflectionHelpers.getField(activity, "statusError");
            assertEquals(View.VISIBLE, error.getVisibility());
            assertTrue(error.getText().toString().contains(expected));
            java.util.ArrayList<View> actions = new java.util.ArrayList<>();
            activity.getWindow().getDecorView().findViewsWithText(actions, "今すぐ同期", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
            assertEquals(1, actions.size());
            actions.get(0).performClick();
            awaitStatus(activity);
            assertTrue(error.getText().toString().contains(expected));
            android.widget.TextView completed = org.robolectric.util.ReflectionHelpers.getField(activity, "syncCompletedValue");
            assertEquals("読み取り不可", completed.getText().toString());
            assertNotEquals("同期を開始しました", org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
        }
        if (damaged == null) assertFalse(state.exists());
        else assertArrayEquals(damaged, java.nio.file.Files.readAllBytes(state.toPath()));
    }

    @Test public void recreationPreservesUnsavedConnectionFields() throws Exception {
        try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            awaitStatus(activity);
            ((EditText) activity.findViewById(R.id.server_url)).setText("https://draft.example.com");
            ((EditText) activity.findViewById(R.id.server_user)).setText("draft-user");
            ((EditText) activity.findViewById(R.id.server_password)).setText("draft-password");
            controller.recreate();
            activity = controller.get();
            awaitStatus(activity);
            assertEquals("https://draft.example.com", ((EditText) activity.findViewById(R.id.server_url)).getText().toString());
            assertEquals("draft-user", ((EditText) activity.findViewById(R.id.server_user)).getText().toString());
            assertEquals("draft-password", ((EditText) activity.findViewById(R.id.server_password)).getText().toString());
        }
    }

    @Test public void systemBarsCutoutAndKeyboardInsetsAreAppliedWithoutAccumulating() throws Exception {
        try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            awaitStatus(controller.get());
            View scroll = controller.get().findViewById(R.id.main_scroll);
            WindowInsets bars = new WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 56, 0, 48))
                    .setInsets(WindowInsets.Type.displayCutout(), Insets.of(72, 0, 0, 0)).build();
            scroll.dispatchApplyWindowInsets(bars);
            scroll.dispatchApplyWindowInsets(bars);
            assertEquals(72, scroll.getPaddingLeft());
            assertEquals(56, scroll.getPaddingTop());
            assertEquals(48, scroll.getPaddingBottom());
            WindowInsets keyboard = new WindowInsets.Builder(bars)
                    .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 300)).build();
            scroll.dispatchApplyWindowInsets(keyboard);
            assertEquals(300, scroll.getPaddingBottom());
            scroll.dispatchApplyWindowInsets(bars);
            assertEquals(48, scroll.getPaddingBottom());
        }
    }

    @Test public void emptySourceRequiresWebDavSettingsAndCompletionBeforeShowingSuccess() throws Exception {
        try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            awaitStatus(activity);
            try (SyncStateStore states = new SyncStateStore(activity)) {
                states.initializeSource(new SyncStatus(1, "source", 1, 0));
                states.markRequestHandled(states.requestRevision());
            }
            refreshStatus(activity);
            TextView status = ReflectionHelpers.getField(activity, "status");
            assertEquals("Nextcloud未設定", status.getText().toString());
            activity.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                    .putString("webdav_url", "https://example.test").putString("webdav_user", "alice").commit();
            refreshStatus(activity);
            assertEquals("同期完了を確認中…", status.getText().toString());
            try (SyncStateStore states = new SyncStateStore(activity)) { states.markSyncCompleted(0); }
            refreshStatus(activity);
            assertEquals("同期完了", status.getText().toString());
            activity.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().remove("webdav_url").commit();
            refreshStatus(activity);
            assertEquals("Nextcloud未設定", status.getText().toString());
        }
    }

    @Test public void schedulingFailureDoesNotShowASuccessToast() throws Exception {
        try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            awaitStatus(activity);
            JobScheduler scheduler = activity.getSystemService(JobScheduler.class);
            scheduler.cancelAll();
            Shadows.shadowOf(scheduler).failOnJob(7101);
            Shadows.shadowOf(scheduler).failOnJob(7104);
            java.util.ArrayList<View> actions = new java.util.ArrayList<>();
            activity.getWindow().getDecorView().findViewsWithText(actions, "今すぐ同期", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
            assertEquals(1, actions.size());
            actions.get(0).performClick();
            awaitStatus(activity);
            assertEquals("同期を予約できませんでした", org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
            TextView error = ReflectionHelpers.getField(activity, "statusError");
            assertTrue(error.getText().toString().contains("could not be scheduled"));
        }
    }

    @Test public void blockedStatusReadLeavesUiResponsiveAndDoesNotUpdatePausedActivity() throws Exception {
        ExecutorService writer = Executors.newSingleThreadExecutor();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            awaitStatus(activity);
            Context app = activity.getApplicationContext();
            var writing = writer.submit(() -> {
                try (SyncStateStore states = new SyncStateStore(app)) {
                    var db = states.getWritableDatabase();
                    db.beginTransaction();
                    try {
                        db.execSQL("UPDATE sync_state SET last_error='test error'");
                        held.countDown();
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                        db.setTransactionSuccessful();
                    } finally { db.endTransaction(); }
                }
                return null;
            });
            try {
                assertTrue(held.await(10, TimeUnit.SECONDS));
                for (int i = 0; i < 10; i++) ReflectionHelpers.callInstanceMethod(activity, "refreshStatus");
                assertFalse("UI callbacks must return while the DB writer is still active", writing.isDone());
                TextView status = ReflectionHelpers.getField(activity, "status");
                status.setText("画面の操作を継続");
                controller.pause();
                release.countDown();
                writing.get(10, TimeUnit.SECONDS);
                awaitStatus(activity);
                assertEquals("画面の操作を継続", status.getText().toString());
                controller.resume();
                awaitStatus(activity);
                assertEquals("同期に失敗しました", status.getText().toString());
            } finally { release.countDown(); }
        } finally { writer.shutdownNow(); }
    }

    @Test public void closingActivityDoesNotDropAnAlreadyRequestedManualSync() throws Exception {
        var controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        awaitStatus(activity);
        ExecutorService worker = ReflectionHelpers.getField(activity, "stateExecutor");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        long before;
        try (SyncStateStore states = new SyncStateStore(activity)) { before = states.requestRevision(); }
        var blocker = worker.submit(() -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return null;
        });
        boolean destroyed = false;
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            ReflectionHelpers.callInstanceMethod(activity, "requestSync",
                    ReflectionHelpers.ClassParameter.from(boolean.class, false));
            controller.pause().stop().destroy();
            destroyed = true;
            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
            try (SyncStateStore states = new SyncStateStore(activity.getApplicationContext())) {
                assertEquals(before + 1, states.requestRevision());
                assertTrue(states.hasPendingRequest());
            }
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        } finally {
            release.countDown();
            if (!destroyed) controller.close();
        }
    }

    private static void refreshStatus(MainActivity activity) throws Exception {
        ReflectionHelpers.callInstanceMethod(activity, "refreshStatus");
        awaitStatus(activity);
    }

    private static void awaitStatus(MainActivity activity) throws Exception {
        ExecutorService worker = ReflectionHelpers.getField(activity, "stateExecutor");
        // A completed reservation posts a UI callback which requests a fresh status read.
        for (int i = 0; i < 2; i++) {
            worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        }
    }
}
