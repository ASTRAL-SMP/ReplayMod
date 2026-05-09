package com.replaymod.render;

import com.replaymod.core.Module;
import com.replaymod.core.ReplayMod;
import com.replaymod.core.files.ManagedReplayFile;
import com.replaymod.core.utils.Utils;
import com.replaymod.render.utils.RenderJob;
import com.replaymod.replay.ReplayHandler;
import com.replaymod.replay.events.ReplayClosedCallback;
import com.replaymod.replay.events.ReplayOpenedCallback;
import com.replaymod.replaystudio.replay.ReplayFile;
import de.johni0702.minecraft.gui.container.VanillaGuiScreen;
import de.johni0702.minecraft.gui.utils.EventRegistrations;
import net.minecraft.util.crash.CrashReport;
import net.minecraft.util.crash.CrashException;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ReplayModRender extends EventRegistrations implements Module {
    { instance = this; }
    public static ReplayModRender instance;

    private ReplayMod core;

    public static Logger LOGGER = LogManager.getLogger();

    private ReplayFile replayFile;
    private final List<RenderJob> renderQueue = new ArrayList<>();

    public ReplayModRender(ReplayMod core) {
        this.core = core;

        core.getSettingsRegistry().register(Setting.class);
    }

    public ReplayMod getCore() {
        return core;
    }

    @Override
    public void initClient() {
        register();
    }

    public File getVideoFolder() {
        String path = core.getSettingsRegistry().get(Setting.RENDER_PATH);
        File folder = new File(path.startsWith("./") ? core.getMinecraft().runDirectory : null, path);
        try {
            FileUtils.forceMkdir(folder);
        } catch (IOException e) {
            throw new CrashException(CrashReport.create(e, "Cannot create video folder."));
        }
        return folder;
    }

    public Path getRenderSettingsPath() {
        return core.getMinecraft().runDirectory.toPath().resolve("config/replaymod-rendersettings.json");
    }

    public List<RenderJob> getRenderQueue() {
        return renderQueue;
    }

    { on(ReplayOpenedCallback.EVENT, this::onReplayOpened); }
    private void onReplayOpened(ReplayHandler replayHandler) {
        replayFile = replayHandler.getReplayFile();
        try {
            List<RenderJob> stored = RenderJob.readQueue(replayFile);
            if (!stored.isEmpty()) {
                renderQueue.addAll(stored);
            } else {
                // Same recovery story as TimelineBackup: if the zip's renderQueue.json is missing
                // because a previous session crashed before save() could commit, fall back to the
                // sidecar so the user doesn't have to re-enter every output path / codec setting.
                List<RenderJob> restored = RenderJob.readBackup(resolveReplayPath(replayFile));
                if (restored != null) {
                    renderQueue.addAll(restored);
                    LOGGER.info("Restored render queue from sidecar backup ({} jobs).", restored.size());
                    // Re-persist into the zip's staging area so a clean exit propagates the
                    // restore back into renderQueue.json (otherwise the post-close cleanup would
                    // delete the only surviving copy).
                    try {
                        RenderJob.writeQueue(replayFile, renderQueue);
                    } catch (IOException writeFailed) {
                        LOGGER.warn("Re-persisting restored render queue failed: {}", writeFailed.toString());
                    }
                }
            }
        } catch (IOException e) {
            throw new CrashException(CrashReport.create(e, "Reading timeline"));
        }
    }

    { on(ReplayClosedCallback.EVENT, this::onReplayClosed); }
    private void onReplayClosed(ReplayHandler replayHandler) {
        // ReplayClosedCallback fires after replayFile.save() succeeded, so the zip is
        // authoritative — drop the sidecar to avoid an intentional clear being undone.
        Path replayPath = resolveReplayPath(replayHandler.getReplayFile());
        if (replayPath != null) {
            RenderJob.deleteBackup(replayPath);
        }
        renderQueue.clear();
        replayFile = null;
    }

    public void saveRenderQueue() {
        try {
            RenderJob.writeQueue(replayFile, renderQueue);
        } catch (IOException e) {
            e.printStackTrace();
            VanillaGuiScreen screen = VanillaGuiScreen.wrap(getCore().getMinecraft().currentScreen);
            CrashReport report = CrashReport.create(e, "Reading timeline");
            Utils.error(LOGGER, screen, report, () -> {});
        }
        Path replayPath = resolveReplayPath(replayFile);
        if (replayPath != null) {
            try {
                RenderJob.writeBackup(replayPath, renderQueue);
            } catch (IOException e) {
                // Sidecar is best-effort — never fail the user's action because of it.
                LOGGER.warn("Writing render queue sidecar backup for {}: {}", replayPath, e.toString());
            }
        }
    }

    private static Path resolveReplayPath(ReplayFile replayFile) {
        if (replayFile instanceof ManagedReplayFile) {
            return ((ManagedReplayFile) replayFile).getReplayPath();
        }
        return null;
    }
}
