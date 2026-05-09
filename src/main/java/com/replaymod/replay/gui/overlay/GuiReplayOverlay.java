package com.replaymod.replay.gui.overlay;

import com.replaymod.core.ReplayMod;
import com.replaymod.core.events.KeyBindingEventCallback;
import com.replaymod.core.events.KeyEventCallback;
import com.replaymod.core.versions.MCVer.Keyboard;
import com.replaymod.replay.ReplayHandler;
import com.replaymod.replay.ReplayModReplay;
import com.replaymod.replay.ReplaySender;
import de.johni0702.minecraft.gui.GuiRenderer;
import de.johni0702.minecraft.gui.RenderInfo;
import de.johni0702.minecraft.gui.container.AbstractGuiOverlay;
import de.johni0702.minecraft.gui.container.GuiPanel;
import de.johni0702.minecraft.gui.element.GuiButton;
import de.johni0702.minecraft.gui.element.GuiElement;
import de.johni0702.minecraft.gui.element.GuiSlider;
import de.johni0702.minecraft.gui.element.GuiTooltip;
import de.johni0702.minecraft.gui.element.advanced.IGuiTimeline;
import de.johni0702.minecraft.gui.function.KeyInput;
import de.johni0702.minecraft.gui.layout.CustomLayout;
import de.johni0702.minecraft.gui.layout.HorizontalLayout;
import de.johni0702.minecraft.gui.utils.EventRegistrations;
import de.johni0702.minecraft.gui.utils.lwjgl.ReadableDimension;
import de.johni0702.minecraft.gui.utils.lwjgl.ReadablePoint;
import de.johni0702.minecraft.gui.utils.lwjgl.WritablePoint;
import net.minecraft.client.options.GameOptions;
import net.minecraft.client.resource.language.I18n;

import static com.replaymod.core.ReplayMod.TEXTURE_SIZE;

public class GuiReplayOverlay extends AbstractGuiOverlay<GuiReplayOverlay> {

    private final ReplayModReplay mod = ReplayModReplay.instance;

    public final GuiPanel topPanel = new GuiPanel(this)
            .setLayout(new HorizontalLayout(HorizontalLayout.Alignment.LEFT).setSpacing(5));
    public final GuiButton playPauseButton = new GuiButton() {
        @Override
        public GuiElement getTooltip(RenderInfo renderInfo) {
            GuiTooltip tooltip = (GuiTooltip) super.getTooltip(renderInfo);
            if (tooltip != null) {
                String label;
                if (getSpriteUV().getY() == 0) { // Play button
                    label = "replaymod.gui.ingame.menu.unpause";
                } else { // Pause button
                    label = "replaymod.gui.ingame.menu.pause";
                }
                tooltip.setText(I18n.translate(label) + " (" + mod.keyPlayPause.getBoundKey() + ")");
            }
            return tooltip;
        }
    }.setSize(20, 20).setTexture(ReplayMod.TEXTURE, TEXTURE_SIZE).setTooltip(new GuiTooltip());
    public final GuiSlider speedSlider = new GuiSlider().setSize(100, 20).setSteps(43); // 0.0 is not included; see getSpeedSliderValue() for the mapping
    public final GuiMarkerTimeline timeline;

    /**
     * This is not used by the replay module itself but may be used by other modules/extras to show
     * when they're active.
     */
    public final GuiPanel statusIndicatorPanel = new GuiPanel(this).setSize(100, 16)
            .setLayout(new HorizontalLayout(HorizontalLayout.Alignment.RIGHT).setSpacing(5));

    private final EventHandler eventHandler = new EventHandler();
    private boolean hidden;

