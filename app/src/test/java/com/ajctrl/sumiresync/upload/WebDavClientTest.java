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
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final List<Request> requests = new ArrayList<>();
    private final List<byte[]> bodies = new ArrayList<>();

    private WebDavClient client(int... statuses) {
        return new WebDavClient(new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request();
            int status = statuses[requests.size()];
            requests.add(request);
            Buffer buffer = new Buffer();
            request.body().writeTo(buffer);
            bodies.add(buffer.readByteArray());
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(status).message("Test response")
                    .body(ResponseBody.create(null, "server detail")).build();
        }).build());
    }

    private File archive() throws IOException {
        File file = temporary.newFile("clipboard-2026-10.sqlite");
        Files.write(file.toPath(), new byte[]{1, 2, 3, 4});
        return file;
    }

    @Test public void createsCollectionBeforeStreamingArchive() throws Exception {
        client(201, 204).put("https://cloud.example.com", " user@example.com ", "secret", archive());
        assertEquals("MKCOL", requests.get(0).method());
        assertEquals("/remote.php/dav/files/user%40example.com/Sumire/",
                requests.get(0).url().encodedPath());
        assertEquals("PUT", requests.get(1).method());
        assertEquals(requests.get(0).header("Authorization"), requests.get(1).header("Authorization"));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, bodies.get(1));
    }

    @Test public void existingCollectionStillUploads() throws Exception {
        client(405, 201).put("https://cloud.example.com", "user", "secret", archive());
        assertEquals(2, requests.size());
    }

    @Test public void collectionFailureStopsBeforePut() throws Exception {
        IOException error = assertThrows(IOException.class, () ->
                client(403).put("https://cloud.example.com", "user", "secret", archive()));
        assertEquals(1, requests.size());
        assertTrue(error.getMessage().contains("403"));
        assertTrue(error.getMessage().contains("server detail"));
    }

    @Test public void putFailureIsNotTreatedAsSuccess() throws Exception {
        IOException error = assertThrows(IOException.class, () ->
                client(405, 405).put("https://cloud.example.com", "user", "secret", archive()));
        assertEquals(2, requests.size());
        assertTrue(error.getMessage().contains("405"));
    }
}
