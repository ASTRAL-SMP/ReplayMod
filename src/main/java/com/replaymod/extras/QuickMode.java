package com.replaymod.extras;

import com.replaymod.core.ReplayMod;
import com.replaymod.core.versions.MCVer.Keyboard;
import com.replaymod.replay.ReplayHandler;
import com.replaymod.replay.ReplayModReplay;
import com.replaymod.replay.events.ReplayOpenedCallback;
import com.replaymod.replay.gui.overlay.GuiReplayOverlay;
import de.johni0702.minecraft.gui.element.GuiImage;
import de.johni0702.minecraft.gui.layout.HorizontalLayout;
import de.johni0702.minecraft.gui.utils.EventRegistrations;

public class QuickMode extends EventRegistrations implements Extra {
    private ReplayModReplay module;

    private final GuiImage indicator = new GuiImage().setTexture(ReplayMod.TEXTURE, 40, 100, 16, 16).setSize(16, 16);

    @Override
    public void register(final ReplayMod mod) {
        this.module = ReplayModReplay.instance;

        mod.getKeyBindingRegistry().registerKeyBinding("replaymod.input.quickmode", Keyboard.KEY_Q, () -> {
            ReplayHandler replayHandler = module.getReplayHandler();
            if (replayHandler == null) {
                return;
            }
            replayHandler.getReplaySender().setSyncModeAndWait();
            mod.runLaterWithoutLock(() -> {
                replayHandler.ensureQuickModeInitialized(() -> {
                    boolean enabled = !replayHandler.isQuickMode();
                    updateIndicator(replayHandler.getOverlay(), enabled);
                    replayHandler.setQuickMode(enabled);
                    replayHandler.getReplaySender().setAsyncMode(true);
                });
            });
        }, true);

        register();
    }

    {
        on(ReplayOpenedCallback.EVENT, replayHandler -> {
            updateIndicator(replayHandler.getOverlay(), replayHandler.isQuickMode());
        });
    }
    // QuickMode auto-init on replay open was removed: it serialized behind the FullReplaySender
    // start-up via setSyncModeAndWait, took tens of seconds on cache-miss replays, and produced
    // log21/log22-style noise when the user navigated away mid-init. Quick Mode is now strictly
    // opt-in via the Q hotkey (see registerKeyBinding above).

    private void updateIndicator(GuiReplayOverlay overlay, boolean enabled) {
        if (enabled) {
            overlay.statusIndicatorPanel.addElements(new HorizontalLayout.Data(1), indicator);
        } else {
            overlay.statusIndicatorPanel.removeElement(indicator);
        }
    }
}
