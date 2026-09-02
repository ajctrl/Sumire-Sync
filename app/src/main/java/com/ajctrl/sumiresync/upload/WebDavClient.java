package com.ajctrl.sumiresync.upload;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLException;

public final class WebDavClient {
    public void put(String serverUrl, String username, String password, File file) throws IOException {
        String normalizedUsername = username.trim();
        URL target = NextcloudUrl.archiveUrl(serverUrl, normalizedUsername, file.getName());
        HttpURLConnection connection = (HttpURLConnection) target.openConnection();
        connection.setRequestMethod("PUT");
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(file.length());
        connection.setRequestProperty("Content-Type", "application/vnd.sqlite3");
        if (!normalizedUsername.isEmpty() || !password.isEmpty()) {
            String auth = Base64.encodeToString((normalizedUsername + ":" + password)
                    .getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            connection.setRequestProperty("Authorization", "Basic " + auth);
        }
        try {
            try {
                try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file));
                     BufferedOutputStream output = new BufferedOutputStream(connection.getOutputStream())) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    throw httpError(connection, target, status);
                }
            } catch (HttpStatusException error) {
                throw error;
            } catch (IOException error) {
                throw connectionError(target, error);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpStatusException httpError(HttpURLConnection connection, URL target,
                                                 int status) {
        StringBuilder message = new StringBuilder("NextcloudがHTTP ").append(status);
        try {
            String reason = connection.getResponseMessage();
            if (reason != null && !reason.trim().isEmpty()) message.append(' ').append(reason);
        } catch (IOException ignored) {
            // The numeric status is sufficient when the reason phrase is unavailable.
        }
        message.append(" を返しました; URL=").append(target);
        String response = errorResponse(connection);
        if (!response.isEmpty()) message.append("; 応答=").append(response);
        return new HttpStatusException(message.toString());
    }

    private static String errorResponse(HttpURLConnection connection) {
        InputStream stream = connection.getErrorStream();
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
