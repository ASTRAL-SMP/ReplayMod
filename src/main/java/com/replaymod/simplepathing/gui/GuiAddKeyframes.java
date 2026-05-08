package com.replaymod.simplepathing.gui;

import com.replaymod.replay.ReplayHandler;
import com.replaymod.replay.ReplayModReplay;
import com.replaymod.simplepathing.SPTimeline;
import com.replaymod.simplepathing.SPTimeline.SPPath;
import de.johni0702.minecraft.gui.container.GuiPanel;
import de.johni0702.minecraft.gui.element.GuiButton;
import de.johni0702.minecraft.gui.element.GuiLabel;
import de.johni0702.minecraft.gui.element.GuiNumberField;
import de.johni0702.minecraft.gui.function.KeyHandler;
import de.johni0702.minecraft.gui.function.KeyInput;
import de.johni0702.minecraft.gui.layout.HorizontalLayout;
import de.johni0702.minecraft.gui.layout.VerticalLayout;
import de.johni0702.minecraft.gui.popup.AbstractGuiPopup;
import de.johni0702.minecraft.gui.utils.Colors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Popup that lets the user enter multiple timeline positions in one go and emits
 * one keyframe per row. Decoupled from {@link GuiPathing#toggleKeyframe} so it
 * doesn't pull in the spectator/entity-tracker prompt path — it just calls
 * {@link SPTimeline#addTimeKeyframe} / {@link SPTimeline#addPositionKeyframe} directly.
 *
 * Rows accept the timeline position in seconds (decimal allowed). For time keyframes
 * each row also takes the replay time in seconds. Rows left empty (zero) are skipped
 * unless the user explicitly puts 0; a row whose timeline position collides with an
 * existing keyframe is also skipped (we don't try to overwrite or move).
 */
public class GuiAddKeyframes extends AbstractGuiPopup<GuiAddKeyframes> implements KeyHandler {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int INITIAL_ROW_COUNT = 3;

    public enum Mode { TIME, POSITION }

    private final Mode mode;
    private final SPTimeline timeline;

    // Captured camera state when the popup opens — same camera position is used for every
    // POSITION keyframe added by this popup. The user can move the camera and reopen the
    // popup if they want a different reference position.
    private final double camX, camY, camZ;
    private final float camYaw, camPitch, camRoll;
    private final int camSpectatedId;
    private final int defaultReplayMs;

    private final GuiLabel title = new GuiLabel();
    private final GuiPanel rowsPanel = new GuiPanel()
            .setLayout(new VerticalLayout(VerticalLayout.Alignment.TOP).setSpacing(2));
    private final List<Row> rows = new ArrayList<>();

    private final GuiButton addRowButton = new GuiButton()
            .setSize(150, 20)
            .setI18nLabel("replaymod.gui.addkeyframes.addrow");
    private final GuiButton confirmButton = new GuiButton()
            .setSize(150, 20)
            .setI18nLabel("replaymod.gui.addkeyframes.apply");
    private final GuiButton cancelButton = new GuiButton()
            .setSize(150, 20)
            .setI18nLabel("replaymod.gui.cancel");

    private final GuiPanel buttons = new GuiPanel()
            .setLayout(new HorizontalLayout(HorizontalLayout.Alignment.CENTER).setSpacing(7))
            .addElements(new HorizontalLayout.Data(0.5), confirmButton, cancelButton);

    {
        setBackgroundColor(Colors.DARK_TRANSPARENT);
        popup.setLayout(new VerticalLayout().setSpacing(8))
                .addElements(new VerticalLayout.Data(0.5, false), title, rowsPanel, addRowButton, buttons);
    }

    public static GuiAddKeyframes openTime(GuiPathing guiPathing, int defaultCursorMs, int defaultReplayMs) {
        ReplayHandler handler = ReplayModReplay.instance.getReplayHandler();
        if (handler == null) return null;
        SPTimeline timeline = guiPathing.getMod().getCurrentTimeline();
        if (timeline == null) return null;
        return new GuiAddKeyframes(Mode.TIME, timeline, handler, defaultCursorMs, defaultReplayMs);
    }

    public static GuiAddKeyframes openPosition(GuiPathing guiPathing, int defaultCursorMs) {
        ReplayHandler handler = ReplayModReplay.instance.getReplayHandler();
        if (handler == null) return null;
        // Camera entity is required to snapshot the reference position; bail rather than
        // opening a popup that would crash on apply.
        if (handler.getCameraEntity() == null) {
            LOGGER.warn("Refusing to open Add Position Keyframes popup: no camera entity.");
            return null;
        }
        SPTimeline timeline = guiPathing.getMod().getCurrentTimeline();
        if (timeline == null) return null;
        return new GuiAddKeyframes(Mode.POSITION, timeline, handler, defaultCursorMs, 0);
    }

    private GuiAddKeyframes(Mode mode, SPTimeline timeline, ReplayHandler handler,
                            int defaultCursorMs, int defaultReplayMs) {
        super(handler.getOverlay());
        this.mode = mode;
        this.timeline = timeline;
        this.defaultReplayMs = defaultReplayMs;

        // Snapshot camera position once so all POSITION keyframes share the same anchor.
        if (mode == Mode.POSITION) {
            com.replaymod.replay.camera.CameraEntity camera = handler.getCameraEntity();
            this.camX = camera.getX();
            this.camY = camera.getY();
            this.camZ = camera.getZ();
            this.camYaw = camera.yaw;
            this.camPitch = camera.pitch;
            this.camRoll = camera.roll;
            this.camSpectatedId = -1; // explicit non-spectator; popup keeps things simple
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

        // First row pre-filled with the current cursor / replay time so plain "open + apply"
        // matches the old single-keyframe behaviour the O / I hotkeys had before.
        addRow(defaultCursorMs, defaultReplayMs);
        for (int i = 1; i < INITIAL_ROW_COUNT; i++) {
            addRow(-1, -1);
        }

        addRowButton.onClick(() -> addRow(-1, -1));
        confirmButton.onClick(this::applyRows);
        cancelButton.onClick(this::close);

        open();
    }

    private void addRow(int prefilledTimelineMs, int prefilledReplayMs) {
        Row row = new Row();
        if (prefilledTimelineMs >= 0) {
            row.timelineSecondsField.setValue(prefilledTimelineMs / 1000.0);
        }
        if (mode == Mode.TIME && prefilledReplayMs >= 0) {
            row.replaySecondsField.setValue(prefilledReplayMs / 1000.0);
        }
        rows.add(row);
        rowsPanel.addElements(new VerticalLayout.Data(0.5), row.panel);
    }

    private void removeRow(Row row) {
        if (rows.size() <= 1) {
            return; // keep at least one row so the popup never collapses to zero entries
        }
        rows.remove(row);
        rowsPanel.removeElement(row.panel);
    }

    private void applyRows() {
        int added = 0;
        int skippedExisting = 0;
        int skippedInvalid = 0;
        for (Row row : rows) {
            Long timelineMs = row.parseTimelineMs();
            if (timelineMs == null) {
                skippedInvalid++;
                continue;
            }
            switch (mode) {
                case TIME: {
                    Integer replayMs = row.parseReplayMs();
                    if (replayMs == null) {
                        skippedInvalid++;
                        continue;
                    }
                    if (timeline.isTimeKeyframe(timelineMs)) {
                        skippedExisting++;
                        continue;
                    }
                    timeline.addTimeKeyframe(timelineMs, replayMs);
                    added++;
                    break;
                }
                case POSITION: {
                    if (timeline.isPositionKeyframe(timelineMs)) {
                        skippedExisting++;
                        continue;
                    }
                    timeline.addPositionKeyframe(timelineMs, camX, camY, camZ,
                            camYaw, camPitch, camRoll, camSpectatedId);
                    added++;
                    break;
                }
            }
        }
        LOGGER.info("GuiAddKeyframes ({}): added {}, skipped {} existing, {} invalid",
                mode, added, skippedExisting, skippedInvalid);
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
    protected GuiAddKeyframes getThis() {
        return this;
    }

    private class Row {
        // GuiNumberField with precision=3 lets the user enter "5.123" seconds. Internally the
        // SPTimeline stores millisecond longs so we round to ms when reading.
        final GuiNumberField timelineSecondsField = new GuiNumberField()
                .setSize(60, 20).setPrecision(3).setMinValue(0d).setValidateOnFocusChange(true);
        final GuiNumberField replaySecondsField = mode == Mode.TIME
                ? new GuiNumberField().setSize(60, 20).setPrecision(3).setMinValue(0d).setValidateOnFocusChange(true)
                : null;
        final GuiButton removeButton = new GuiButton()
                .setSize(20, 20)
                .setLabel("X")
                .onClick(() -> removeRow(this));
        final GuiPanel panel;

        Row() {
            GuiPanel rowPanel = new GuiPanel()
                    .setLayout(new HorizontalLayout(HorizontalLayout.Alignment.CENTER).setSpacing(4));
            rowPanel.addElements(new HorizontalLayout.Data(0.5),
                    new GuiLabel().setI18nText("replaymod.gui.addkeyframes.timelinelabel"),
                    timelineSecondsField);
            if (mode == Mode.TIME) {
                rowPanel.addElements(new HorizontalLayout.Data(0.5),
                        new GuiLabel().setI18nText("replaymod.gui.addkeyframes.replaylabel"),
                        replaySecondsField);
            }
            rowPanel.addElements(new HorizontalLayout.Data(0.5), removeButton);
            this.panel = rowPanel;
        }

        Long parseTimelineMs() {
            String text = timelineSecondsField.getText();
            if (text == null || text.trim().isEmpty()) {
                return null;
            }
            try {
                double seconds = Double.parseDouble(text);
                if (seconds < 0) return null;
                return Math.round(seconds * 1000d);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        Integer parseReplayMs() {
            if (replaySecondsField == null) return null;
            String text = replaySecondsField.getText();
            if (text == null || text.trim().isEmpty()) {
                // Fallback to the default replay time (the cursor when popup opened) so a row
                // with only the timeline filled still produces a valid time keyframe.
                return defaultReplayMs;
            }
            try {
                double seconds = Double.parseDouble(text);
                if (seconds < 0) return null;
                return (int) Math.round(seconds * 1000d);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
