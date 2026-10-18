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

    @Test public void coalescesShortAndZeroReadsAndDoesNotReuseDeliveredBuffers() throws Exception {
        byte[] source = new byte[1024 * 1024 + 37];
        new java.util.Random(42).nextBytes(source);
        java.util.List<byte[]> chunks = new java.util.ArrayList<>();
        InputStream input = new java.io.ByteArrayInputStream(source) {
            int reads;
            @Override public synchronized int read(byte[] buffer, int offset, int length) {
                if (++reads % 7 == 0) return 0;
                return super.read(buffer, offset, Math.min(length, 1024));
            }
        };
        assertEquals(source.length, ContentChunker.copy(input, 64 * 1024, (index, chunk) -> {
            assertEquals(chunks.size(), index);
            chunks.add(chunk);
        }));
        assertEquals(17, chunks.size());
        ByteArrayOutputStream restored = new ByteArrayOutputStream();
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i == 16 ? 37 : 64 * 1024, chunks.get(i).length);
            restored.write(chunks.get(i));
        }
        assertArrayEquals(source, restored.toByteArray());
    }

    @Test public void cancellationDuringShortReadsDoesNotEmitPartialChunk() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean();
        InputStream input = new java.io.ByteArrayInputStream(new byte[1024]) {
            @Override public synchronized int read(byte[] buffer, int offset, int length) {
                stopped.set(true);
                return super.read(buffer, offset, Math.min(length, 10));
            }
        };
        AtomicInteger emitted = new AtomicInteger();
        org.junit.Assert.assertThrows(java.io.IOException.class, () -> ContentChunker.copy(input,
                64 * 1024, (index, chunk) -> emitted.incrementAndGet(), stopped::get));
        assertEquals(0, emitted.get());
        assertEquals(0, ContentChunker.copy(new java.io.ByteArrayInputStream(new byte[0]),
                64 * 1024, (index, chunk) -> emitted.incrementAndGet()));
        assertEquals(0, emitted.get());
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
