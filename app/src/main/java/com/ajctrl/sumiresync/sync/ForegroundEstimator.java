package com.ajctrl.sumiresync.sync;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ForegroundEstimator {
    private static final long LOOKBACK_MILLIS = 24L * 60L * 60L * 1000L;
    private final Context context;

    public ForegroundEstimator(Context context) { this.context = context.getApplicationContext(); }

    public Map<Long, Candidate> estimate(List<ClipboardItemTime> items) {
        Map<Long, Candidate> result = new HashMap<>();
        if (items.isEmpty()) return result;
        try {
            long earliest = items.stream().mapToLong(value -> value.createdAt).min().orElse(0);
            long latest = items.stream().mapToLong(value -> value.createdAt).max().orElse(0);
            UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            UsageEvents events = manager.queryEvents(Math.max(0, earliest - LOOKBACK_MILLIS), latest + 1);
            List<Event> resumes = new ArrayList<>();
            UsageEvents.Event event = new UsageEvents.Event();
            int wantedType = Build.VERSION.SDK_INT >= 29
                    ? UsageEvents.Event.ACTIVITY_RESUMED : UsageEvents.Event.MOVE_TO_FOREGROUND;
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (event.getEventType() == wantedType && event.getPackageName() != null) {
                    resumes.add(new Event(event.getTimeStamp(), event.getPackageName()));
                }
            }
            resumes.sort(Comparator.comparingLong(value -> value.at));
            for (ClipboardItemTime item : items) {
                Event best = null;
                for (Event resume : resumes) {
                    if (resume.at > item.createdAt) break;
                    best = resume;
                }
                if (best != null) result.put(item.id, new Candidate(best.packageName, appName(best.packageName)));
            }
        } catch (RuntimeException ignored) {
            // This is optional metadata. Usage permission and OEM behavior must never stop syncing.
        }
        return result;
    }

    private String appName(String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(info);
            return label == null ? null : label.toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return null;
        }
    }

    public static final class ClipboardItemTime {
        public final long id;
        public final long createdAt;
        public ClipboardItemTime(long id, long createdAt) { this.id = id; this.createdAt = createdAt; }
    }

    public static final class Candidate {
        public final String packageName;
        public final String appName;
        Candidate(String packageName, String appName) {
            this.packageName = packageName;
            this.appName = appName;
        }
    }

    private static final class Event {
        final long at;
        final String packageName;
        Event(long at, String packageName) { this.at = at; this.packageName = packageName; }
    }
}
