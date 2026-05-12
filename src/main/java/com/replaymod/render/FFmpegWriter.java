package com.replaymod.render;

import com.replaymod.core.versions.MCVer;
import com.replaymod.render.frame.BitmapFrame;
import com.replaymod.render.rendering.Channel;
import com.replaymod.render.rendering.FrameConsumer;
import com.replaymod.render.rendering.VideoRenderer;
import com.replaymod.render.utils.ByteBufferPool;
import com.replaymod.render.utils.StreamPipe;
import de.johni0702.minecraft.gui.utils.lwjgl.ReadableDimension;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.TeeOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static com.replaymod.render.ReplayModRender.LOGGER;
import static org.apache.commons.lang3.Validate.isTrue;

public class FFmpegWriter implements FrameConsumer<BitmapFrame> {
    private static final long BENCHMARK_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final String LEGACY_MP4_CUSTOM_ARGS =
            "-y -f rawvideo -pix_fmt bgra -s %WIDTH%x%HEIGHT% -r %FPS% -i - %FILTERS%-an -c:v libx264 -b:v %BITRATE% -pix_fmt yuv420p \"%FILENAME%\"";
    private static final String LEGACY_WEBM_CUSTOM_ARGS =
            "-y -f rawvideo -pix_fmt bgra -s %WIDTH%x%HEIGHT% -r %FPS% -i - %FILTERS%-an -c:v libvpx -b:v %BITRATE% -pix_fmt yuv420p \"%FILENAME%\"";
    private static final String NVENC_BGRA_PROPERTY = "replaymod.ffmpeg.nvencBgra";
    private static final String NVENC_BGRA_ENV = "REPLAYMOD_FFMPEG_NVENC_BGRA";
    private static final String STALL_TIMEOUT_PROPERTY = "replaymod.ffmpeg.stallTimeoutSeconds";
    private static final String STALL_TIMEOUT_ENV = "REPLAYMOD_FFMPEG_STALL_TIMEOUT_SECONDS";
    private static final long DEFAULT_STALL_TIMEOUT_SECONDS = 60;
    // Quality preset for hardware encoders. The historical defaults (NVENC `p1`,
    // QSV `veryfast`, AMF `quality speed`) target real-time streaming and are
    // visibly blockier than vanilla `libx264 -preset veryfast` at the same target
    // bitrate, which is what users compare against when they say the optimised
    // render path "looks rougher" than the regular render. The new defaults are
    // tuned for offline/file encoding (NVENC `p5 -tune hq`, QSV `slower`,
    // AMF `balanced`). Set this property to `speed` to restore the old behaviour
    // when you actually need maximum encoding throughput over output quality.
    private static final String HW_PROFILE_PROPERTY = "replaymod.ffmpeg.hwProfile";
    private static final String HW_PROFILE_ENV = "REPLAYMOD_FFMPEG_HW_PROFILE";
    private static final String HW_PROFILE_QUALITY = "quality";
    private static final String HW_PROFILE_SPEED = "speed";

    private final VideoRenderer renderer;
    private final RenderSettings settings;
    private final Process process;
    private final OutputStream outputStream;
    private final String commandArgs;
    private final boolean inputNeedsVerticalFlip;
    private final int maxQueuedFrames;
    private final Object queueLock = new Object();
    private final TreeMap<Integer, Map<Channel, BitmapFrame>> queuedFrames = new TreeMap<>();
    private final Thread writerThread;
    private final Thread watchdogThread;
    private final long stallTimeoutNanos;
    private volatile long writeStartedNanos = -1;
    private byte[] writeBuffer;
    private volatile boolean aborted;
    private volatile Throwable writerFailure;
    private boolean inputClosed;
    private int nextFrameToWrite;
    private final long startedNanos = System.nanoTime();
    private long firstFrameNanos = -1;
    private long lastFrameNanos = -1;
    private long framesWritten;
    private long bytesWritten;
    private long writeNanos;
    private long maxFrameWriteNanos;
    private long writeCalls;
    private long lastBenchmarkLogNanos;
    private long enqueueWaitNanos;
    private int maxQueueDepth;
    private boolean loggedBufferMode;

    private ByteArrayOutputStream ffmpegLog = new ByteArrayOutputStream(4096);

    public FFmpegWriter(final VideoRenderer renderer) throws IOException {
        this(renderer, false);
    }

