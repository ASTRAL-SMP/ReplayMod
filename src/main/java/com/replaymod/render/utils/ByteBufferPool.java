package com.replaymod.render.utils;

import com.google.common.collect.Maps;
import org.lwjgl.BufferUtils;

import java.lang.ref.SoftReference;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class ByteBufferPool {
    private static final int MAX_BUFFERS_PER_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors() + 2);
    private static Map<Integer, List<SoftReference<ByteBuffer>>> directBufferPool = Maps.newHashMap();
    private static Map<Integer, List<SoftReference<ByteBuffer>>> heapBufferPool = Maps.newHashMap();

    public static synchronized ByteBuffer allocate(int size) {
        ByteBuffer buffer = allocateFromPool(directBufferPool, size);
        return buffer != null ? buffer : BufferUtils.createByteBuffer(size);
    }

    public static synchronized ByteBuffer allocateHeap(int size) {
        ByteBuffer buffer = allocateFromPool(heapBufferPool, size);
        return buffer != null ? buffer : ByteBuffer.allocate(size);
    }

    private static ByteBuffer allocateFromPool(Map<Integer, List<SoftReference<ByteBuffer>>> pool, int size) {
        List<SoftReference<ByteBuffer>> available = pool.get(size);
        if (available != null) {
            Iterator<SoftReference<ByteBuffer>> iter = available.iterator();
            try {
                while (iter.hasNext()) {
                    SoftReference<ByteBuffer> reference = iter.next();
                    ByteBuffer buffer = reference.get();
                    iter.remove();
                    if (buffer != null) {
                        return buffer;
                    }
                }
            } finally {
                if (!iter.hasNext()) {
                    pool.remove(size);
                }
            }
        }
        return null;
    }

    public static synchronized void release(ByteBuffer buffer) {
        buffer.clear();
        int size = buffer.capacity();
        Map<Integer, List<SoftReference<ByteBuffer>>> pool = buffer.isDirect() ? directBufferPool : heapBufferPool;
        List<SoftReference<ByteBuffer>> available = pool.get(size);
        if (available == null) {
            available = new LinkedList<>();
            pool.put(size, available);
        } else {
            Iterator<SoftReference<ByteBuffer>> iter = available.iterator();
            while (iter.hasNext()) {
                if (iter.next().get() == null) {
                    iter.remove();
                }
            }
            if (available.size() >= MAX_BUFFERS_PER_SIZE) {
                return;
            }
        }
        available.add(new SoftReference<>(buffer));
    }

    // Drop every pooled buffer and request a GC so the off-heap (DirectByteBuffer) ranges
    // can be reclaimed. Soft references are otherwise only cleared under memory pressure,
    // so after a render finishes the JVM happily holds onto ~ MAX_BUFFERS_PER_SIZE * frame
    // size of native memory for many seconds (at 4K BGRA on a 16-core box that's ~600 MB).
    public static synchronized void clear() {
        directBufferPool.clear();
        heapBufferPool.clear();
        // Encourage prompt finalisation of DirectByteBuffer Cleaners so the off-heap memory
        // is actually returned to the OS instead of waiting for the next major GC.
        System.gc();
    }
}
