package com.replaymod.replay.mixin;

//#if MC>=11600
import com.replaymod.replay.FullReplaySender;
import com.replaymod.replay.ReplayHandler;
import com.replaymod.replay.ReplayModReplay;
import com.replaymod.replay.ReplaySender;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * During a large jump (sync-mode {@code sendPacketsTill}, or async-mode hurrying)
 * the user is staring at a "Please Wait" loading screen and the world renderer's
 * incremental section meshes are never displayed before the jump finishes anyway.
 * Each block update inside that window calls
 * {@link ClientWorld#scheduleBlockRender(BlockPos, BlockState, BlockState)}, which
 * iterates the affected section + its neighbours and queues mesh rebuilds. With
 * heavy contraptions (World Eater etc.) this is the dominant cost: a 60-minute
 * jump can spend ~20 minutes here just rescheduling rebuilds for sections that
 * will be rebuilt again on the next packet.
 *
 * Skip the call entirely while fast-forwarding and let
 * {@link FullReplaySender#setAsyncMode(boolean)} (or {@code stopHurrying}) issue
 * a single {@code worldRenderer.reload()} when the jump completes.
 */
@Mixin(ClientWorld.class)
public abstract class Mixin_SkipBlockRenderUpdatesDuringFastForward {
    @Inject(method = "scheduleBlockRender(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/block/BlockState;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void replayMod_skipBlockRenderDuringFastForward(BlockPos pos, BlockState old, BlockState updated, CallbackInfo ci) {
        ReplayModReplay mod = ReplayModReplay.instance;
        if (mod == null) {
            return;
        }
        ReplayHandler handler = mod.getReplayHandler();
        if (handler == null) {
            return;
        }
        ReplaySender sender = handler.getReplaySender();
        if (sender == null || !sender.isFastForwarding()) {
            return;
        }
        if (sender instanceof FullReplaySender) {
            ((FullReplaySender) sender).replayMod_markSkippedRenderUpdate();
        }
        ci.cancel();
    }
}
//#else
//$$ // ClientWorld.scheduleBlockRender(BlockPos, BlockState, BlockState) was introduced in
//$$ // 1.16; older versions use a different code path that this optimisation doesn't cover.
//#endif
