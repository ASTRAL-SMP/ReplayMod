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
    private final GuiPathing guiPathing;
    private final ReplayHandler handler;
    private final int replayDurationMs;
    private final int currentReplayMs;

    // Snapshotted once; every position keyframe placed by this popup uses the same anchor.
    private final double camX, camY, camZ;
    private final float camYaw, camPitch, camRoll;
    private final int camSpectatedId;

    private final TreeSet<Long> pendingMarkers = new TreeSet<>();
    /**
     * Existing keyframes (of this popup's mode) the user has scheduled for deletion.
     * Right-click on an existing keyframe toggles inclusion; Apply removes them as part
     * of the same commit as the additions.
     */
    private final TreeSet<Long> pendingDeletions = new TreeSet<>();

    // Popup panels render with a light background, so titles/labels need explicit black
    // text — leaving them on the default white made them invisible (matched popup chrome).
    private final GuiLabel title = new GuiLabel().setColor(Colors.BLACK);
    private final GuiLabel hint = new GuiLabel()
            .setText("Left-click: add  ·  Right-click: remove pending or mark existing for delete")
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
        return new GuiAddKeyframesTimeline(Mode.TIME, timeline, guiPathing, handler, defaultReplayMs);
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
        return new GuiAddKeyframesTimeline(Mode.POSITION, timeline, guiPathing, handler, defaultReplayMs);
    }

    private GuiAddKeyframesTimeline(Mode mode, SPTimeline timeline, GuiPathing guiPathing, ReplayHandler handler, int currentReplayMs) {
        super(handler.getOverlay());
        this.mode = mode;
        this.timeline = timeline;
        this.guiPathing = guiPathing;
        this.handler = handler;
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

        // Pre-seed a pending marker at the playhead so pressing I/O → Apply (with no
        // extra clicks) reproduces the old-style "toggle keyframe at current playhead"
        // behaviour. This is what users actually mean when they hit the hotkey at a
        // moment they want to mark; the click-anywhere UI is a superset, not a
        // replacement. Right-click the cursor marker to drop it if a different intent.
        if (mode == Mode.TIME && !timeline.isTimeKeyframe(this.currentReplayMs)) {
            pendingMarkers.add((long) this.currentReplayMs);
        } else if (mode == Mode.POSITION && !timeline.isPositionKeyframe(this.currentReplayMs)) {
            pendingMarkers.add((long) this.currentReplayMs);
        }

        applyButton.onClick(this::applyMarkers);
        clearButton.onClick(() -> {
            pendingMarkers.clear();
            pendingDeletions.clear();
            updateCounter();
        });
        cancelButton.onClick(this::close);

        updateCounter();
        open();
    }

    private void updateCounter() {
        if (pendingDeletions.isEmpty()) {
            counter.setText("Pending: " + pendingMarkers.size());
        } else {
            counter.setText("Pending: " + pendingMarkers.size() + " add, " + pendingDeletions.size() + " delete");
        }
    }

    /**
     * Returns the timestamps of every existing keyframe of this popup's mode (time or
     * position). Each value is the keyframe's path-video time, which is also what we
     * pass to {@link SPTimeline#removeTimeKeyframe}/{@link SPTimeline#removePositionKeyframe}.
     */
    private java.util.List<Long> existingKeyframeTimes() {
        com.replaymod.replaystudio.pathing.path.Path path = mode == Mode.TIME
                ? timeline.getTimePath()
                : timeline.getPositionPath();
        java.util.List<Long> out = new java.util.ArrayList<>();
        for (com.replaymod.replaystudio.pathing.path.Keyframe kf : path.getKeyframes()) {
            out.add(kf.getTime());
        }
        return out;
    }

    private Long nearestExistingKeyframe(long timeMs, long toleranceMs) {
        Long best = null;
        long bestDist = Long.MAX_VALUE;
        for (long t : existingKeyframeTimes()) {
            long d = Math.abs(t - timeMs);
            if (d <= toleranceMs && d < bestDist) {
                best = t;
                bestDist = d;
            }
        }
        return best;
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

    /**
     * Right-click handler. Order: pending marker first, then existing keyframe.
     * - Pending green marker within snap → just drop it (cancels a stray add).
     * - Existing keyframe within snap     → toggle delete-mark (apply will remove it).
     * Lets the user fix mistakes and prune existing keyframes through the same UI,
     * which is the whole point of routing all keyframe ops through this popup.
     */
    private void removeNearestMarker(long timeMs) {
        Long nearest = nearestMarkerWithin(timeMs, SNAP_TOLERANCE_MS);
        if (nearest != null) {
            pendingMarkers.remove(nearest);
            updateCounter();
            return;
        }
        Long existing = nearestExistingKeyframe(timeMs, SNAP_TOLERANCE_MS);
        if (existing != null) {
            if (!pendingDeletions.add(existing)) {
                // Already marked → toggle off so users can back out of the deletion.
                pendingDeletions.remove(existing);
            }
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
        if (pendingMarkers.isEmpty() && pendingDeletions.isEmpty()) {
            close();
            return;
        }
        // Process deletions FIRST so a "delete + re-add at the same time" round-trip works
        // (otherwise the add hits the still-existing keyframe and SPTimeline.addTimeKeyframe
        // throws "Keyframe already exists"). Iterate descending so removing a keyframe
        // doesn't shift indices of remaining ones (paths are time-keyed, but stable).
        int removed = 0;
        for (long t : pendingDeletions.descendingSet()) {
            try {
                if (mode == Mode.TIME) {
                    if (timeline.isTimeKeyframe(t)) {
                        timeline.removeTimeKeyframe(t);
                        removed++;
                    }
                } else {
                    if (timeline.isPositionKeyframe(t)) {
                        timeline.removePositionKeyframe(t);
                        removed++;
                    }
                }
            } catch (Throwable ex) {
                // Don't abort the whole apply if one removal fails — the user explicitly
                // asked for these operations and partial completion is better than nothing.
                LOGGER.warn("GuiAddKeyframesTimeline ({}): failed to remove keyframe at {}: {}",
                        mode, t, ex.toString());
            }
        }

        int added = 0;
        int skippedExisting = 0;
        long maxMarker = pendingMarkers.isEmpty() ? 0L : pendingMarkers.last();
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
        // The keyframe-editor timeline's visible length is set from
        // Setting.TIMELINE_LENGTH (default 30 min). With the new "click anywhere on the
        // replay" UI, users can place keyframes at replay times beyond that — the
        // keyframe IS in the data model, but it's drawn off-screen, which looks like
        // "the 2nd keyframe didn't get added." Extend the editor timeline to fit the
        // furthest marker so every just-applied keyframe is visible.
        if (added > 0 && guiPathing != null && guiPathing.timeline != null) {
            long needed = maxMarker + 1000L; // small buffer so the keyframe isn't right at the edge
            if (needed > guiPathing.timeline.getLength()) {
                guiPathing.timeline.setLength((int) Math.min(Integer.MAX_VALUE, needed));
            }
        }
        LOGGER.info("GuiAddKeyframesTimeline ({}): added {}, removed {}, skipped {} existing",
                mode, added, removed, skippedExisting);

        // The user's mental model is "I am marking THIS moment for a keyframe"; if the
        // playhead doesn't actually advance to the keyframe's timestamp afterwards, the
        // world they're looking at doesn't match where they just placed the keyframe and
        // the placement looks "wrong" even though the data is correct. Jumping here also
        // forces the replay sender to load the chunks/state at that timestamp — the
        // "force-load nearby" the user asked for. We aim at the largest marker so the
        // sender ends up no earlier than the rightmost just-placed keyframe.
        if ((added > 0 || removed > 0) && handler != null) {
            long target;
            if (!pendingMarkers.isEmpty()) {
                target = pendingMarkers.last();
            } else {
                target = pendingDeletions.last();
            }
            int clamped = (int) Math.min(Integer.MAX_VALUE, Math.max(0, target));
            try {
                handler.getReplaySender().jumpToTime(clamped);
            } catch (Throwable ex) {
                LOGGER.warn("GuiAddKeyframesTimeline: jump to {} after apply failed: {}",
                        clamped, ex.toString());
            }
        }
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

            com.replaymod.replaystudio.pathing.path.Path ownPath = mode == Mode.TIME
                    ? timeline.getTimePath()
                    : timeline.getPositionPath();

            for (com.replaymod.replaystudio.pathing.path.Path path : timeline.getTimeline().getPaths()) {
                boolean own = path == ownPath;
                for (com.replaymod.replaystudio.pathing.path.Keyframe kf : path.getKeyframes()) {
                    long t = kf.getTime();
                    if (t < startTime || t > startTime + visibleLength) continue;
                    int x = BORDER_LEFT + (int) ((t - startTime) / (double) visibleLength * bodyWidth);
                    int top = BORDER_TOP;
                    int bottom = size.getHeight() - BORDER_BOTTOM;
                    if (own && pendingDeletions.contains(t)) {
                        // Solid red stripe + cross-bars marking "this will be removed on Apply".
                        // Right-clicking again toggles it back off.
                        int del = 0xFFFF4444;
                        renderer.drawRect(x - MARKER_HALF_WIDTH, top, MARKER_HALF_WIDTH * 2 + 1, 2, del);
                        renderer.drawRect(x, top, 1, bottom - top, del);
                        renderer.drawRect(x - MARKER_HALF_WIDTH, bottom - 2, MARKER_HALF_WIDTH * 2 + 1, 2, del);
                    } else if (own) {
                        // Existing keyframe of THIS popup's mode — draw brighter so the user sees
                        // it as something they can right-click to delete.
                        renderer.drawRect(x, top, 1, bottom - top, 0xCCFFFFFF);
                    } else {
                        // Other-mode keyframe (e.g. position keyframe on a TIME popup) — faint
                        // hint only, not selectable.
                        renderer.drawRect(x, top, 1, bottom - top, 0x55FFFFFF);
                    }
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
