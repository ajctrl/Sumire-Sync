package com.ajctrl.sumiresync.data;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.BooleanSupplier;

public final class ContentChunker {
    private ContentChunker() {}

    public static long copy(InputStream input, int chunkBytes, Sink sink) throws IOException {
        return copy(input, chunkBytes, sink, () -> false);
    }

    public static long copy(InputStream input, int chunkBytes, Sink sink,
                            BooleanSupplier stopped) throws IOException {
        if (chunkBytes <= 0) throw new IllegalArgumentException("chunkBytes must be positive");
        byte[] buffer = new byte[chunkBytes];
        long total = 0;
        int chunkIndex = 0;
        int filled = 0;
        int count;
        while (true) {
            checkStopped(stopped);
            count = input.read(buffer, filled, chunkBytes - filled);
            checkStopped(stopped);
            if (count == -1) {
                if (filled > 0) {
                    byte[] tail = new byte[filled];
                    System.arraycopy(buffer, 0, tail, 0, filled);
                    sink.write(chunkIndex, tail);
                }
                break;
            }
            if (count == 0) continue;
            filled += count;
            total = Math.addExact(total, count);
            if (filled == chunkBytes) {
                sink.write(chunkIndex++, buffer);
                // A sink may retain the array; never mutate a delivered chunk.
                buffer = new byte[chunkBytes];
                filled = 0;
            }
        }
        return total;
    }

    private static void checkStopped(BooleanSupplier stopped) throws IOException {
        if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new IOException("Sync cancelled");
        }
    }

    public interface Sink {
        void write(int chunkIndex, byte[] content) throws IOException;
    }
}
