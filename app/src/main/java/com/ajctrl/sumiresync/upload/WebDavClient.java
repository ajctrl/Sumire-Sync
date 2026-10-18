package com.ajctrl.sumiresync.upload;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLException;

import okhttp3.Credentials;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class WebDavClient {
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
    private final OkHttpClient client;
    private boolean cancelled;
    private Call activeCall;

    public WebDavClient() { this(CLIENT); }

    WebDavClient(OkHttpClient client) { this.client = client; }

    public synchronized void cancel() {
        cancelled = true;
        if (activeCall != null) activeCall.cancel();
    }

    public void put(String serverUrl, String username, String password, String installationId, File file) throws IOException {
        String normalizedUsername = username.trim();
        URL target = NextcloudUrl.archiveUrl(serverUrl, normalizedUsername, installationId, file.getName());
        URL collection = NextcloudUrl.archiveUrl(serverUrl, normalizedUsername, installationId, "");
        String authorization = Credentials.basic(normalizedUsername, password, StandardCharsets.UTF_8);
        // HttpURLConnection rejects WebDAV extension methods such as MKCOL on Android.
        for (URL directory : new URL[]{NextcloudUrl.collectionUrl(serverUrl, normalizedUsername), collection}) {
            execute(new Request.Builder().url(directory).header("Authorization", authorization)
                    .method("MKCOL", RequestBody.create(null, new byte[0])).build(), true);
        }
        String etag = execute(new Request.Builder().url(target)
                .header("Authorization", authorization).head().build(), false);
        Request.Builder upload = new Request.Builder().url(target)
                .header("Authorization", authorization)
                .put(RequestBody.create(MediaType.get("application/vnd.sqlite3"), file));
        // A cancelled/timed-out request can still reach the server. Conditional writes
        // prevent that older request from replacing a newer successful upload.
        if (etag == null) upload.header("If-None-Match", "*");
        else upload.header("If-Match", etag);
        execute(upload.build(), false);
    }

    private String execute(Request request, boolean creatingCollection) throws IOException {
        URL target = request.url().url();
        Call call;
        synchronized (this) {
            if (cancelled) throw new IOException("Upload cancelled");
            call = client.newCall(request);
            activeCall = call;
        }
        try (Response response = call.execute()) {
            if ("HEAD".equals(request.method()) && response.code() == 404) return null;
            // RFC 4918: MKCOL returns 405 when the collection already exists.
            if (!response.isSuccessful() && !(creatingCollection && response.code() == 405)) {
                HttpStatusException error = httpError(response, target);
                int code = response.code();
                // Authentication, rate limiting, quota, collection and server failures
                // affect the destination. Resource conflicts/rejections affect this file.
                if (!creatingCollection && (code == 403 || code == 404 || code == 405
                        || code == 409 || code == 412 || code == 413 || code == 415
                        || code == 422 || code == 423)) {
                    throw new ArchiveUploadException(error.getMessage());
                }
                throw error;
            }
            String etag = response.header("ETag");
            if ("HEAD".equals(request.method()) && (etag == null || !etag.startsWith("\"")
                    || !etag.endsWith("\"") || etag.length() < 2)) {
                throw new ArchiveUploadException("Server did not return a strong ETag; refusing an unsafe overwrite");
            }
            return etag;
        } catch (HttpStatusException | ArchiveUploadException error) {
            throw error;
        } catch (IOException error) {
            throw connectionError(target, error);
        } finally {
            synchronized (this) { if (activeCall == call) activeCall = null; }
        }
    }

    private static HttpStatusException httpError(Response response, URL target) {
        StringBuilder message = new StringBuilder("NextcloudがHTTP ").append(response.code());
        if (!response.message().trim().isEmpty()) message.append(' ').append(response.message());
        message.append(" を返しました; URL=").append(target);
        String body = errorResponse(response);
        if (!body.isEmpty()) message.append("; 応答=").append(body);
        return new HttpStatusException(message.toString());
    }

    private static String errorResponse(Response response) {
        InputStream stream = response.body() == null ? null : response.body().byteStream();
        if (stream == null) return "";
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[512];
            int remaining = 2048;
            while (remaining > 0) {
                int count = input.read(buffer, 0, Math.min(buffer.length, remaining));
                if (count < 0) break;
                output.write(buffer, 0, count);
                remaining -= count;
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ").trim();
        } catch (IOException error) {
            return "<応答本文を読み取れません: " + error.getClass().getSimpleName() + ">";
        }
    }

    private static IOException connectionError(URL target, IOException error) {
        String category;
        if (error instanceof UnknownHostException) {
            category = "ホスト名を解決できません（DNSまたはURLを確認してください）";
        } else if (error instanceof ConnectException) {
            category = "接続を拒否されました（ホスト、ポート、サーバー稼働状態を確認してください）";
        } else if (error instanceof SocketTimeoutException) {
            category = "接続がタイムアウトしました";
        } else if (error instanceof SSLException) {
            category = "TLS/証明書の検証に失敗しました";
        } else {
            category = "通信に失敗しました";
        }
        String authority = target.getHost() + (target.getPort() < 0 ? "" : ":" + target.getPort());
        String detail = error.getMessage();
        return new IOException("Nextcloud接続失敗: " + category + "; 接続先=" + authority
                + "; 種類=" + error.getClass().getSimpleName()
                + (detail == null || detail.trim().isEmpty() ? "" : "; 詳細=" + detail), error);
    }

    private static final class HttpStatusException extends IOException {
        HttpStatusException(String message) {
            super(message);
        }
    }
}
