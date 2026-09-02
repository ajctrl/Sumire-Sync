package com.ajctrl.sumiresync.sync;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import com.ajctrl.sumiresync.data.ArchiveStore;
import com.ajctrl.sumiresync.data.ClipboardItem;
import com.ajctrl.sumiresync.data.SyncStateStore;
import com.ajctrl.sumiresync.data.SyncStatus;
import com.ajctrl.sumiresync.settings.AppSettings;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SyncEngine implements AutoCloseable {
    private static final Object RUN_LOCK = new Object();
    private final Context context;
    private final ContentResolver resolver;
    private final SyncStateStore states;
    private final ArchiveStore archives;
    private final AppSettings settings;

    public SyncEngine(Context context) {
        this.context = context.getApplicationContext();
        resolver = this.context.getContentResolver();
        states = new SyncStateStore(this.context);
        archives = new ArchiveStore(this.context, states);
        settings = new AppSettings(this.context);
    }

    public Result runBatch(int requestedLimit) {
        synchronized (RUN_LOCK) {
            try {
                ProviderConnection provider = resolveProvider();
                String authority = provider.authority;
                SyncStatus remote = provider.status;
                SyncStateStore.State local = reconcile(remote);
                long querySnapshotSequence = remote.currentSequence;
                List<ClipboardItem> items = readItems(authority, local.lastId, requestedLimit);
                SyncStatus confirmed = readStatus(authority);
                if (!remote.databaseInstanceId.equals(confirmed.databaseInstanceId)
                        || remote.clipboardGeneration != confirmed.clipboardGeneration
                        || remote.apiVersion != confirmed.apiVersion) {
                    throw new IllegalStateException("Sumire data source changed during sync; retrying safely");
                }
                if (confirmed.currentSequence < local.lastId) {
                    throw new IllegalStateException("Sumire sequence moved backwards during sync");
                }
                remote = confirmed;
                Map<Long, ForegroundEstimator.Candidate> candidates = inferForeground(items);
                long expected = local.lastId + 1;
                int saved = 0;
                int gaps = 0;
                for (ClipboardItem item : items) {
                    if (expected < item.id) {
                        long end = item.id - 1;
                        states.recordGapRange(remote.databaseInstanceId, remote.clipboardGeneration,
                                expected, end, "not returned by provider (deleted before sync)");
                        states.advance(end);
                        gaps = addGapCount(gaps, end - expected + 1);
                    }
                    SyncItemType itemType = SyncItemType.fromContractValue(item.itemType);
                    if (itemType == SyncItemType.IMAGE) {
                        states.recordGap(remote.databaseInstanceId, remote.clipboardGeneration,
                                item.id, "image sync is not supported in API version 1");
                        states.advance(item.id);
                        expected = item.id + 1;
                        gaps = addGapCount(gaps, 1);
                        continue;
                    }
                    try (InputStream content = openContent(item.contentUri, authority)) {
                        ForegroundEstimator.Candidate candidate = candidates.get(item.id);
                        archives.save(remote, item, content,
                                candidate == null ? null : candidate.packageName,
                                candidate == null ? null : candidate.appName);
                    } catch (FileNotFoundException missing) {
                        states.recordGap(remote.databaseInstanceId, remote.clipboardGeneration,
                                item.id, "content no longer exists");
                        states.advance(item.id);
                        expected = item.id + 1;
                        gaps = addGapCount(gaps, 1);
                        continue;
                    }
                    states.advance(item.id);
                    expected = item.id + 1;
                    saved++;
                }
                int boundedLimit = Math.min(500, Math.max(1, requestedLimit));
                if (items.size() < boundedLimit && expected <= querySnapshotSequence) {
                    // The ordered query reached its end; any trailing IDs already covered by the
                    // pre-query status sequence are absent. IDs inserted concurrently are left
                    // for the next run instead of being misclassified as deleted.
                    states.recordGapRange(remote.databaseInstanceId, remote.clipboardGeneration,
                            expected, querySnapshotSequence,
                            "not returned by provider (deleted before sync)");
                    states.advance(querySnapshotSequence);
                    gaps = addGapCount(gaps, querySnapshotSequence - expected + 1);
                }
                return new Result(saved, gaps, items.size() == boundedLimit);
            } catch (Exception error) {
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                states.setError(message);
                return new Result(message);
            }
        }
    }

    @Override public void close() {
        states.close();
    }

    private SyncStateStore.State reconcile(SyncStatus remote) {
        SyncStateStore.State local = states.read();
        SourceStatePolicy.Decision decision = SourceStatePolicy.evaluate(
                SumireContract.SUPPORTED_API_VERSION, remote.apiVersion,
                remote.databaseInstanceId, remote.clipboardGeneration, remote.currentSequence,
                local.instanceId, local.generation, local.lastId);
        if (decision == SourceStatePolicy.Decision.RESET) {
            states.initializeSource(remote);
            return states.read();
        }
        return local;
    }

    private ProviderConnection resolveProvider() {
        Exception lastError = null;
        StringBuilder failures = new StringBuilder();
        for (String authority : SumireContract.authorityCandidates(
                settings.connectedProviderAuthority())) {
            try {
                SyncStatus status = readStatus(authority);
                settings.saveConnectedProviderAuthority(authority);
                return new ProviderConnection(authority, status);
            } catch (Exception error) {
                lastError = error;
                if (failures.length() > 0) failures.append("; ");
                failures.append(authority).append(" -> ").append(errorDetail(error));
            }
        }
        String detail = lastError == null ? "no authority candidates" : failures.toString();
        throw new IllegalStateException("Sumire Provider is unavailable; tried: " + detail, lastError);
    }

    private SyncStatus readStatus(String authority) {
        try (Cursor cursor = resolver.query(SumireContract.statusUri(authority), null, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new IllegalStateException("Sumire status is unavailable");
            return new SyncStatus(cursor.getInt(required(cursor, "apiVersion")),
                    cursor.getString(required(cursor, "databaseInstanceId")),
                    cursor.getInt(required(cursor, "clipboardGeneration")),
                    cursor.getLong(required(cursor, "currentSequence")));
        }
    }

    private List<ClipboardItem> readItems(String authority, long afterId, int limit) {
        List<ClipboardItem> result = new ArrayList<>();
        try (Cursor cursor = resolver.query(SumireContract.itemsAfter(authority, afterId, limit),
                null, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("Sumire items query returned no cursor");
            int id = required(cursor, "id");
            int type = required(cursor, "itemType");
            int created = required(cursor, "createdAt");
            int pinned = required(cursor, "isPinned");
            int preview = required(cursor, "preview");
            int contentUri = required(cursor, "contentUri");
            long previous = afterId;
            while (cursor.moveToNext()) {
                long sourceId = cursor.getLong(id);
                if (sourceId <= previous) throw new IllegalStateException("Provider items are not strictly ordered by id");
                long createdAt = cursor.getLong(created);
                if (createdAt < 0) throw new IllegalStateException("Provider returned an invalid createdAt");
                String uri = cursor.getString(contentUri);
                if (uri == null) throw new IllegalStateException("Provider returned an empty contentUri");
                Uri parsed = Uri.parse(uri);
                if (!"content".equals(parsed.getScheme()) || !authority.equals(parsed.getAuthority())) {
                    throw new SecurityException("Provider returned an unexpected contentUri");
                }
                result.add(new ClipboardItem(sourceId, cursor.getString(type), createdAt,
                        cursor.getInt(pinned) != 0, cursor.isNull(preview) ? null : cursor.getString(preview), parsed));
                previous = sourceId;
            }
        }
        return result;
    }

    private Map<Long, ForegroundEstimator.Candidate> inferForeground(List<ClipboardItem> items) {
        if (!new AppSettings(context).foregroundInferenceEnabled()) return new HashMap<>();
        List<ForegroundEstimator.ClipboardItemTime> times = new ArrayList<>();
        for (ClipboardItem item : items) times.add(new ForegroundEstimator.ClipboardItemTime(item.id, item.createdAt));
        return new ForegroundEstimator(context).estimate(times);
    }

    private InputStream openContent(Uri uri, String authority) throws IOException {
        if (!"content".equals(uri.getScheme()) || !authority.equals(uri.getAuthority())) {
            throw new SecurityException("Provider returned an unexpected contentUri");
        }
        InputStream input = resolver.openInputStream(uri);
        if (input == null) throw new FileNotFoundException(uri.toString());
        return input;
    }

    private static String errorDetail(Exception error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }

    private static int required(Cursor cursor, String name) {
        int index = cursor.getColumnIndex(name);
        if (index < 0) throw new IllegalStateException("Provider response is missing column: " + name);
        return index;
    }

    private static int addGapCount(int current, long added) {
        return (int) Math.min(Integer.MAX_VALUE, (long) current + added);
    }

    private static final class ProviderConnection {
        final String authority;
        final SyncStatus status;

        ProviderConnection(String authority, SyncStatus status) {
            this.authority = authority;
            this.status = status;
        }
    }

    public static final class Result {
        public final boolean success;
        public final int saved;
        public final int gaps;
        public final boolean mayHaveMore;
        public final String error;

        Result(int saved, int gaps, boolean mayHaveMore) {
            this.success = true; this.saved = saved; this.gaps = gaps;
            this.mayHaveMore = mayHaveMore; this.error = null;
        }
        Result(String error) {
            this.success = false; this.saved = 0; this.gaps = 0;
            this.mayHaveMore = false; this.error = error;
        }
    }
}
