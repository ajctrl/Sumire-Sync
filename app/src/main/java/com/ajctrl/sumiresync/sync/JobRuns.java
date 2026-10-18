package com.ajctrl.sumiresync.sync;

import android.app.job.JobParameters;
import java.util.HashMap;
import java.util.Map;

/** Binder recreates JobParameters for stop callbacks; only jobId identifies the slot. */
public final class JobRuns {
    private final Map<Integer, Run> active = new HashMap<>();
    private boolean destroyed;

    public synchronized Run start(JobParameters params) {
        if (destroyed) throw new IllegalStateException("Service is destroyed");
        Run previous = active.get(params.getJobId());
        if (previous != null) stop(previous);
        Run run = new Run(params);
        active.put(params.getJobId(), run);
        return run;
    }

    public synchronized void stop(JobParameters params) {
        Run run = active.get(params.getJobId());
        if (run != null) stop(run);
    }

    private void stop(Run run) {
        run.stopped = true;
        if (run.cancel != null) run.cancel.run();
    }

    public synchronized void onCancel(Run run, Runnable cancel) {
        run.cancel = cancel;
        if (run.isStopped() && cancel != null) cancel.run();
    }

    /** Makes acknowledgement atomic with respect to stop and replacement callbacks. */
    public synchronized boolean ifActive(Run run, Runnable action) {
        if (run.isStopped() || active.get(run.params.getJobId()) != run) return false;
        action.run();
        return true;
    }

    public synchronized void finish(Run run, Runnable notifyFinished) {
        try { ifActive(run, notifyFinished); }
        finally {
            // A late old worker must never remove the replacement's cancellation state.
            active.remove(run.params.getJobId(), run);
            run.cancel = null;
        }
    }

    public synchronized void destroy() {
        destroyed = true;
        for (Run run : active.values()) stop(run);
        active.clear();
    }

    public static final class Run {
        public final JobParameters params;
        private volatile boolean stopped;
        private Runnable cancel;

        private Run(JobParameters params) { this.params = params; }
        public boolean isStopped() { return stopped; }
    }
}