    public GuiReplayOverlay(final ReplayHandler replayHandler) {
        timeline = new GuiMarkerTimeline(replayHandler){
            @Override
            public void draw(GuiRenderer renderer, ReadableDimension size, RenderInfo renderInfo) {
                // Use reached, not realtime-extrapolated, so the playhead doesn't sit ahead of
                // the world at high speeds and snap back on pause. The keyframe timeline tracks
                // this same cursor for auto-sync, so they stay in sync as a pair.
                setCursorPosition(replayHandler.getReplaySender().getReachedTimeStamp());
                super.draw(renderer, size, renderInfo);
            }
        }.setSize(Integer.MAX_VALUE, 20);

        topPanel.addElements(null, playPauseButton, speedSlider, timeline);
        setLayout(new CustomLayout<GuiReplayOverlay>() {
            @Override
            protected void layout(GuiReplayOverlay container, int width, int height) {
                pos(topPanel, 10, 10);
                size(topPanel, width - 20, 20);

                pos(statusIndicatorPanel, width / 2, height - 21);
                width(statusIndicatorPanel, width / 2 - 5);
            }
        });

        playPauseButton.setSpriteUV(new ReadablePoint() {
            @Override
            public int getX() {
                return 0;
            }

            @Override
            public int getY() {
                return replayHandler.getReplaySender().paused() ? 0 : 20;
            }

            @Override
            public void getLocation(WritablePoint dest) {
                dest.setLocation(getX(), getY());
            }
        }).onClick(new Runnable() {
            @Override
            public void run() {
                ReplaySender replaySender = replayHandler.getReplaySender();
                // If currently paused
                if (replaySender.paused()) {
                    // then play
                    replaySender.setReplaySpeed(getSpeedSliderValue());
                } else {
                    // else pause
                    replaySender.setReplaySpeed(0);
                }
            }
        });

        speedSlider.onValueChanged(new Runnable() {
            @Override
            public void run() {
                double speed = getSpeedSliderValue();
                speedSlider.setText(I18n.translate("replaymod.gui.speed") + ": " + speed + "x");
                ReplaySender replaySender = replayHandler.getReplaySender();
                if (!replaySender.paused()) {
                    replaySender.setReplaySpeed(speed);
                }
            }
        }).setValue(9);

        timeline.onClick(new IGuiTimeline.OnClick() {
            @Override
            public void run(int time) {
                replayHandler.doJump(time, true);
            }
        }).setLength(replayHandler.getReplayDuration());
    }

    // Slider position to speed multiplier mapping. Total of 44 positions (slider
    // value 0..43, displayed value 1..44):
    //  -  1.. 9 (9 positions): 0.1x .. 0.9x in 0.1 increments  — fine slow-mo control
    //  - 10..38 (29 positions): 1.0x .. 8.0x in 0.25 increments — original 8x range
    //  - 39..44 (6 positions): 12, 16, 24, 32, 48, 64x         — high-speed extension
    private static final double[] HIGH_SPEED_STEPS = {12d, 16d, 24d, 32d, 48d, 64d};

    public double getSpeedSliderValue() {
        int value = speedSlider.getValue() + 1;
        if (value <= 9) {
            return value / 10d;
        } else if (value <= 38) {
            return 1 + (0.25d * (value - 10));
        } else {
            int idx = value - 39;
            if (idx >= HIGH_SPEED_STEPS.length) {
                idx = HIGH_SPEED_STEPS.length - 1;
            }
            return HIGH_SPEED_STEPS[idx];
        }
    }

    @Override
    public void setVisible(boolean visible) {
        if (isVisible() != visible) {
            if (visible) {
                eventHandler.register();
            } else {
                eventHandler.unregister();
            }
        }
        super.setVisible(visible);
    }

    @Override
    public void draw(GuiRenderer renderer, ReadableDimension size, RenderInfo renderInfo) {
        // Do not render overlay if all hud, or this one specifically, is hidden and we're not in some popup
        if ((getMinecraft().options.hudHidden || hidden) && isAllowUserInput()) {
            // Note that this only applies to when the mouse is visible, otherwise
            // the draw method isn't called in the first place
            return;
        }
        super.draw(renderer, size, renderInfo);
    }

    @Override
    protected GuiReplayOverlay getThis() {
        return this;
    }

    private class EventHandler extends EventRegistrations {
        { on(KeyBindingEventCallback.EVENT, this::onKeyBindingEvent); }
        private void onKeyBindingEvent() {
            GameOptions gameSettings = getMinecraft().options;
            while (gameSettings.keyChat.wasPressed() || gameSettings.keyCommand.wasPressed()) {
                if (!isMouseVisible()) {
                    setMouseVisible(true);
                }
            }
        }

        { on(KeyEventCallback.EVENT, this::onKeyInput); }
        private boolean onKeyInput(KeyInput keyInput, int action) {
            if (action != KeyEventCallback.ACTION_PRESS) return false;
            // Allow F1 to be used to hide the replay gui (e.g. for recording with OBS)
            if (isMouseVisible() && keyInput.key == Keyboard.KEY_F1) {
                hidden = !hidden;
                return true;
            }
            return false;
        }
    }
}
