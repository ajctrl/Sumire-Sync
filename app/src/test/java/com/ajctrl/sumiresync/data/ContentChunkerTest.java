package com.ajctrl.sumiresync.data;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

public final class ContentChunkerTest {
    @Test public void preservesContentAndChunkOrder() throws Exception {
        byte[] source = "sumire-sync-content".getBytes("UTF-8");
        ByteArrayOutputStream reconstructed = new ByteArrayOutputStream();
        AtomicInteger expectedIndex = new AtomicInteger();

        long size = ContentChunker.copy(new ByteArrayInputStream(source), 4, (index, chunk) -> {
            assertEquals(expectedIndex.getAndIncrement(), index);
            reconstructed.write(chunk);
        });

        assertEquals(source.length, size);
        assertArrayEquals(source, reconstructed.toByteArray());
    }

    @Test public void streamsPastFormer64MiBLimitWithoutBufferingWholeInput() throws Exception {
        long sourceBytes = 65L * 1024L * 1024L + 17;
        AtomicInteger chunks = new AtomicInteger();
        InputStream generated = new InputStream() {
            long remaining = sourceBytes;

            @Override public int read() {
                if (remaining == 0) return -1;
                remaining--;
                return 0;
            }

            @Override public int read(byte[] buffer, int offset, int length) {
                if (remaining == 0) return -1;
                int count = (int) Math.min(remaining, length);
                remaining -= count;
                return count;
            }
        };

        long size = ContentChunker.copy(generated, 64 * 1024,
                (index, chunk) -> chunks.incrementAndGet());

        assertEquals(sourceBytes, size);
        assertEquals((int) ((sourceBytes + 64 * 1024 - 1) / (64 * 1024)), chunks.get());
    }
}