    public FFmpegWriter(final VideoRenderer renderer, boolean inputNeedsVerticalFlip) throws IOException {
        this.renderer = renderer;
        this.settings = renderer.getRenderSettings();
        this.inputNeedsVerticalFlip = inputNeedsVerticalFlip;

        File outputFolder = settings.getOutputFile().getParentFile();
        FileUtils.forceMkdir(outputFolder);
        String fileName = settings.getOutputFile().getName();

        String executable = settings.getExportCommandOrDefault();
        commandArgs = buildCommandArgs(executable, fileName);
        LOGGER.info("Starting {} with args: {}", executable, commandArgs);
        String[] cmdline;
        try {
            cmdline = new CommandLine(executable).addArguments(commandArgs, false).toStrings();
        } catch (IllegalArgumentException e) {
            LOGGER.error("Failed to parse ffmpeg command line:", e);
            throw new FFmpegStartupException(settings, e.getLocalizedMessage());
        }
        try {
            process = new ProcessBuilder(cmdline).directory(outputFolder).start();
        } catch (IOException e) {
            throw new NoFFmpegException(e);
        }
        File exportLogFile = resolveExportLogFile(settings);
        OutputStream exportLogOut = new TeeOutputStream(new FileOutputStream(exportLogFile), ffmpegLog);
        new StreamPipe(process.getInputStream(), exportLogOut).start();
        new StreamPipe(process.getErrorStream(), exportLogOut).start();
        LOGGER.info("FFmpeg subprocess output is mirrored to {}.", exportLogFile.getAbsolutePath());
        outputStream = process.getOutputStream();
        maxQueuedFrames = Math.max(2, Math.min(8, settings.getRenderWorkerThreadCount()));
        stallTimeoutNanos = TimeUnit.SECONDS.toNanos(parseStallTimeoutSeconds());
        writerThread = new Thread(this::runWriter, "replaymod-ffmpeg-writer");
        writerThread.setDaemon(true);
        writerThread.start();
        watchdogThread = new Thread(this::runWatchdog, "replaymod-ffmpeg-watchdog");
        watchdogThread.setDaemon(true);
        watchdogThread.start();

        long rawBytesPerSecond = (long) settings.getVideoWidth()
                * (long) settings.getVideoHeight()
                * 4L
                * (long) settings.getFramesPerSecond();
        LOGGER.info("FFmpeg benchmark started: resolution={}x{}, fps={}, rawInputRate={}/s, targetBitrate={}/s, stallTimeout={}s",
                settings.getVideoWidth(), settings.getVideoHeight(), settings.getFramesPerSecond(),
                formatBytes(rawBytesPerSecond), formatBytes(settings.getBitRate() / 8L),
                TimeUnit.NANOSECONDS.toSeconds(stallTimeoutNanos));
    }

    private static long parseStallTimeoutSeconds() {
        String value = System.getProperty(STALL_TIMEOUT_PROPERTY);
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv(STALL_TIMEOUT_ENV);
        }
        if (value != null && !value.trim().isEmpty()) {
            try {
                long parsed = Long.parseLong(value.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException e) {
                LOGGER.warn("Ignoring invalid {} value '{}'; using default {}s.",
                        STALL_TIMEOUT_PROPERTY, value, DEFAULT_STALL_TIMEOUT_SECONDS);
            }
        }
        return DEFAULT_STALL_TIMEOUT_SECONDS;
    }

