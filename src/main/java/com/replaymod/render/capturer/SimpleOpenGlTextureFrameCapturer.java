package com.replaymod.render.capturer;

import com.replaymod.render.frame.OpenGlTextureFrame;
import com.replaymod.render.rendering.Channel;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;

public class SimpleOpenGlTextureFrameCapturer
        extends OpenGlFrameCapturer<OpenGlTextureFrame, SimpleOpenGlTextureFrameCapturer.SinglePass> {

    //#if MC<12105
    private int flipTextureId;
    private int flipFramebufferId;
    private int flipTextureWidth;
    private int flipTextureHeight;
    //#endif

    public SimpleOpenGlTextureFrameCapturer(WorldRenderer worldRenderer, RenderInfo renderInfo) {
        super(worldRenderer, renderInfo);
    }

    @Override
    public Map<Channel, OpenGlTextureFrame> process() {
        float partialTicks = renderInfo.updateForNextFrame();
        int frameId = framesDone++;
        return Collections.singletonMap(Channel.BRGA,
                (OpenGlTextureFrame) renderFrame(frameId, partialTicks, SinglePass.SINGLE_PASS));
    }

    @Override
    protected OpenGlTextureFrame captureFrame(int frameId, SinglePass captureData) {
        //#if MC>=12105
        //$$ throw new UnsupportedOperationException("Native OpenGL texture encoding is not wired for Minecraft 1.21.5+ GPU texture handles yet.");
        //#else
        // OpenGL framebuffers have a bottom-left origin, but NVENC encodes texture rows in
        // memory order (top-down). Without a flip the encoded video comes out upside-down.
        // Blit the rendered FBO into a private flip-target texture with reversed Y so the
        // native encoder always receives a top-down image; the NV12-or-similar handoff in the
        // C++ side stays unchanged.
        int width = getFrameWidth();
        int height = getFrameHeight();
        int dstTextureId = ensureFlipTarget(width, height);

        int prevReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            // beginWrite(true) binds MC's framebuffer to both READ and DRAW; we override the
            // DRAW binding to our flip-target, blit with reversed Y, then restore the prior
            // bindings so the rest of MC's render loop is unaffected.
            frameBuffer().beginWrite(true);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, flipFramebufferId);
            GL30.glBlitFramebuffer(
                    0, 0, width, height,
                    0, height, width, 0,
                    GL11.GL_COLOR_BUFFER_BIT,
                    GL11.GL_NEAREST);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
        }

        return new OpenGlTextureFrame(frameId, frameSize, GL11.GL_TEXTURE_2D, dstTextureId);
        //#endif
    }

    //#if MC<12105
    private int ensureFlipTarget(int width, int height) {
        if (flipTextureId != 0 && flipTextureWidth == width && flipTextureHeight == height) {
            return flipTextureId;
        }
        if (flipTextureId == 0) {
            flipTextureId = GL11.glGenTextures();
        }
        int prevTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, flipTextureId);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTexture);

        if (flipFramebufferId == 0) {
            flipFramebufferId = GL30.glGenFramebuffers();
        }
        int prevDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, flipFramebufferId);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, flipTextureId, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException(
                    "Native NVENC flip target FBO is incomplete: 0x" + Integer.toHexString(status));
        }

        flipTextureWidth = width;
        flipTextureHeight = height;
        return flipTextureId;
    }
    //#endif

    @Override
    public void close() throws IOException {
        super.close();
        //#if MC<12105
        if (flipFramebufferId != 0) {
            GL30.glDeleteFramebuffers(flipFramebufferId);
            flipFramebufferId = 0;
        }
        if (flipTextureId != 0) {
            GL11.glDeleteTextures(flipTextureId);
            flipTextureId = 0;
        }
        //#endif
    }

    public enum SinglePass implements CaptureData {
        SINGLE_PASS
    }
}
