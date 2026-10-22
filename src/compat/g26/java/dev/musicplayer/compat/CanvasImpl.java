package dev.musicplayer.compat;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.FormattedCharSequence;

public final class CanvasImpl extends Canvas {
    final GuiGraphicsExtractor ctx;

    public CanvasImpl(GuiGraphicsExtractor ctx) { this.ctx = ctx; }

    @Override public int screenWidth() { return ctx.guiWidth(); }
    @Override public int screenHeight() { return ctx.guiHeight(); }

    @Override
    public void fill(int x1, int y1, int x2, int y2, int argb) { ctx.fill(x1, y1, x2, y2, argb); }

    @Override
    public void text(Font font, String value, int x, int y, int argb, boolean shadow) {
        ctx.text(font, value, x, y, argb, shadow);
    }

    @Override
    public void text(Font font, FormattedCharSequence value, int x, int y, int argb) {
        ctx.text(font, value, x, y, argb);
    }

    @Override
    public void texture(Tex texture, int x, int y, int width, int height, int textureSize, float alpha) {
        int tint = (Math.max(0, Math.min(255, Math.round(alpha * 255f))) << 24) | 0xFFFFFF;
        ctx.blit(RenderPipelines.GUI_TEXTURED, texture.id, x, y, 0.0F, 0.0F,
                width, height, textureSize, textureSize, textureSize, textureSize, tint);
    }

    @Override public void pushMatrix() { ctx.pose().pushMatrix(); }
    @Override public void translate(float x, float y) { ctx.pose().translate(x, y); }
    @Override public void scale(float x, float y) { ctx.pose().scale(x, y); }
    @Override public void popMatrix() { ctx.pose().popMatrix(); }
}
