package com.ajctrl.sumiresync.sync;

import static org.junit.Assert.*;
import android.app.job.JobParameters;
import com.ajctrl.sumiresync.TestJobParameters;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30)
public class JobRunsTest {
    @Test public void lateOldCompletionDoesNotRemoveOrFinishReplacement() {
        JobRuns jobs = new JobRuns();
        JobParameters params = TestJobParameters.create(7102);
        JobRuns.Run old = jobs.start(params);
        AtomicInteger cancelled = new AtomicInteger();
        jobs.onCancel(old, cancelled::incrementAndGet);
        jobs.stop(TestJobParameters.copy(params));
        assertEquals(1, cancelled.get());
        JobRuns.Run replacement = jobs.start(TestJobParameters.copy(params));
        jobs.finish(old, () -> fail("stopped job must not call jobFinished"));
        assertTrue(jobs.ifActive(replacement, () -> {}));
        jobs.stop(TestJobParameters.copy(params));
        assertTrue(replacement.isStopped());
        assertFalse(jobs.ifActive(replacement, () -> fail("stopped job acknowledged")));
    }

    @Test public void stopOneSlotPreservesTheOtherAndCancelsLateRegisteredCalls() {
        JobRuns jobs = new JobRuns();
        JobParameters first = TestJobParameters.create(7102);
        JobRuns.Run a = jobs.start(first);
        JobRuns.Run b = jobs.start(TestJobParameters.create(7105));
        jobs.stop(TestJobParameters.copy(first));
        AtomicInteger cancelled = new AtomicInteger();
        jobs.onCancel(a, cancelled::incrementAndGet);
        assertEquals(1, cancelled.get());
        assertFalse(b.isStopped());
        jobs.destroy();
        assertTrue(b.isStopped());
    }
}
