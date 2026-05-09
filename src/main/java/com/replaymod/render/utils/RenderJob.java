package com.replaymod.render.utils;

import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.replaymod.render.RenderSettings;
import com.replaymod.replaystudio.lib.guava.base.Optional;
import com.replaymod.replaystudio.pathing.PathingRegistry;
import com.replaymod.replaystudio.pathing.path.Timeline;
import com.replaymod.replaystudio.pathing.serialize.TimelineSerialization;
import com.replaymod.replaystudio.replay.ReplayFile;
import com.replaymod.simplepathing.SPTimeline;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class RenderJob {
    private Timeline timeline;
    private RenderSettings settings;

    public RenderJob() {
    }

    public String getName() {
        return settings.getOutputFile().getName();
    }

    public Timeline getTimeline() {
        return this.timeline;
    }

    public RenderSettings getSettings() {
        return this.settings;
    }

    public void setTimeline(Timeline timeline) {
        this.timeline = timeline;
    }

    public void setSettings(RenderSettings settings) {
        this.settings = settings;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RenderJob renderJob = (RenderJob) o;
        return timeline.equals(renderJob.timeline) &&
                settings.equals(renderJob.settings);
    }

    @Override
    public int hashCode() {
        return Objects.hash(timeline, settings);
    }

    @Override
    public String toString() {
        return "RenderJob{" +
                "timeline=" + timeline +
                ", settings=" + settings +
                '}';
    }

    public static List<RenderJob> readQueue(ReplayFile replayFile) throws IOException {
        synchronized (replayFile) {
            Optional<InputStream> optIn = replayFile.get("renderQueue.json");
            if (!optIn.isPresent()) {
                return new ArrayList<>();
            }
            try (InputStream in = optIn.get();
                 InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                List<RenderJob> jobs = new GsonBuilder()
                        .registerTypeAdapter(Timeline.class, new TimelineTypeAdapter())
                        .create()
                        .fromJson(reader, new TypeToken<List<RenderJob>>(){}.getType());
                if (jobs == null) {
                    jobs = new ArrayList<>();
                }
                return jobs;
            }
        }
    }

    public static void writeQueue(ReplayFile replayFile, List<RenderJob> renderQueue) throws IOException {
        synchronized (replayFile) {
            try (OutputStream out = replayFile.write("renderQueue.json");
                 OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                new GsonBuilder()
                        .registerTypeAdapter(Timeline.class, new TimelineTypeAdapter())
                        .create()
                        .toJson(renderQueue, writer);
            }
        }
    }

    // === Sidecar backup ===
    //
    // Mirrors writeQueue/readQueue but onto a plain JSON file next to the .mcpr.
    // Same rationale as TimelineBackup: the zip's staging area only survives a
    // crash if the user accepts the recovery dialog on the next launch, and a
    // user-queued render job (output path, codec, timeline binding...) is just
    // as painful to reconstruct as the keyframes themselves.

    private static final String BACKUP_SUFFIX = ".renderqueue.bak.json";

    public static Path backupFor(Path replayPath) {
        if (replayPath == null) return null;
        Path parent = replayPath.getParent();
        String name = replayPath.getFileName().toString() + BACKUP_SUFFIX;
        return parent != null ? parent.resolve(name) : java.nio.file.Paths.get(name);
    }

    public static void writeBackup(Path replayPath, List<RenderJob> renderQueue) throws IOException {
        Path backup = backupFor(replayPath);
        if (backup == null) return;
        StringWriter buffer = new StringWriter();
        new GsonBuilder()
                .registerTypeAdapter(Timeline.class, new TimelineTypeAdapter())
                .create()
                .toJson(renderQueue, buffer);
        byte[] bytes = buffer.toString().getBytes(StandardCharsets.UTF_8);
        Path tmp = backup.resolveSibling(backup.getFileName() + ".tmp");
        Path parent = backup.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(tmp, backup, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Returns null if the backup is missing, empty, or unparseable — restoration is best-effort. */
    public static List<RenderJob> readBackup(Path replayPath) {
        Path backup = backupFor(replayPath);
        if (backup == null || !Files.exists(backup)) return null;
        try {
            String json = new String(Files.readAllBytes(backup), StandardCharsets.UTF_8);
            if (json.isEmpty()) return null;
            try (Reader reader = new StringReader(json)) {
                List<RenderJob> jobs = new GsonBuilder()
                        .registerTypeAdapter(Timeline.class, new TimelineTypeAdapter())
                        .create()
                        .fromJson(reader, new TypeToken<List<RenderJob>>(){}.getType());
                return jobs == null || jobs.isEmpty() ? null : jobs;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    public static void deleteBackup(Path replayPath) {
        Path backup = backupFor(replayPath);
        if (backup == null) return;
        try {
            Files.deleteIfExists(backup);
        } catch (IOException ignored) {
        }
    }

    private static class TimelineTypeAdapter extends TypeAdapter<Timeline> {

        private final TimelineSerialization serialization;

        public TimelineTypeAdapter(TimelineSerialization serialization) {
            this.serialization = serialization;
        }

        public TimelineTypeAdapter(PathingRegistry registry) {
            this(new TimelineSerialization(registry, null));
        }

        public TimelineTypeAdapter() {
            // TODO need to somehow get rid of the reliance on simplepathing
            this(new SPTimeline());
        }

        @Override
        public void write(JsonWriter out, Timeline value) throws IOException {
            out.value(serialization.serialize(Collections.singletonMap("", value)));
        }

        @Override
        public Timeline read(JsonReader in) throws IOException {
            return serialization.deserialize(in.nextString()).get("");
        }
    }
}
