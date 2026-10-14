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
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
    private final OkHttpClient client;

    public WebDavClient() { this(CLIENT); }

    WebDavClient(OkHttpClient client) { this.client = client; }

    public void put(String serverUrl, String username, String password, File file) throws IOException {
        String normalizedUsername = username.trim();
        URL target = NextcloudUrl.archiveUrl(serverUrl, normalizedUsername, file.getName());
        URL collection = NextcloudUrl.archiveUrl(serverUrl, normalizedUsername, "");
        String authorization = Credentials.basic(normalizedUsername, password, StandardCharsets.UTF_8);
        // HttpURLConnection rejects WebDAV extension methods such as MKCOL on Android.
        Request createCollection = new Request.Builder().url(collection)
                .header("Authorization", authorization)
                .method("MKCOL", RequestBody.create(null, new byte[0])).build();
        execute(createCollection, true);
        Request upload = new Request.Builder().url(target)
                .header("Authorization", authorization)
                .put(RequestBody.create(MediaType.get("application/vnd.sqlite3"), file)).build();
        execute(upload, false);
    }

    private void execute(Request request, boolean creatingCollection) throws IOException {
        URL target = request.url().url();
        try (Response response = client.newCall(request).execute()) {
            // RFC 4918: MKCOL returns 405 when the collection already exists.
            if (!response.isSuccessful() && !(creatingCollection && response.code() == 405)) {
                throw httpError(response, target);
            }
        } catch (HttpStatusException error) {
            throw error;
        } catch (IOException error) {
            throw connectionError(target, error);
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
