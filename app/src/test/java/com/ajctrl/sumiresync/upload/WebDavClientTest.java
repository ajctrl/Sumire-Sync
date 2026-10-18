package com.ajctrl.sumiresync.upload;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

public final class WebDavClientTest {
    private static final String INSTALLATION = "11111111-1111-4111-8111-111111111111";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final List<Request> requests = new ArrayList<>();
    private final List<byte[]> bodies = new ArrayList<>();

    private WebDavClient client(int... statuses) {
        return new WebDavClient(new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request();
            int status = statuses[requests.size()];
            requests.add(request);
            Buffer buffer = new Buffer();
            if (request.body() != null) request.body().writeTo(buffer);
            bodies.add(buffer.readByteArray());
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(status).message("Test response")
                    .header("ETag", "\"version-1\"")
                    .body(ResponseBody.create(null, "server detail")).build();
        }).build());
    }

    private File archive() throws IOException {
        File file = temporary.newFile("clipboard-2026-10.sqlite");
        Files.write(file.toPath(), new byte[]{1, 2, 3, 4});
        return file;
    }

    @Test public void createsCollectionBeforeStreamingArchive() throws Exception {
        client(201, 201, 404, 204).put("https://cloud.example.com", " user@example.com ", "secret", INSTALLATION, archive());
        assertEquals("MKCOL", requests.get(0).method());
        assertEquals("/remote.php/dav/files/user%40example.com/Sumire/",
                requests.get(0).url().encodedPath());
        assertEquals("MKCOL", requests.get(1).method());
        assertEquals("/remote.php/dav/files/user%40example.com/Sumire/" + INSTALLATION + "/",
                requests.get(1).url().encodedPath());
        assertEquals("HEAD", requests.get(2).method());
        assertEquals("PUT", requests.get(3).method());
        assertEquals("*", requests.get(3).header("If-None-Match"));
        assertEquals(requests.get(0).header("Authorization"), requests.get(3).header("Authorization"));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, bodies.get(3));
    }

    @Test public void existingCollectionStillUploads() throws Exception {
        client(405, 405, 200, 201).put("https://cloud.example.com", "user", "secret", INSTALLATION, archive());
        assertEquals(4, requests.size());
        assertEquals("\"version-1\"", requests.get(3).header("If-Match"));
    }

    @Test public void collectionFailureStopsBeforePut() throws Exception {
        IOException error = assertThrows(IOException.class, () ->
                client(403).put("https://cloud.example.com", "user", "secret", INSTALLATION, archive()));
        assertEquals(1, requests.size());
        assertTrue(error.getMessage().contains("403"));
        assertTrue(error.getMessage().contains("server detail"));
        assertFalse(error instanceof ArchiveUploadException);
    }

    @Test public void classifiesFileRejectionsSeparatelyFromAuthenticationAndServerFailures() throws Exception {
        File file = archive();
        for (int code : new int[]{403, 404, 405, 409, 412, 413, 415, 422, 423, 401, 429, 500, 503, 507}) {
            requests.clear();
            bodies.clear();
            IOException error = assertThrows(IOException.class, () -> client(405, 405, 200, code)
                    .put("https://cloud.example.com", "user", "secret", INSTALLATION, file));
            assertEquals("HTTP " + code, code != 401 && code != 429 && code < 500,
                    error instanceof ArchiveUploadException);
        }
    }

    @Test public void putFailureIsNotTreatedAsSuccess() throws Exception {
        IOException error = assertThrows(IOException.class, () ->
                client(405, 405, 200, 405).put("https://cloud.example.com", "user", "secret", INSTALLATION, archive()));
        assertEquals(4, requests.size());
        assertTrue(error.getMessage().contains("405"));
    }

    @Test public void changedRemoteIsNotAcknowledgedOrRetriedWithoutItsPrecondition() throws Exception {
        IOException error = assertThrows(IOException.class, () ->
                client(405, 405, 200, 412).put("https://cloud.example.com", "user", "secret", INSTALLATION, archive()));
        assertEquals(4, requests.size());
        assertEquals("\"version-1\"", requests.get(3).header("If-Match"));
        assertTrue(error.getMessage().contains("412"));
    }

    @Test public void cancellationBeforeStartMakesNoRequests() throws Exception {
        WebDavClient client = client();
        client.cancel();
        assertThrows(IOException.class, () ->
                client.put("https://cloud.example.com", "user", "secret", INSTALLATION, archive()));
        assertTrue(requests.isEmpty());
    }

    @Test public void separateInstallationsAndRetryPreserveOtherAndLegacyArchives() throws Exception {
        String second = "22222222-2222-4222-8222-222222222222";
        String root = "/remote.php/dav/files/user/Sumire/";
        String name = "clipboard-2026-10.sqlite";
        java.util.Map<String, byte[]> remote = new java.util.HashMap<>();
        remote.put(root + name, new byte[]{99}); // Older releases wrote here.
        java.util.Map<String, String> etags = new java.util.HashMap<>();
        boolean[] conflict = {true};
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request();
            String path = request.url().encodedPath();
            int code = 405;
            if (request.method().equals("HEAD")) code = remote.containsKey(path) ? 200 : 404;
            if (request.method().equals("PUT")) {
                if (path.contains(INSTALLATION) && conflict[0]) {
                    conflict[0] = false;
                    remote.put(path, new byte[]{1});
                    etags.put(path, "\"race\"");
                }
                boolean allowed = remote.containsKey(path)
                        ? etags.get(path).equals(request.header("If-Match"))
                        : "*".equals(request.header("If-None-Match"));
                code = allowed ? 204 : 412;
                if (allowed) {
                    Buffer body = new Buffer();
                    request.body().writeTo(body);
                    remote.put(path, body.readByteArray());
                    etags.put(path, "\"version-" + requests.size() + "\"");
                }
            }
            requests.add(request);
            Response.Builder response = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(code).message("Test").body(ResponseBody.create(null, ""));
            if (etags.containsKey(path)) response.header("ETag", etags.get(path));
            return response.build();
        }).build();
        File file = archive();
        Files.write(file.toPath(), new byte[]{2});
        new WebDavClient(http).put("https://cloud.example.com", "user", "secret", second, file);
        Files.write(file.toPath(), new byte[]{3});
        assertThrows(IOException.class, () -> new WebDavClient(http).put(
                "https://cloud.example.com", "user", "secret", INSTALLATION, file));
        new WebDavClient(http).put("https://cloud.example.com", "user", "secret", INSTALLATION, file);
        assertArrayEquals(new byte[]{99}, remote.get(root + name));
        assertArrayEquals(new byte[]{2}, remote.get(root + second + "/" + name));
        assertArrayEquals(new byte[]{3}, remote.get(root + INSTALLATION + "/" + name));
    }

    @Test public void missingOrWeakEtagNeverFallsBackToUnconditionalPut() throws Exception {
        File file = archive();
        for (String etag : new String[]{null, "W/\"weak\""}) {
            List<String> methods = new ArrayList<>();
            WebDavClient client = new WebDavClient(new OkHttpClient.Builder().addInterceptor(chain -> {
                Request request = chain.request();
                methods.add(request.method());
                Response.Builder response = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK").body(ResponseBody.create(null, ""));
                if (etag != null) response.header("ETag", etag);
                return response.build();
            }).build());
            assertThrows(IOException.class, () -> client.put("https://cloud.example.com", "user", "secret", INSTALLATION, file));
            assertEquals(java.util.Arrays.asList("MKCOL", "MKCOL", "HEAD"), methods);
        }
    }

    @Test public void cancellationDuringCollectionRequestPreventsHeadAndPut() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<okhttp3.Call> active = new java.util.concurrent.atomic.AtomicReference<>();
        WebDavClient client = new WebDavClient(new OkHttpClient.Builder().addInterceptor(chain -> {
            active.set(chain.call());
            entered.countDown();
            try {
                if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("test timeout");
            } catch (InterruptedException error) { throw new IOException(error); }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(201).message("Created").body(ResponseBody.create(null, "")).build();
        }).build());
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        File file = archive();
        try {
            java.util.concurrent.Future<?> upload = worker.submit(() -> {
                assertThrows(IOException.class, () -> client.put("https://cloud.example.com", "user", "secret", INSTALLATION, file));
            });
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            client.cancel();
            assertTrue(active.get().isCanceled());
            release.countDown();
            upload.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }
}
