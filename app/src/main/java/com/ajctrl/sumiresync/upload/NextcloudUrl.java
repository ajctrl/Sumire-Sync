package com.ajctrl.sumiresync.upload;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Builds the fixed Nextcloud WebDAV destination used by Sumire Sync. */
public final class NextcloudUrl {
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private NextcloudUrl() {}

    /**
     * Returns only the HTTPS server origin. Paths from settings saved by older
     * versions are intentionally removed so the WebDAV path is never appended twice.
     */
    public static String serverOrigin(String value) {
        try {
            URI input = new URI(value.trim());
            if (!"https".equalsIgnoreCase(input.getScheme())) {
                throw new IllegalArgumentException("HTTPS URLを入力してください");
            }
            if (input.getHost() == null || input.getUserInfo() != null
                    || input.getQuery() != null || input.getFragment() != null) {
                throw new IllegalArgumentException("有効なサーバーURLを入力してください");
            }
            return new URI("https", null, input.getHost(), input.getPort(), null, null, null)
                    .toASCIIString();
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("有効なサーバーURLを入力してください", error);
        }
    }

    public static URL archiveUrl(String serverUrl, String username, String fileName)
            throws IOException {
        try {
            if (username.trim().isEmpty()) {
                throw new IllegalArgumentException("Nextcloud username is required");
            }
            String target = serverOrigin(serverUrl)
                    + "/remote.php/dav/files/" + pathSegment(username.trim())
                    + "/Sumire/" + pathSegment(fileName);
            return URI.create(target).toURL();
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid Nextcloud server URL", error);
        }
    }

    private static String pathSegment(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte valueByte : bytes) {
            int current = valueByte & 0xff;
            if ((current >= 'a' && current <= 'z')
                    || (current >= 'A' && current <= 'Z')
                    || (current >= '0' && current <= '9')
                    || current == '-' || current == '.' || current == '_' || current == '~') {
                encoded.append((char) current);
            } else {
                encoded.append('%');
                encoded.append(HEX[current >>> 4]);
                encoded.append(HEX[current & 0x0f]);
            }
        }
        return encoded.toString();
    }
}
