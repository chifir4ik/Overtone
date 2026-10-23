package dev.musicplayer.compat;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.FormattedCharSequence;

public final class CanvasImpl extends Canvas {
    final GuiGraphics ctx;

    public CanvasImpl(GuiGraphics ctx) { this.ctx = ctx; }

    @Override public int screenWidth() { return ctx.guiWidth(); }
    @Override public int screenHeight() { return ctx.guiHeight(); }

    @Override
    public void fill(int x1, int y1, int x2, int y2, int argb) {
        int c = faded(argb);
        if (opacity < 1f && c >>> 24 == 0) return;
        ctx.fill(x1, y1, x2, y2, c);
    }

    @Override
    public void text(Font font, String value, int x, int y, int argb, boolean shadow) {
        int c = fadedText(argb);
        if (opacity < 1f && c == 0) return;
        ctx.drawString(font, value, x, y, c, shadow);
    }

    @Override
    public void text(Font font, FormattedCharSequence value, int x, int y, int argb) {
        int c = fadedText(argb);
        if (opacity < 1f && c == 0) return;
        ctx.drawString(font, value, x, y, c, true);
    }

    @Override
    public void texture(Tex texture, int x, int y, int width, int height, int textureSize, float alpha) {
        int tint = (Math.max(0, Math.min(255, Math.round(alpha * opacity * 255f))) << 24) | 0xFFFFFF;
        ctx.blit(RenderType::guiTextured, texture.id, x, y, 0.0F, 0.0F,
                width, height, textureSize, textureSize, textureSize, textureSize, tint);
    }

    @Override
    public void blit(Tex texture, int x, int y, int w, int h, int u, int v, int uw, int vh, int textureSize, int argb) {
        int c = faded(argb);
        if (c >>> 24 == 0) return;
        ctx.blit(RenderType::guiTextured, texture.id, x, y, u, v, w, h, uw, vh, textureSize, textureSize, c);
    }

    @Override public void pushMatrix() { ctx.pose().pushPose(); }
    @Override public void translate(float x, float y) { ctx.pose().translate(x, y, 0f); }
    @Override public void scale(float x, float y) { ctx.pose().scale(x, y, 1f); }
    @Override public void rotate(float rad) { ctx.pose().mulPose(new org.joml.Quaternionf().rotationZ(rad)); }
    @Override public void popMatrix() { ctx.pose().popPose(); }
}
