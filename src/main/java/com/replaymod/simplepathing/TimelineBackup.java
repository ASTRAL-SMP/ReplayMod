package com.replaymod.simplepathing;

import com.replaymod.replaystudio.pathing.path.Timeline;
import com.replaymod.replaystudio.pathing.serialize.TimelineSerialization;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * Sidecar backup of the in-progress timeline JSON, kept next to the .mcpr file.
 *
 * The replay's own staging area ({@code <replay>.mcpr.tmp/}) is the primary recovery
 * mechanism, but it relies on the user accepting the recovery dialog on the next
 * launch. This sidecar runs in parallel: if it ever shows up next to a replay whose
 * stored timeline has no keyframes, we silently restore from it on open. That covers
 * crashes where the recovery dialog is dismissed (or never seen — e.g. the user
 * opens a different replay first, having no idea the keyframes survived elsewhere).
 *
 * The backup is just the same JSON {@link TimelineSerialization} writes into the
 * zip's {@code timelines.json} entry, so loading is symmetric.
 */
public final class TimelineBackup {
    private static final String SUFFIX = ".timeline.bak.json";

    private TimelineBackup() {}

    public static java.nio.file.Path backupFor(java.nio.file.Path replayPath) {
        if (replayPath == null) return null;
        java.nio.file.Path parent = replayPath.getParent();
        String name = replayPath.getFileName().toString() + SUFFIX;
        return parent != null ? parent.resolve(name) : java.nio.file.Paths.get(name);
    }

    /** Atomically writes the serialized timeline JSON to the sidecar file. */
    public static void write(java.nio.file.Path replayPath, String json) throws IOException {
        java.nio.file.Path backup = backupFor(replayPath);
        if (backup == null) return;
        java.nio.file.Path tmp = backup.resolveSibling(backup.getFileName() + ".tmp");
        java.nio.file.Path parent = backup.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            // Some filesystems (FAT, certain network mounts) don't support atomic moves.
            Files.move(tmp, backup, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static String read(java.nio.file.Path replayPath) throws IOException {
        java.nio.file.Path backup = backupFor(replayPath);
        if (backup == null || !Files.exists(backup)) return null;
        return new String(Files.readAllBytes(backup), StandardCharsets.UTF_8);
    }

    public static void delete(java.nio.file.Path replayPath) {
        java.nio.file.Path backup = backupFor(replayPath);
        if (backup == null) return;
        try {
            Files.deleteIfExists(backup);
        } catch (IOException ignored) {
        }
    }

    /** True if at least one path in the timeline has at least one keyframe. */
    public static boolean hasKeyframes(Timeline timeline) {
        if (timeline == null) return false;
        for (com.replaymod.replaystudio.pathing.path.Path path : timeline.getPaths()) {
            if (!path.getKeyframes().isEmpty()) return true;
        }
        return false;
    }

    /**
     * Tries to deserialize the timeline JSON via the supplied registry. Returns null
     * (and logs nothing) if the file is malformed — restoration is best-effort.
     */
    public static Timeline tryDeserialize(SPTimeline registry, String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            Map<String, Timeline> deserialized = new TimelineSerialization(registry, null).deserialize(json);
            return deserialized.get("");
        } catch (Throwable ignored) {
            return null;
        }
    }
}
