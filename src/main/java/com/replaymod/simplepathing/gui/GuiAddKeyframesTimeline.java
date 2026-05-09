package com.replaymod.simplepathing.gui;

import com.replaymod.replay.ReplayHandler;
import com.replaymod.replay.ReplayModReplay;
import com.replaymod.simplepathing.SPTimeline;
import de.johni0702.minecraft.gui.GuiRenderer;
import de.johni0702.minecraft.gui.RenderInfo;
import de.johni0702.minecraft.gui.container.GuiPanel;
import de.johni0702.minecraft.gui.element.GuiButton;
import de.johni0702.minecraft.gui.element.GuiLabel;
import de.johni0702.minecraft.gui.element.advanced.AbstractGuiTimeline;
import de.johni0702.minecraft.gui.function.Click;
import de.johni0702.minecraft.gui.function.KeyHandler;
import de.johni0702.minecraft.gui.function.KeyInput;
import de.johni0702.minecraft.gui.layout.HorizontalLayout;
import de.johni0702.minecraft.gui.layout.VerticalLayout;
import de.johni0702.minecraft.gui.popup.AbstractGuiPopup;
import de.johni0702.minecraft.gui.utils.Colors;
import de.johni0702.minecraft.gui.utils.lwjgl.Dimension;
import de.johni0702.minecraft.gui.utils.lwjgl.ReadableDimension;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.TreeSet;

/**
 * Click-to-place replacement for the row-based "Add Keyframes" popup: opens a wide
 * timeline of the replay (the "loaded time axis" the user asked for) and lets them mark
 * any number of positions in one go. Apply commits them as keyframes.
 *
 * Existing camera-path keyframes are drawn faintly so the user can see what's already
 * there. Pending markers are drawn in green; left-click adds, right-click removes the
 * nearest pending marker within snap distance.
 *
 * Marker → keyframe mapping uses the clicked replay timestamp as both the camera-path
 * position and (for TIME mode) the replay time, giving a 1:1 sync that the user can
 * fine-tune later via the standard keyframe editor. Slow-mo / fast-fwd time mappings
 * (the old row popup could set non-1:1 in one shot) now require per-keyframe edits.
 */
public class GuiAddKeyframesTimeline extends AbstractGuiPopup<GuiAddKeyframesTimeline> implements KeyHandler {
    private static final Logger LOGGER = LogManager.getLogger();

    public enum Mode { TIME, POSITION }

    private final Mode mode;
    private final SPTimeline timeline;
    private final int replayDurationMs;
    private final int currentReplayMs;

    // Snapshotted once; every position keyframe placed by this popup uses the same anchor.
    private final double camX, camY, camZ;
    private final float camYaw, camPitch, camRoll;
    private final int camSpectatedId;

    private final TreeSet<Long> pendingMarkers = new TreeSet<>();

    // Popup panels render with a light background, so titles/labels need explicit black
    // text — leaving them on the default white made them invisible (matched popup chrome).
    private final GuiLabel title = new GuiLabel().setColor(Colors.BLACK);
    private final GuiLabel hint = new GuiLabel()
            .setText("Left-click: add  ·  Right-click: remove")
            .setColor(Colors.BLACK);
    private final GuiLabel counter = new GuiLabel().setColor(Colors.BLACK);

    private final MarkerTimeline markerTimeline = new MarkerTimeline();

    private final GuiButton applyButton = new GuiButton()
            .setSize(100, 20)
            .setI18nLabel("replaymod.gui.addkeyframes.apply");
    private final GuiButton clearButton = new GuiButton()
            .setSize(100, 20)
            .setLabel("Clear");
    private final GuiButton cancelButton = new GuiButton()
            .setSize(100, 20)
            .setI18nLabel("replaymod.gui.cancel");

    private final GuiPanel buttons = new GuiPanel()
            .setLayout(new HorizontalLayout(HorizontalLayout.Alignment.CENTER).setSpacing(6))
            .addElements(new HorizontalLayout.Data(0.5), applyButton, clearButton, cancelButton);

    {
        setBackgroundColor(Colors.DARK_TRANSPARENT);
        popup.setLayout(new VerticalLayout().setSpacing(8))
                .addElements(new VerticalLayout.Data(0.5, false), title, markerTimeline, hint, counter, buttons);
    }

    public static GuiAddKeyframesTimeline openTime(GuiPathing guiPathing, int defaultReplayMs) {
        ReplayHandler handler = ReplayModReplay.instance.getReplayHandler();
        if (handler == null) return null;
        SPTimeline timeline = guiPathing.getMod().getCurrentTimeline();
        if (timeline == null) return null;
        return new GuiAddKeyframesTimeline(Mode.TIME, timeline, handler, defaultReplayMs);
    }