    private static boolean isNvencBgraEnabled() {
        String value = System.getProperty(NVENC_BGRA_PROPERTY);
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv(NVENC_BGRA_ENV);
        }
        return value != null && Boolean.parseBoolean(value.trim());
    }

    private static boolean isHwSpeedProfile() {
        String value = System.getProperty(HW_PROFILE_PROPERTY);
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv(HW_PROFILE_ENV);
        }
        return value != null && HW_PROFILE_SPEED.equalsIgnoreCase(value.trim());
    }

    // Each render gets its own log file under <runDir>/replay_debug/, named after the output
    // video so it pairs 1:1 with the produced .mp4 (or with a wall-clock timestamp if no name
    // is available). The prior behaviour was a single <runDir>/export.log that got overwritten
    // on every render, which made it impossible to correlate FFmpeg output with a specific
    // failed export after the fact.
    private static File resolveExportLogFile(RenderSettings settings) throws IOException {
        File debugDir = new File(MCVer.getMinecraft().runDirectory, "replay_debug");
        FileUtils.forceMkdir(debugDir);
        String stem;
        File outputFile = settings.getOutputFile();
        if (outputFile != null && outputFile.getName() != null && !outputFile.getName().isEmpty()) {
            String name = outputFile.getName();
            int dot = name.lastIndexOf('.');
            stem = dot > 0 ? name.substring(0, dot) : name;
        } else {
            stem = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date());
        }
        return new File(debugDir, "export-" + stem + ".log");
    }

    @Override
    public void close() throws IOException {
        synchronized (queueLock) {
            inputClosed = true;
            queueLock.notifyAll();
        }
        try {
            writerThread.join(TimeUnit.SECONDS.toMillis(60));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (writerThread.isAlive()) {
            aborted = true;
            IOUtils.closeQuietly(outputStream);
            writerThread.interrupt();
            try {
                writerThread.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            LOGGER.warn("FFmpeg writer thread did not finish cleanly; forcing FFmpeg stdin closed.");
        }
        IOUtils.closeQuietly(outputStream);

        long startTime = System.nanoTime();
        long rem = TimeUnit.SECONDS.toNanos(30);
        boolean exited = false;
        int exitCode = Integer.MIN_VALUE;
        do {
            try {
                exitCode = process.exitValue();
                exited = true;
                break;
            } catch(IllegalThreadStateException ex) {
                if (rem > 0) {
                    try {
                        Thread.sleep(Math.min(TimeUnit.NANOSECONDS.toMillis(rem) + 1, 100));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            rem = TimeUnit.SECONDS.toNanos(30) - (System.nanoTime() - startTime);
        } while (rem > 0);

        if (!exited) {
            process.destroy();
        }
        watchdogThread.interrupt();
        try {
            watchdogThread.join(TimeUnit.SECONDS.toMillis(2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        logFinalBenchmark(System.nanoTime(), System.nanoTime() - startTime, exited, exitCode);
    }

    @Override
    public void consume(Map<Channel, BitmapFrame> channels) {
        BitmapFrame frame = channels.get(Channel.BRGA);
        try {
            checkSize(frame.getSize());
            enqueueFrame(frame.getFrameId(), channels);
        } catch (Throwable t) {
            if (aborted) {
                return;
            }
            try {
                // Check whether this is a failure right at the beginning of the rendering process
                // or at some later point (ffmpeg won't print the output file until the first frame
                // has been written to stdin, so we can't already check for invalid args in <init>).
                getVideoFile();
            } catch (FFmpegStartupException e) {
                // Possibly invalid ffmpeg arguments
                LOGGER.error("FFmpeg failed to start or rejected the export arguments:\n{}", e.getLog());
                renderer.setFailure(e);
                return;
            }
            renderer.setFailure(t);
            releaseFrames(channels);
        }
    }

    private void enqueueFrame(int frameId, Map<Channel, BitmapFrame> channels) throws InterruptedException, IOException {
        long waitStartNanos = 0;
        synchronized (queueLock) {
            while (!aborted && writerFailure == null && queuedFrames.size() >= maxQueuedFrames) {
                if (waitStartNanos == 0) {
                    waitStartNanos = System.nanoTime();
                }
                queueLock.wait();
            }
            if (waitStartNanos != 0) {
                enqueueWaitNanos += System.nanoTime() - waitStartNanos;
            }
            if (writerFailure != null) {
                throw new RuntimeException(writerFailure);
            }
            if (aborted || inputClosed) {
                throw new IOException("FFmpeg writer is closed.");
            }
            Map<Channel, BitmapFrame> previous = queuedFrames.put(frameId, channels);
            if (previous != null) {
                releaseFrames(previous);
                LOGGER.warn("Replacing duplicate rendered frame {} in FFmpeg queue.", frameId);
            }
            maxQueueDepth = Math.max(maxQueueDepth, queuedFrames.size());
            queueLock.notifyAll();
        }
    }

    private void runWriter() {
        try {
            while (true) {
                Map<Channel, BitmapFrame> channels = takeNextFrame();
                if (channels == null) {
                    return;
                }
                writeFrame(channels);
            }
        } catch (Throwable t) {
            if (!aborted) {
                writerFailure = t;
                renderer.setFailure(t);
            }
        } finally {
            synchronized (queueLock) {
                for (Map<Channel, BitmapFrame> channels : queuedFrames.values()) {
                    releaseFrames(channels);
                }
                queuedFrames.clear();
                queueLock.notifyAll();
            }
        }
    }

    private Map<Channel, BitmapFrame> takeNextFrame() throws InterruptedException {
        synchronized (queueLock) {
            while (true) {
                Map<Channel, BitmapFrame> channels = queuedFrames.remove(nextFrameToWrite);
                if (channels != null) {
                    nextFrameToWrite++;
                    queueLock.notifyAll();
                    return channels;
                }
                if (inputClosed && !queuedFrames.isEmpty()) {
                    int firstFrameId = queuedFrames.firstKey();
                    LOGGER.warn("FFmpeg writer expected frame {} but only frame {}+ is queued; continuing with queued frame.",
                            nextFrameToWrite, firstFrameId);
                    nextFrameToWrite = firstFrameId;
                    continue;
                }
                if ((inputClosed || aborted) && queuedFrames.isEmpty()) {
                    queueLock.notifyAll();
                    return null;
                }
                queueLock.wait();
            }
        }
    }

    private void writeFrame(Map<Channel, BitmapFrame> channels) throws IOException {
        try {
            BitmapFrame frame = channels.get(Channel.BRGA);
            ByteBuffer buffer = frame.getByteBuffer();
            int frameBytes = buffer.remaining();
            long writeStartNanos = System.nanoTime();
            writeStartedNanos = writeStartNanos;
            try {
                if (firstFrameNanos < 0) {
                    firstFrameNanos = writeStartNanos;
                }
                writeBufferToFFmpeg(buffer);
            } finally {
                writeStartedNanos = -1;
            }
            long now = System.nanoTime();
            long frameWriteNanos = now - writeStartNanos;
            framesWritten++;
            bytesWritten += frameBytes;
            writeNanos += frameWriteNanos;
            maxFrameWriteNanos = Math.max(maxFrameWriteNanos, frameWriteNanos);
            lastFrameNanos = now;
            logProgressBenchmark(now, false);
        } finally {
            releaseFrames(channels);
        }
    }

    private void runWatchdog() {
        // FFmpeg's stdin pipe holds at most a few dozen KB of buffered data on Windows. If the
        // child process stops draining its stdin (encoder init failure, broken filter graph,
        // GPU OOM, etc.), the writer thread blocks inside outputStream.write forever, the queue
        // fills, and Pipeline.run blocks the render thread on processService.submit -- which
        // looks like Minecraft freezing solid. Detect that case here and break the deadlock with
        // a clear failure instead of an unrecoverable hang.
        long pollIntervalMillis = Math.max(1000, TimeUnit.NANOSECONDS.toMillis(stallTimeoutNanos) / 6);
        while (!aborted && writerFailure == null) {
            synchronized (queueLock) {
                if (inputClosed && queuedFrames.isEmpty()) {
                    return;
                }
            }
            try {
                Thread.sleep(pollIntervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            long started = writeStartedNanos;
            if (started <= 0) {
                continue;
            }
            long elapsedNanos = System.nanoTime() - started;
            if (elapsedNanos < stallTimeoutNanos) {
                continue;
            }
            long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(elapsedNanos);
            String log = ffmpegLog.toString();
            String tail = log.length() > 1024 ? log.substring(log.length() - 1024) : log;
            IOException stall = new IOException(
                    "FFmpeg has not consumed the rendered frame for " + elapsedSeconds
                            + "s; aborting render.\nLast FFmpeg output:\n" + tail);
            LOGGER.error("FFmpeg stdin stalled for {}s; killing FFmpeg subprocess to unblock the renderer. "
                    + "Last FFmpeg output:\n{}", elapsedSeconds, tail);
            writerFailure = stall;
            aborted = true;
            try {
                process.destroyForcibly();
            } catch (Throwable ignored) {
            }
            IOUtils.closeQuietly(outputStream);
            synchronized (queueLock) {
                queueLock.notifyAll();
            }
            renderer.setFailure(stall);
            return;
        }
    }

    private void releaseFrames(Map<Channel, BitmapFrame> channels) {
        for (BitmapFrame value : channels.values()) {
            ByteBufferPool.release(value.getByteBuffer());
        }
    }

    private void writeBufferToFFmpeg(ByteBuffer buffer) throws IOException {
        int remaining = buffer.remaining();
        if (!loggedBufferMode) {
            loggedBufferMode = true;
            LOGGER.info("FFmpeg stdin write path: directBuffer={}, hasArray={}, copyBuffer={}",
                    buffer.isDirect(), buffer.hasArray(), buffer.hasArray() ? "none" : formatBytes(remaining));
        }
        if (buffer.hasArray()) {
            int position = buffer.position();
            outputStream.write(buffer.array(), buffer.arrayOffset() + position, remaining);
            buffer.position(position + remaining);
            writeCalls++;
            return;
        }
        ensureWriteBufferCapacity(remaining);
        buffer.get(writeBuffer, 0, remaining);
        outputStream.write(writeBuffer, 0, remaining);
        writeCalls++;
    }

    private void ensureWriteBufferCapacity(int size) {
        if (writeBuffer == null || writeBuffer.length < size) {
            writeBuffer = new byte[size];
        }
    }

    private void logProgressBenchmark(long nowNanos, boolean force) {
        if (framesWritten == 0) {
            return;
        }
        if (!force && nowNanos - lastBenchmarkLogNanos < BENCHMARK_LOG_INTERVAL_NANOS) {
            return;
        }
        lastBenchmarkLogNanos = nowNanos;
        long frameSpanNanos = Math.max(1, nowNanos - firstFrameNanos);
        double elapsedSeconds = nanosToSeconds(Math.max(1, nowNanos - startedNanos));
        double streamSeconds = nanosToSeconds(frameSpanNanos);
        double writeSeconds = nanosToSeconds(Math.max(1, writeNanos));
        double encodedVideoSeconds = framesWritten / (double) settings.getFramesPerSecond();
        LOGGER.info("FFmpeg benchmark: frames={}, videoTime={}, wall={}, streamFps={}, realtime={}x, rawWritten={}, avgWrite={}, maxWrite={}, writeThroughput={}/s, writeCalls={}, avgChunk={}, queueMax={}, enqueueWait={}",
                framesWritten,
                formatSeconds(encodedVideoSeconds),
                formatSeconds(elapsedSeconds),
                formatDecimal(framesWritten / streamSeconds),
                formatDecimal(encodedVideoSeconds / elapsedSeconds),
                formatBytes(bytesWritten),
                formatMillis(writeNanos / Math.max(1L, framesWritten)),
                formatMillis(maxFrameWriteNanos),
                formatBytes((long) (bytesWritten / writeSeconds)),
                writeCalls,
                formatBytes(bytesWritten / Math.max(1L, writeCalls)),
                maxQueueDepth,
                formatSeconds(nanosToSeconds(enqueueWaitNanos)));
    }

    private void logFinalBenchmark(long nowNanos, long ffmpegWaitNanos, boolean exited, int exitCode) {
        logProgressBenchmark(nowNanos, true);
        if (framesWritten == 0) {
            LOGGER.info("FFmpeg benchmark finished: no frames were written, exited={}, exitCode={}",
                    exited, exited ? String.valueOf(exitCode) : "still running after timeout");
            return;
        }
        double totalSeconds = nanosToSeconds(Math.max(1, nowNanos - startedNanos));
        double writeSeconds = nanosToSeconds(Math.max(1, writeNanos));
        double encodedVideoSeconds = framesWritten / (double) settings.getFramesPerSecond();
        LOGGER.info("FFmpeg benchmark finished: frames={}, videoTime={}, wall={}, realtime={}x, rawWritten={}, writeTime={}, writeDuty={}%, writeCalls={}, avgChunk={}, queueMax={}, enqueueWait={}, waitForFFmpeg={}, exitCode={}",
                framesWritten,
                formatSeconds(encodedVideoSeconds),
                formatSeconds(totalSeconds),
                formatDecimal(encodedVideoSeconds / totalSeconds),
                formatBytes(bytesWritten),
                formatSeconds(writeSeconds),
                formatDecimal(writeSeconds * 100.0 / totalSeconds),
                writeCalls,
                formatBytes(bytesWritten / Math.max(1L, writeCalls)),
                maxQueueDepth,
                formatSeconds(nanosToSeconds(enqueueWaitNanos)),
                formatSeconds(nanosToSeconds(ffmpegWaitNanos)),
                exited ? String.valueOf(exitCode) : "still running after timeout");
    }

    private static double nanosToSeconds(long nanos) {
        return nanos / 1_000_000_000.0;
    }

    private static String formatMillis(long nanos) {
        return String.format(Locale.ROOT, "%.3fms", nanos / 1_000_000.0);
    }

    private static String formatSeconds(double seconds) {
        return String.format(Locale.ROOT, "%.3fs", seconds);
    }

    private static String formatDecimal(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String formatBytes(long bytes) {
        double value = bytes;
        String[] units = {"B", "KiB", "MiB", "GiB"};
        int unit = 0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return String.format(Locale.ROOT, "%.2f%s", value, units[unit]);
    }

    @Override
    public boolean isParallelCapable() {
        return true;
    }

    private void checkSize(ReadableDimension size) {
        checkSize(size.getWidth(), size.getHeight());
    }

    private void checkSize(int width, int height) {
        isTrue(width == settings.getVideoWidth(), "Width has to be %d but was %d", settings.getVideoWidth(), width);
        isTrue(height == settings.getVideoHeight(), "Height has to be %d but was %d", settings.getVideoHeight(), height);
    }

    public void abort() {
        aborted = true;
    }

    private String buildCommandArgs(String executable, String fileName) {
        String args = upgradeLegacyPresetArguments(settings.getExportArguments());
        String videoFilters = getEffectiveVideoFilters();
        HardwareEncoderArgs hardwareEncoder = null;
        if (args.contains("%HARDWARE_H264%")) {
            hardwareEncoder = selectHardwareEncoder(executable, videoFilters, VideoCodec.H264);
            args = args.replace("%HARDWARE_H264%", hardwareEncoder.args);
        } else if (args.contains("%HARDWARE_HEVC%")) {
            hardwareEncoder = selectHardwareEncoder(executable, videoFilters, VideoCodec.HEVC);
            args = args.replace("%HARDWARE_HEVC%", hardwareEncoder.args);
        } else if (args.contains("%HARDWARE_AV1%")) {
            hardwareEncoder = selectHardwareEncoder(executable, videoFilters, VideoCodec.AV1);
            args = args.replace("%HARDWARE_AV1%", hardwareEncoder.args);
        }
        args = addThreadLimitForCpuEncoders(args);
        return args
                .replace("%WIDTH%", String.valueOf(settings.getVideoWidth()))
                .replace("%HEIGHT%", String.valueOf(settings.getVideoHeight()))
                .replace("%FPS%", String.valueOf(settings.getFramesPerSecond()))
                .replace("%FILENAME%", fileName)
                .replace("%BITRATE%", String.valueOf(settings.getBitRate()))
                .replace("%THREADS%", String.valueOf(Math.max(1, settings.getEncoderThreadCount())))
                .replace("%FILTERS%", hardwareEncoder != null && hardwareEncoder.consumesFilters
                        ? ""
                        : videoFilters);
    }

    private String getEffectiveVideoFilters() {
        if (!inputNeedsVerticalFlip) {
            return settings.getVideoFilters();
        }
        String filters = appendVideoFilter(settings.getVideoFilters(), "vflip");
        LOGGER.info("Using FFmpeg vertical flip filter for raw OpenGL frame fast path: {}", filters.trim());
        return filters;
    }

    private String appendVideoFilter(String filters, String filter) {
        String filterChain = extractFilterChain(filters);
        if (filterChain.isEmpty()) {
            filterChain = filter;
        } else {
            filterChain += "," + filter;
        }
        return "-filter:v " + filterChain + " ";
    }

    private String upgradeLegacyPresetArguments(String args) {
        if (LEGACY_MP4_CUSTOM_ARGS.equals(args)) {
            return RenderSettings.EncodingPreset.MP4_HARDWARE.getValue();
        }
        if (LEGACY_WEBM_CUSTOM_ARGS.equals(args)) {
            return RenderSettings.EncodingPreset.WEBM_CUSTOM.getValue();
        }
        return args;
    }

    private String addThreadLimitForCpuEncoders(String args) {
        String lowerArgs = args.toLowerCase(Locale.ROOT);
        if (lowerArgs.contains("-threads")
                || (!lowerArgs.contains("libx264")
                    && !lowerArgs.contains("libvpx")
                    && !lowerArgs.contains("libx265")
                    && !lowerArgs.contains("libsvtav1")
                    && !lowerArgs.contains("libaom-av1"))) {
            return args;
        }
        int outputIndex = args.lastIndexOf("\"%FILENAME%\"");
        if (outputIndex < 0) {
            outputIndex = args.lastIndexOf("%FILENAME%");
        }
        String threads = "-threads %THREADS% ";
        if (outputIndex < 0) {
            return args + " " + threads;
        }
        return args.substring(0, outputIndex) + threads + args.substring(outputIndex);
    }

    private HardwareEncoderArgs selectHardwareEncoder(String executable, String filters, VideoCodec codec) {
        String prefix = codec.prefix;
        List<String> hwaccels = queryFFmpegFeatures(executable, "-hwaccels",
                new String[]{"cuda", "d3d11va", "dxva2", "qsv", "vaapi", "vulkan", "opencl", "videotoolbox"});
        List<String> encoders = queryFFmpegFeatures(executable, "-encoders",
                new String[]{prefix + "_nvenc", prefix + "_vaapi", prefix + "_qsv", prefix + "_amf", prefix + "_videotoolbox"});
        List<String> gpuFilters = queryFFmpegFeatures(executable, "-filters",
                new String[]{"hwupload", "hwupload_cuda", "hwmap", "scale_cuda", "scale_vaapi", "scale_qsv"});
        LOGGER.info("FFmpeg hardware acceleration methods: {}", hwaccels.isEmpty() ? "none detected" : hwaccels);
        LOGGER.info("FFmpeg hardware {} encoders: {}", codec.label, encoders.isEmpty() ? "none detected" : encoders);
        LOGGER.info("FFmpeg GPU upload/map filters: {}", gpuFilters.isEmpty() ? "none detected" : gpuFilters);
        LOGGER.info("ReplayMod currently feeds FFmpeg via rawvideo stdin, so hardware encoders still require a CPU-to-GPU upload. Zero-copy OpenGL texture handoff is not available through this FFmpeg CLI path.");

        String tagSuffix = codec.mp4Tag != null ? " -tag:v " + codec.mp4Tag : "";
        boolean speedProfile = isHwSpeedProfile();
        // p5 + hq tune + spatial/temporal AQ roughly matches `libx264 -preset
        // veryfast` quality at the same bitrate while still running noticeably
        // faster than libx264 on Turing+ NVENC. We omit `-multipass fullres`
        // deliberately: it is a Turing-era option and older drivers reject the
        // encoder init outright instead of ignoring it.
        //
        // H.264 NVENC supports B-frames on every NVENC-capable GPU; HEVC NVENC
        // only supports them on Turing+ and Pascal drivers fail init when asked
        // for HEVC B-frames, so we keep -bf 0 on the HEVC path.
        String bFrameOpt = (codec == VideoCodec.H264) ? " -bf 3" : "";
        String nvencTuning = speedProfile
                ? "-preset p1"
                : "-preset p5 -tune hq -rc vbr -spatial-aq 1 -temporal-aq 1" + bFrameOpt;
        String encoder = null;
        boolean consumesFilters = false;
        if (encoders.contains(prefix + "_nvenc")) {
            String nvencName = prefix + "_nvenc";
            String filterChain = extractFilterChain(filters);
            // Default to the long-standing CUDA NV12 upload path. The BGRA-direct fast path skips
            // the format= + hwupload_cuda filter, but the resulting filter graph (especially
            // sw vflip -> bgra -> nvenc at 4K/120) has been observed to stall Minecraft on first
            // frame on some FFmpeg + driver combinations. Enable it explicitly via property/env
            // when the user wants the optimisation.
            boolean wantBgra = isNvencBgraEnabled();
            boolean nvencAcceptsBgra = wantBgra && encoderSupportsPixelFormat(executable, nvencName, "bgra");
            if (nvencAcceptsBgra) {
                if (filterChain.isEmpty()) {
                    LOGGER.info("Using {} direct BGRA input path (opt-in); skipping CPU BGRA-to-NV12 filter before GPU upload.", nvencName);
                    encoder = "-c:v " + nvencName + " " + nvencTuning + " -pix_fmt bgra";
                } else {
                    LOGGER.info("Using {} BGRA input path with software filter chain (opt-in): {}", nvencName, filterChain);
                    encoder = "-filter:v " + filterChain + " -c:v " + nvencName + " " + nvencTuning + " -pix_fmt bgra";
                    consumesFilters = true;
                }
            } else {
                if (wantBgra) {
                    LOGGER.info("Requested {} BGRA input path but the encoder does not advertise bgra; falling back to CUDA NV12 upload.", nvencName);
                } else {
                    LOGGER.info("Using {} via CUDA NV12 upload (set -D{}=true to enable the BGRA fast path).",
                            nvencName, NVENC_BGRA_PROPERTY);
                }
                encoder = buildCudaUploadFilter(filters) + "-c:v " + nvencName + " " + nvencTuning;
                consumesFilters = true;
            }
        } else if (encoders.contains(prefix + "_vaapi")) {
            // VAAPI: drop the bare `-qp 23` (ignored bitrate target, drifts low at high motion)
            // in favour of a VBR rate-control aimed at the configured target bitrate.
            String vaapiTuning = speedProfile
                    ? "-rc_mode CBR"
                    : "-rc_mode VBR -compression_level 1";
            encoder = "-vaapi_device /dev/dri/renderD128 " + buildVaapiUploadFilter(filters)
                    + "-c:v " + prefix + "_vaapi " + vaapiTuning;
            consumesFilters = true;
        } else if (encoders.contains(prefix + "_qsv")) {
            // QSV: `veryfast` is the lowest-quality preset; `slower` keeps the GPU pipeline
            // saturated while matching libx264 veryfast quality at the same bitrate.
            encoder = "-c:v " + prefix + "_qsv -preset " + (speedProfile ? "veryfast" : "slower");
        } else if (encoders.contains(prefix + "_amf")) {
            // AMF: `speed` is the lowest-quality knob; `balanced` is the recommended
            // offline-encoding profile for the AMD media engine.
            encoder = "-c:v " + prefix + "_amf -quality " + (speedProfile ? "speed" : "balanced");
        } else if (encoders.contains(prefix + "_videotoolbox")) {
            encoder = "-c:v " + prefix + "_videotoolbox";
        }
        if (encoder == null) {
            LOGGER.warn("No supported hardware {} encoder found; falling back to {}.", codec.label, codec.cpuFallback);
            return new HardwareEncoderArgs(
                    "-c:v " + codec.cpuFallback + " -threads %THREADS% -b:v %BITRATE% -pix_fmt yuv420p" + tagSuffix,
                    false);
        }
        LOGGER.info("Using FFmpeg hardware encoder: {}", encoder);
        return new HardwareEncoderArgs(encoder + " -b:v %BITRATE%" + tagSuffix, consumesFilters);
    }

    private String buildCudaUploadFilter(String filters) {
        String filterChain = extractFilterChain(filters);
        if (filterChain.isEmpty()) {
            filterChain = "format=nv12,hwupload_cuda";
        } else {
            filterChain += ",format=nv12,hwupload_cuda";
        }
        LOGGER.info("Using CUDA upload/filter chain for NVENC: {}", filterChain);
        return "-filter:v " + filterChain + " ";
    }

    private String buildVaapiUploadFilter(String filters) {
        String filterChain = extractFilterChain(filters);
        if (filterChain.isEmpty()) {
            filterChain = "format=nv12,hwupload";
        } else {
            filterChain += ",format=nv12,hwupload";
        }
        LOGGER.info("Using VAAPI upload/filter chain: {}", filterChain);
        return "-filter:v " + filterChain + " ";
    }

    private String extractFilterChain(String filters) {
        String trimmed = filters == null ? "" : filters.trim();
        if (trimmed.startsWith("-filter:v ")) {
            return trimmed.substring("-filter:v ".length()).trim();
        }
        if (trimmed.startsWith("-vf ")) {
            return trimmed.substring("-vf ".length()).trim();
        }
        return "";
    }

    private static class HardwareEncoderArgs {
        private final String args;
        private final boolean consumesFilters;

        private HardwareEncoderArgs(String args, boolean consumesFilters) {
            this.args = args;
            this.consumesFilters = consumesFilters;
        }
    }

    private enum VideoCodec {
        H264("h264", "H.264", "libx264 -preset veryfast", null),
        HEVC("hevc", "HEVC", "libx265 -preset veryfast", "hvc1"),
        AV1("av1", "AV1", "libsvtav1 -preset 8", null);

        private final String prefix;
        private final String label;
        private final String cpuFallback;
        private final String mp4Tag;

        VideoCodec(String prefix, String label, String cpuFallback, String mp4Tag) {
            this.prefix = prefix;
            this.label = label;
            this.cpuFallback = cpuFallback;
            this.mp4Tag = mp4Tag;
        }
    }

    private List<String> queryFFmpegFeatures(String executable, String argument, String[] candidates) {
        List<String> features = new ArrayList<>();
        Process ffmpegProcess = null;
        try {
            ffmpegProcess = new ProcessBuilder(executable, "-hide_banner", argument)
                    .redirectErrorStream(true)
                    .start();
            ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
            IOUtils.copy(ffmpegProcess.getInputStream(), output);
            if (!ffmpegProcess.waitFor(3, TimeUnit.SECONDS)) {
                ffmpegProcess.destroy();
                return features;
            }
            String text = output.toString("UTF-8").toLowerCase(Locale.ROOT);
            for (String candidate : candidates) {
                if (text.contains(candidate)) {
                    features.add(candidate);
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("Failed to query FFmpeg {}:", argument, t);
        } finally {
            if (ffmpegProcess != null) {
                ffmpegProcess.destroy();
            }
        }
        return features;
    }

    private boolean encoderSupportsPixelFormat(String executable, String encoder, String pixelFormat) {
        Process ffmpegProcess = null;
        try {
            ffmpegProcess = new ProcessBuilder(executable, "-hide_banner", "-h", "encoder=" + encoder)
                    .redirectErrorStream(true)
                    .start();
            ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
            IOUtils.copy(ffmpegProcess.getInputStream(), output);
            if (!ffmpegProcess.waitFor(3, TimeUnit.SECONDS)) {
                ffmpegProcess.destroy();
                return false;
            }
            String text = output.toString("UTF-8").toLowerCase(Locale.ROOT);
            return text.contains(pixelFormat.toLowerCase(Locale.ROOT));
        } catch (Throwable t) {
            LOGGER.debug("Failed to query FFmpeg encoder {} pixel formats:", encoder, t);
            return false;
        } finally {
            if (ffmpegProcess != null) {
                ffmpegProcess.destroy();
            }
        }
    }

    public File getVideoFile() throws FFmpegStartupException {
        waitForFFmpegLog();
        String log = ffmpegLog.toString();
        for (String line : log.split("\n")) {
            if (line.startsWith("Output #0")) {
                String fileName = line.substring(line.indexOf(", to '") + 6, line.lastIndexOf('\''));
                return new File(settings.getOutputFile().getParentFile(), fileName);
            }
        }
        throw new FFmpegStartupException(settings, log);
    }

    private void waitForFFmpegLog() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        int previousSize = -1;
        while (System.nanoTime() < deadline) {
            int size = ffmpegLog.size();
            if (size > 0 && size == previousSize) {
                return;
            }
            previousSize = size;
            try {
                process.exitValue();
                if (size > 0) {
                    return;
                }
            } catch (IllegalThreadStateException ignored) {
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public static class NoFFmpegException extends IOException {
        public NoFFmpegException(Throwable cause) {
            super(cause);
        }
    }

    public static class FFmpegStartupException extends IOException {
        private final RenderSettings settings;
        private final String log;

        public FFmpegStartupException(RenderSettings settings, String log) {
            super(log);
            this.settings = settings;
            this.log = log;
        }

        public RenderSettings getSettings() {
            return settings;
        }

        public String getLog() {
            return log;
        }
    }
}
