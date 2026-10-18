package com.ajctrl.sumiresync.sync;

import static org.junit.Assert.*;
import static com.ajctrl.sumiresync.data.StoragePaths.database;
import android.content.*;
import android.content.pm.*;
import android.database.*;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import com.ajctrl.sumiresync.data.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30)
public class ProviderIdentityTest {
    private static final long CREATED = 1790899200000L;

    @Test public void differentSignerProviderIsRejectedInFavorOfLegitimateLite() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit();
        android.database.sqlite.SQLiteDatabase.deleteDatabase(database(context, "sync-state.sqlite"));
        PackageManager pm = context.getPackageManager();
        Signature legitimate = new Signature("11223344");
        PackageInfo own = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
        own.signatures = new Signature[]{legitimate};
        Shadows.shadowOf(pm).installPackage(own);
        MockProvider attacker = install(context, "com.example.sumire", new Signature("55667788"), "attacker-source");
        MockProvider trusted = install(context, "com.example.sumire.lite", legitimate, "trusted-source");
        assertEquals(PackageManager.SIGNATURE_NO_MATCH, pm.checkSignatures(context.getPackageName(), "com.example.sumire"));
        assertEquals(PackageManager.SIGNATURE_MATCH, pm.checkSignatures(context.getPackageName(), "com.example.sumire.lite"));
        assertNull(pm.resolveContentProvider(attacker.authority, 0).readPermission);
        try (SyncEngine engine = new SyncEngine(context)) {
            SyncEngine.Result result = engine.runBatch(500);
            assertTrue(result.error, result.success);
            assertEquals(1, result.saved);
        }
        assertEquals("untrusted provider must never be queried", 0, attacker.queries);
        assertTrue(trusted.queries > 0);
        String archiveName;
        try (SyncStateStore states = new SyncStateStore(context)) {
            assertEquals("trusted-source", states.read().instanceId);
            assertEquals(1, states.dirtyArchiveCount());
            archiveName = new ArchiveStore(context, states).fileNameFor(CREATED);
        }
        try (SQLiteDatabase archive = SQLiteDatabase.openDatabase(database(context, archiveName).getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            assertEquals("trusted-source", DatabaseUtils.stringForQuery(archive, "SELECT database_instance_id FROM clipboard_items", null));
            try (Cursor row = archive.rawQuery("SELECT content FROM clipboard_content_chunks", null)) {
                assertTrue(row.moveToFirst());
                assertEquals("forged clipboard: trusted-source", new String(row.getBlob(0), StandardCharsets.UTF_8));
            }
        }
    }

    private static MockProvider install(Context context, String packageName, Signature signature, String instance) throws Exception {
        PackageInfo pkg = new PackageInfo();
        pkg.packageName = packageName;
        pkg.signatures = new Signature[]{signature};
        pkg.applicationInfo = new ApplicationInfo();
        pkg.applicationInfo.packageName = packageName;
        pkg.applicationInfo.enabled = true;
        ProviderInfo info = new ProviderInfo();
        info.name = packageName + ".ClipboardProvider";
        info.packageName = packageName;
        info.applicationInfo = pkg.applicationInfo;
        info.authority = packageName + ".syncbridge.clipboard";
        info.exported = true;
        info.enabled = true;
        pkg.providers = new ProviderInfo[]{info};
        Shadows.shadowOf(context.getPackageManager()).installPackage(pkg);
        File content = new File(context.getCacheDir(), packageName + ".txt");
        Files.write(content.toPath(), ("forged clipboard: " + instance).getBytes(StandardCharsets.UTF_8));
        MockProvider provider = new MockProvider(info.authority, instance, content);
        provider.attachInfo(context, info);
        ShadowContentResolver.registerProviderInternal(info.authority, provider);
        return provider;
    }

    private static class MockProvider extends ContentProvider {
        final String authority;
        final String instance;
        final File content;
        int queries;
        MockProvider(String authority, String instance, File content) {
            this.authority = authority; this.instance = instance; this.content = content;
        }
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            queries++;
            if ("/status".equals(uri.getPath())) {
                MatrixCursor cursor = new MatrixCursor(new String[]{"apiVersion", "databaseInstanceId", "clipboardGeneration", "currentSequence"});
                cursor.addRow(new Object[]{1, instance, 1, 1L});
                return cursor;
            }
            MatrixCursor cursor = new MatrixCursor(new String[]{"id", "itemType", "createdAt", "isPinned", "preview", "contentUri"});
            if ("0".equals(uri.getQueryParameter("afterId"))) cursor.addRow(new Object[]{1L, "TEXT", CREATED, 0, "forged", "content://" + authority + "/items/1/content"});
            return cursor;
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            return ParcelFileDescriptor.open(content, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public String getType(Uri uri) { return "text/plain"; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
    }
}
