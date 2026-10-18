package com.ajctrl.sumiresync.sync;

import static org.junit.Assert.*;

import com.ajctrl.sumiresync.data.SyncStateStore;
import com.ajctrl.sumiresync.data.SyncStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public final class CatchUpJobServiceTest {
    private SyncStateStore states;
    private CatchUpJobService service;

    @Before public void setUp() {
        RuntimeEnvironment.getApplication().deleteDatabase("sync-state.sqlite");
        states = new SyncStateStore(RuntimeEnvironment.getApplication());
        states.initializeSource(new SyncStatus(1, "source", 1, 10001));
        long previousRequest = states.requestSync();
        states.markRequestHandled(previousRequest);
        states.getWritableDatabase().execSQL("UPDATE sync_state SET last_sync_completed_at=1");
        service = new CatchUpJobService();
    }

    @After public void tearDown() { states.close(); }

    @Test public void periodicBatchLimitKeepsCompletionPendingUntilRetryFinishes() {
        int[] batches = {0};
        // No scheduler request: model a periodic job after the prior sync completed.
        assertFalse(states.hasPendingRequest());
        assertFalse(service.catchUp(null, states, () -> {
            assertTrue(states.hasPendingRequest());
            batches[0]++;
            states.advance((long) batches[0] * SumireContract.CATCH_UP_BATCH_SIZE);
            states.markDirty("clipboard-2026-10.sqlite");
            return new SyncEngine.Result(SumireContract.CATCH_UP_BATCH_SIZE, 0, true);
        }));
        assertEquals(20, batches[0]);
        assertEquals(10000, states.read().lastId);

        // Uploading the partial archive must not make the whole sync complete.
        SyncStateStore.DirtyArchive archive = states.dirtyArchives().get(0);
        states.markUploaded(archive.fileName, archive.revision, 0);
        states.markSyncCompleted(0);
        assertEquals(Long.valueOf(1), states.read().lastSyncCompletedAt);
        states.close();
        states = new SyncStateStore(RuntimeEnvironment.getApplication());
        assertTrue(states.hasPendingRequest());

        assertTrue(service.catchUp(null, states, () -> {
            states.advance(10001);
            states.markDirty(archive.fileName);
            return new SyncEngine.Result(1, 0, false);
        }));
        assertFalse(states.hasPendingRequest());
        states.markSyncCompleted(0);
        assertEquals(Long.valueOf(1), states.read().lastSyncCompletedAt);
        SyncStateStore.DirtyArchive updatedArchive = states.dirtyArchives().get(0);
        states.markUploaded(updatedArchive.fileName, updatedArchive.revision, 0);
        states.markSyncCompleted(0);
        assertTrue(states.read().lastSyncCompletedAt > 1);
    }

    @Test public void requestArrivingDuringCatchUpRemainsPending() {
        assertFalse(service.catchUp(null, states, () -> {
            states.requestSync();
            return new SyncEngine.Result(0, 0, false);
        }));
        assertTrue(states.hasPendingRequest());
        states.markSyncCompleted(0);
        assertEquals(Long.valueOf(1), states.read().lastSyncCompletedAt);
        assertTrue(service.catchUp(null, states, () -> new SyncEngine.Result(0, 0, false)));
        assertFalse(states.hasPendingRequest());
    }

    @Test public void failedOrStoppedPeriodicCatchUpDoesNotAcknowledgeRequest() {
        assertFalse(service.catchUp(null, states, () -> new SyncEngine.Result("failed")));
        assertTrue(states.hasPendingRequest());
        states.markSyncCompleted(0);
        assertEquals(Long.valueOf(1), states.read().lastSyncCompletedAt);

        assertFalse(service.catchUp(null, states, () -> {
            service.onStopJob(null);
            return new SyncEngine.Result(0, 0, false);
        }));
        assertTrue(states.hasPendingRequest());
        states.markSyncCompleted(0);
        assertEquals(Long.valueOf(1), states.read().lastSyncCompletedAt);
    }
}
