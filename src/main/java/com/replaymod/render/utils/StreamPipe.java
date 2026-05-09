package com.replaymod.render.utils;

import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class StreamPipe extends Thread {
    private final InputStream in;
    private final OutputStream out;

    public StreamPipe(InputStream in, OutputStream out) {
        super("StreamPipe from " + in + " to " + out);
        this.in = in;
        this.out = out;
        // Pipes ffmpeg stdout/stderr into the export.log capture buffer. Purely a logging
        // sidecar — if it gets stuck because ffmpeg never sends EOF (orphan child process,
        // hung renderer, etc.), it must not block JVM shutdown. The render finishing path
        // doesn't join() these threads, so a non-daemon pipe was historically a hidden way
        // for "Minecraft won't close after a render" to require force-quit.
        setDaemon(true);
    }

    @Override
    public void run() {
        try {
            IOUtils.copy(in, out);
        } catch (IOException ignored) {
            // We don't care
            // Note: Once we use this for something important, we should probably care!
        }
    }
}
