package com.replaymod.replay;

import com.replaymod.core.mixin.MinecraftAccessor;
import com.replaymod.core.mixin.TimerAccessor;
import net.minecraft.client.MinecraftClient;

import static com.replaymod.core.versions.MCVer.getMinecraft;

public interface ReplaySender {
    int currentTimeStamp();

    /**
     * The replay timestamp the sender has actually dispatched packets up to.
     *
     * <p>Differs from {@link #currentTimeStamp()} only for {@link FullReplaySender} in async
     * mode: that one extrapolates from real time × speed so animations stay smooth, but at
     * high speeds (e.g. 64x) the sender can fall behind packet processing and the cursor
     * ends up displayed ahead of where the world actually is. Use this method whenever the
     * UI needs to show — or persist into a keyframe — the position the user is *actually*
     * seeing, not where the sender wishes it were.
     */
    default int getReachedTimeStamp() {
        return currentTimeStamp();
    }

    /**
     * Whether the replay is currently paused.
     * @return {@code true} if it is paused, {@code false} otherwise
     */
    public default boolean paused() {
        MinecraftClient mc = getMinecraft();
        TimerAccessor timer = (TimerAccessor) ((MinecraftAccessor) mc).getTimer();
        //#if MC>=11200
        return timer.getTickLength() == Float.POSITIVE_INFINITY;
        //#else
        //$$ return timer.getTimerSpeed() == 0;
        //#endif
    }

    void setReplaySpeed(double factor);
    double getReplaySpeed();

    boolean isAsyncMode();
    void setAsyncMode(boolean async);
    void setSyncModeAndWait();

    /**
     * Whether the replay is currently fast-forwarding through a large amount of packets
     * (sync-mode `sendPacketsTill` for a big jump, or async-mode hurrying). When this is
     * {@code true} the user is staring at a loading screen and we can drop work whose only
     * visible effect would be incremental world-renderer state, since we'll force a full
     * world re-render once it returns to {@code false}.
     */
    default boolean isFastForwarding() {
        return false;
    }

    void jumpToTime(int value); // async
    void sendPacketsTill(int replayTime); // sync
}