    public static GuiAddKeyframesTimeline openPosition(GuiPathing guiPathing, int defaultReplayMs) {
        ReplayHandler handler = ReplayModReplay.instance.getReplayHandler();
        if (handler == null) return null;
        if (handler.getCameraEntity() == null) {
            LOGGER.warn("Refusing to open Add Position Keyframes timeline: no camera entity.");
            return null;
        }
        SPTimeline timeline = guiPathing.getMod().getCurrentTimeline();
        if (timeline == null) return null;
        return new GuiAddKeyframesTimeline(Mode.POSITION, timeline, handler, defaultReplayMs);
    }

    private GuiAddKeyframesTimeline(Mode mode, SPTimeline timeline, ReplayHandler handler, int currentReplayMs) {
        super(handler.getOverlay());
        this.mode = mode;
        this.timeline = timeline;
        this.replayDurationMs = Math.max(1000, handler.getReplayDuration());
        this.currentReplayMs = Math.max(0, Math.min(currentReplayMs, replayDurationMs));

        if (mode == Mode.POSITION) {
            com.replaymod.replay.camera.CameraEntity camera = handler.getCameraEntity();
            this.camX = camera.getX();
            this.camY = camera.getY();
            this.camZ = camera.getZ();
            this.camYaw = camera.yaw;
            this.camPitch = camera.pitch;
            this.camRoll = camera.roll;
            this.camSpectatedId = -1;
        } else {
            this.camX = 0d;
            this.camY = 0d;
            this.camZ = 0d;
            this.camYaw = 0f;
            this.camPitch = 0f;
            this.camRoll = 0f;
            this.camSpectatedId = -1;
        }

        title.setI18nText(mode == Mode.TIME
                ? "replaymod.gui.addkeyframes.title.time"
                : "replaymod.gui.addkeyframes.title.position");

        markerTimeline.setLength(replayDurationMs);
        markerTimeline.setCursorPosition(this.currentReplayMs);
        markerTimeline.setMarkers(true);

        applyButton.onClick(this::applyMarkers);
        clearButton.onClick(() -> {
            pendingMarkers.clear();
            updateCounter();
        });
        cancelButton.onClick(this::close);

        updateCounter();
        open();
    }

    private void updateCounter() {
        counter.setText("Pending: " + pendingMarkers.size());
    }

    // Snap tolerance for the right-click "remove nearest" gesture and for left-click
    // dedupe. Fixed in ms — scaling with replay duration made it nonsensically large
    // for long recordings (e.g. 18s on a 1h replay) and prevented dense placement.
    private static final long SNAP_TOLERANCE_MS = 300L;

    private void addMarker(long timeMs) {
        if (timeMs < 0) return;
        // Skip duplicate placement within snap distance so a slightly-off second click
        // doesn't pile two markers visually on top of each other.
        if (nearestMarkerWithin(timeMs, SNAP_TOLERANCE_MS) != null) return;
        pendingMarkers.add(timeMs);
        updateCounter();
    }

    private void removeNearestMarker(long timeMs) {
        Long nearest = nearestMarkerWithin(timeMs, SNAP_TOLERANCE_MS);
        if (nearest != null) {
            pendingMarkers.remove(nearest);
            updateCounter();
        }
    }

    private Long nearestMarkerWithin(long timeMs, long toleranceMs) {
        Long floor = pendingMarkers.floor(timeMs);
        Long ceil = pendingMarkers.ceiling(timeMs);
        Long best = null;
        long bestDist = Long.MAX_VALUE;
        if (floor != null) {
            long d = timeMs - floor;
            if (d <= toleranceMs) { best = floor; bestDist = d; }
        }
        if (ceil != null) {
            long d = ceil - timeMs;
            if (d <= toleranceMs && d < bestDist) { best = ceil; }
        }
        return best;
    }

