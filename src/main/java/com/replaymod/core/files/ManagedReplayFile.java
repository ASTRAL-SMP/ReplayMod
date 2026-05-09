package com.replaymod.core.files;

import com.replaymod.replaystudio.replay.ReplayFile;

import java.io.IOException;
import java.nio.file.Path;

public class ManagedReplayFile extends DelegatingReplayFile {
    private Runnable onClose;
    private final Path replayPath;

    public ManagedReplayFile(ReplayFile delegate, Runnable onClose) {
        this(delegate, onClose, null);
    }

    public ManagedReplayFile(ReplayFile delegate, Runnable onClose, Path replayPath) {
        super(delegate);

        this.onClose = onClose;
        this.replayPath = replayPath;
    }

    /**
     * Path of the .mcpr file backing this replay (output side, normalized & absolute).
     * May be {@code null} for legacy callers; modern open paths always set it.
     */
    public Path getReplayPath() {
        return replayPath;
    }

    @Override
    public void close() throws IOException {
        super.close();

        onClose.run();
        onClose = () -> {};
    }
}
