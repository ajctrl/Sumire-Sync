package com.ajctrl.sumiresync.sync;

import android.net.Uri;

import com.ajctrl.sumiresync.BuildConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class SumireContract {
    public static final int SUPPORTED_API_VERSION = 1;
    public static final int CATCH_UP_BATCH_SIZE = 500;
    private static final String AUTHORITY_SUFFIX = ".syncbridge.clipboard";

    private SumireContract() {}

    public static List<String> packageIdCandidates() {
        return Arrays.asList(BuildConfig.SUMIRE_PACKAGE_IDS.split(","));
    }

    public static List<String> authorityCandidates(String savedAuthority) {
        Set<String> result = new LinkedHashSet<>();
        if (savedAuthority != null && !savedAuthority.trim().isEmpty()) {
            result.add(savedAuthority.trim());
        }
        for (String packageId : packageIdCandidates()) {
            result.add(packageId + AUTHORITY_SUFFIX);
        }
        return new ArrayList<>(result);
    }

    public static String packageIdForAuthority(String authority) {
        if (authority == null) return null;
        String normalized = authority.trim();
        if (normalized.endsWith(AUTHORITY_SUFFIX)) {
            return normalized.substring(0, normalized.length() - AUTHORITY_SUFFIX.length());
        }
        return normalized;
    }

    public static List<String> changedActionCandidates() {
        Set<String> result = new LinkedHashSet<>();
        for (String packageId : packageIdCandidates()) {
            result.add(packageId + ".action.CLIPBOARD_CHANGED");
        }
        return new ArrayList<>(result);
    }

    public static boolean isChangedAction(String action) {
        return action != null && changedActionCandidates().contains(action);
    }

    public static Uri statusUri(String authority) {
        return Uri.parse("content://" + authority + "/status");
    }

    public static Uri itemsAfter(String authority, long afterId, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        return Uri.parse("content://" + authority + "/items").buildUpon()
                .appendQueryParameter("afterId", Long.toString(Math.max(0, afterId)))
                .appendQueryParameter("limit", Integer.toString(boundedLimit))
                .build();
    }
}