    private void applyMarkers() {
        if (pendingMarkers.isEmpty()) {
            close();
            return;
        }
        int added = 0;
        int skippedExisting = 0;
        for (long marker : pendingMarkers) {
            switch (mode) {
                case TIME:
                    if (timeline.isTimeKeyframe(marker)) {
                        skippedExisting++;
                    } else {
                        // 1:1 default: camera-path position == clicked replay time, replay-time
                        // == clicked replay time. This makes "mark a moment in the replay" the
                        // primary action and gives a real-time camera path. Slow-mo / fast-fwd
                        // paths now require editing each keyframe afterwards — the old row popup
                        // could set non-1:1 in one shot, this UX trades that for click-to-place.
                        timeline.addTimeKeyframe(marker, (int) marker);
                        added++;
                    }
                    break;
                case POSITION:
                    if (timeline.isPositionKeyframe(marker)) {
                        skippedExisting++;
                    } else {
                        timeline.addPositionKeyframe(marker, camX, camY, camZ,
                                camYaw, camPitch, camRoll, camSpectatedId);
                        added++;
                    }
                    break;
            }
        }
        LOGGER.info("GuiAddKeyframesTimeline ({}): added {}, skipped {} existing", mode, added, skippedExisting);
        close();
    }

    @Override
    public boolean handleKey(KeyInput keyInput) {
        if (keyInput.isEscape()) {
            close();
            return true;
        }
        return false;
    }

    @Override
    protected GuiAddKeyframesTimeline getThis() {
        return this;
    }

    /**
     * Custom timeline with click-to-toggle marker placement. Existing camera-path keyframes
     * are drawn underneath as a hint so the user knows where collisions would happen.
     */
    private class MarkerTimeline extends AbstractGuiTimeline<MarkerTimeline> {
        // Arbitrary minimum that fits comfortably on most screens; popup will get scaled by jGui's
        // layout but this sets the floor so we don't end up with a too-thin strip on small windows.
        private static final int PREFERRED_WIDTH = 480;
        private static final int HEIGHT = 22;
        private static final int MARKER_HALF_WIDTH = 3;

        @Override
        public ReadableDimension calcMinSize() {
            return new Dimension(PREFERRED_WIDTH, HEIGHT);
        }

        @Override
        public void draw(GuiRenderer renderer, ReadableDimension size, RenderInfo renderInfo) {
            super.draw(renderer, size, renderInfo);
            drawExistingKeyframes(renderer, size);
            drawPendingMarkers(renderer, size);
            // Re-draw the playhead cursor on top so it's not covered by markers.
            drawTimelineCursor(renderer, size);
        }

        private void drawExistingKeyframes(GuiRenderer renderer, ReadableDimension size) {
            int width = size.getWidth();
            int bodyWidth = width - BORDER_LEFT - BORDER_RIGHT;
            int visibleLength = (int) (getZoom() * getLength());
            int startTime = getOffset();
            if (visibleLength <= 0 || bodyWidth <= 0) return;

            for (com.replaymod.replaystudio.pathing.path.Path path : timeline.getTimeline().getPaths()) {
                for (com.replaymod.replaystudio.pathing.path.Keyframe kf : path.getKeyframes()) {
                    long t = kf.getTime();
                    if (t < startTime || t > startTime + visibleLength) continue;
                    int x = BORDER_LEFT + (int) ((t - startTime) / (double) visibleLength * bodyWidth);
                    // Two faint stripes so position vs time keyframes are distinguishable from the
                    // pending green ticks; they don't need to scream for attention.
                    renderer.drawRect(x, BORDER_TOP, 1, size.getHeight() - BORDER_TOP - BORDER_BOTTOM, 0x55FFFFFF);
                }
            }
        }

        private void drawPendingMarkers(GuiRenderer renderer, ReadableDimension size) {
            int width = size.getWidth();
            int bodyWidth = width - BORDER_LEFT - BORDER_RIGHT;
            int visibleLength = (int) (getZoom() * getLength());
            int startTime = getOffset();
            if (visibleLength <= 0 || bodyWidth <= 0) return;

            int top = BORDER_TOP;
            int bottom = size.getHeight() - BORDER_BOTTOM;
            int markerColor = 0xFF55FF55;
            for (Long marker : pendingMarkers) {
                if (marker < startTime || marker > startTime + visibleLength) continue;
                int x = BORDER_LEFT + (int) ((marker - startTime) / (double) visibleLength * bodyWidth);
                renderer.drawRect(x - MARKER_HALF_WIDTH, top, MARKER_HALF_WIDTH * 2 + 1, 2, markerColor);
                renderer.drawRect(x, top, 1, bottom - top, markerColor);
                renderer.drawRect(x - MARKER_HALF_WIDTH, bottom - 2, MARKER_HALF_WIDTH * 2 + 1, 2, markerColor);
            }
        }

        @Override
        public boolean mouseClick(Click click) {
            int time = getTimeAt(click.x, click.y);
            if (time == -1) return false;
            if (click.button == 1) {
                removeNearestMarker(time);
            } else {
                addMarker(time);
            }
            return true;
        }

        @Override
        protected MarkerTimeline getThis() {
            return this;
        }
    }
}
