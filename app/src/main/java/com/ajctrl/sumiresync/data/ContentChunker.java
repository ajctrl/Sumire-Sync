package com.ajctrl.sumiresync.data;

import java.io.IOException;
import java.io.InputStream;

public final class ContentChunker {
    private ContentChunker() {}

    public static long copy(InputStream input, int chunkBytes, Sink sink) throws IOException {
        if (chunkBytes <= 0) throw new IllegalArgumentException("chunkBytes must be positive");
        byte[] buffer = new byte[chunkBytes];
        long total = 0;
        int chunkIndex = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count == 0) continue;
            byte[] chunk = new byte[count];
            System.arraycopy(buffer, 0, chunk, 0, count);
            sink.write(chunkIndex++, chunk);
            total = Math.addExact(total, count);
        }
        return total;
    }

    public interface Sink {
        void write(int chunkIndex, byte[] content) throws IOException;
    }
}
