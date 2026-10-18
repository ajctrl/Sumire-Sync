package com.ajctrl.sumiresync.sync;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.CancellationSignal;
import android.util.Log;

import com.ajctrl.sumiresync.data.ArchiveStore;
import com.ajctrl.sumiresync.data.ClipboardItem;
import com.ajctrl.sumiresync.data.SyncStateStore;
import com.ajctrl.sumiresync.data.SyncStatus;
import com.ajctrl.sumiresync.settings.AppSettings;

import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

public final class SyncEngine implements AutoCloseable {
    private static final Object RUN_LOCK = new Object();
    private final Context context;
    private final ContentResolver resolver;
    private final SyncStateStore states;
    private final ArchiveStore archives;
    private final AppSettings settings;
    private final CancellationSignal cancellation = new CancellationSignal();
    private final Object contentLock = new Object();
    private ActiveContent activeContent;

    public SyncEngine(Context context) {
        this.context = context.getApplicationContext();
        resolver = this.context.getContentResolver();
        states = new SyncStateStore(this.context);
        archives = new ArchiveStore(this.context, states);
        settings = new AppSettings(this.context);
    }

    public Result runBatch(int requestedLimit) {
        return runBatch(requestedLimit, () -> false);
    }

    public Result runBatch(int requestedLimit, BooleanSupplier stopped) {
        synchronized (RUN_LOCK) {
            try {
                checkStopped(stopped);
                archives.recoverPendingWrites();
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
                Map<Long, ForegroundEstimator.Candidate> candidates = inferForeground(items);
                long expected = local.lastId + 1;
                int saved = 0;
                int gaps = 0;
                for (ClipboardItem item : items) {
                    checkStopped(stopped);
                    if (expected < item.id) {
                        long end = item.id - 1;
                        states.recordGapRangeAndAdvance(remote.databaseInstanceId, remote.clipboardGeneration,
                                expected, end, "not returned by provider");
                        gaps = addGapCount(gaps, end - expected + 1);
                    }
                    SyncItemType itemType = SyncItemType.fromContractValue(item.itemType);
                    if (itemType == SyncItemType.IMAGE) {
                        states.recordGapRangeAndAdvance(remote.databaseInstanceId, remote.clipboardGeneration,
                                item.id, item.id, "image sync is not supported in API version 1");
                        expected = item.id + 1;
                        gaps = addGapCount(gaps, 1);
                        continue;
                    }
                    try (InputStream content = openContent(item.contentUri, authority)) {
                        ForegroundEstimator.Candidate candidate = candidates.get(item.id);
                        archives.save(remote, item, content,
                                candidate == null ? null : candidate.packageName,
                                candidate == null ? null : candidate.appName,
                                settings.flaggingEnabled(), () -> {
                                    if (isStopped(stopped)) throw new IllegalStateException("Sync cancelled");
                                    validateSource(authority, remote);
                                }, () -> isStopped(stopped));
                    } catch (FileNotFoundException missing) {
                        checkStopped(stopped);
                        validateSource(authority, remote);
                        states.recordGapRangeAndAdvance(remote.databaseInstanceId, remote.clipboardGeneration,
                                item.id, item.id, "content no longer exists");
                        expected = item.id + 1;
                        gaps = addGapCount(gaps, 1);
                        continue;
                    }
                    states.advance(item.id);
                    expected = item.id + 1;
                    saved++;
                }
                int boundedLimit = Math.min(500, Math.max(1, requestedLimit));
                checkStopped(stopped);
                if (items.size() < boundedLimit && expected <= querySnapshotSequence) {
                    // The ordered query reached its end; any trailing IDs already covered by the
                    // pre-query status sequence are absent. IDs inserted concurrently are left
                    // for the next run instead of being misclassified as deleted.
                    states.recordGapRangeAndAdvance(remote.databaseInstanceId, remote.clipboardGeneration,
                            expected, querySnapshotSequence,
                            "not returned by provider");
                    gaps = addGapCount(gaps, querySnapshotSequence - expected + 1);
                }
                states.setLocalError(null);
                return new Result(saved, gaps, items.size() == boundedLimit);
            } catch (Exception error) {
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                if (!isStopped(stopped)) {
                    try { states.setLocalError(message); }
                    catch (SQLiteException reportingError) {
                        // The original failure may be the state DB itself. Never reset it
                        // merely to record an error; the UI reports read failures directly.
                        Log.e("SumireSync", "Cannot record sync error", reportingError);
                    }
                }
                return new Result(message);
            }
        }
    }

    private boolean isStopped(BooleanSupplier stopped) {
        return cancellation.isCanceled() || stopped.getAsBoolean() || Thread.currentThread().isInterrupted();
    }

    private void checkStopped(BooleanSupplier stopped) throws IOException {
        if (isStopped(stopped)) throw new IOException("Sync cancelled");
    }

    /** Called by the owning job's stop callback, independently of the worker's RUN_LOCK. */
    public void cancel() {
        // Cancel the Provider operation and prevent a concurrently opened stream from escaping.
        try { cancellation.cancel(); }
        finally {
            ActiveContent content;
            synchronized (contentLock) { content = activeContent; }
            if (content != null) {
                try { content.close(); } catch (IOException ignored) { }
            }
        }
    }

    @Override public void close() {
        cancel();
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
            cancellation.throwIfCanceled();
            try {
                verifyProvider(authority);
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

    private void verifyProvider(String authority) {
        PackageManager packages = context.getPackageManager();
        ProviderInfo provider = packages.resolveContentProvider(authority, 0);
        String expectedPackage = SumireContract.packageIdForAuthority(authority);
        if (!SumireContract.packageIdCandidates().contains(expectedPackage)
                || provider == null || !expectedPackage.equals(provider.packageName)
                || packages.checkSignatures(context.getPackageName(), provider.packageName)
                != PackageManager.SIGNATURE_MATCH) {
            throw new SecurityException("Untrusted Sumire Provider: " + authority);
        }
    }

    private SyncStatus readStatus(String authority) {
        try (Cursor cursor = resolver.query(SumireContract.statusUri(authority), null, null, null, null,
                cancellation)) {
            if (cursor == null || !cursor.moveToFirst()) throw new IllegalStateException("Sumire status is unavailable");
            return new SyncStatus(cursor.getInt(required(cursor, "apiVersion")),
                    cursor.getString(required(cursor, "databaseInstanceId")),
                    cursor.getInt(required(cursor, "clipboardGeneration")),
                    cursor.getLong(required(cursor, "currentSequence")));
        }
    }

    private void validateSource(String authority, SyncStatus expected) {
        SyncStatus actual = readStatus(authority);
        if (!expected.databaseInstanceId.equals(actual.databaseInstanceId)
                || expected.clipboardGeneration != actual.clipboardGeneration
                || expected.apiVersion != actual.apiVersion
                || actual.currentSequence < expected.currentSequence) {
            throw new IllegalStateException("Sumire data source changed during content read; retrying safely");
        }
    }

    private List<ClipboardItem> readItems(String authority, long afterId, int limit) {
        List<ClipboardItem> result = new ArrayList<>();
        try (Cursor cursor = resolver.query(SumireContract.itemsAfter(authority, afterId, limit),
                null, null, null, null, cancellation)) {
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
                ArchiveStore.validateCreatedAt(createdAt);
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
        AssetFileDescriptor descriptor = resolver.openAssetFileDescriptor(uri, "r", cancellation);
        // null can mean the Provider just crashed, not that this item was deleted.
        if (descriptor == null) throw new IOException("Sumire content temporarily unavailable: " + uri);
        ActiveContent input;
        try {
            input = new ActiveContent(descriptor.createInputStream());
        } catch (IOException | RuntimeException error) {
            try { descriptor.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
            throw error;
        }
        synchronized (contentLock) {
            if (!cancellation.isCanceled()) {
                activeContent = input;
                return input;
            }
        }
        input.close();
        throw new IOException("Sync cancelled");
    }

    private final class ActiveContent extends FilterInputStream {
        private boolean closed;

        ActiveContent(InputStream input) { super(input); }

        @Override public void close() throws IOException {
            synchronized (contentLock) {
                if (closed) return;
                closed = true;
                if (activeContent == this) activeContent = null;
            }
            super.close();
        }
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
