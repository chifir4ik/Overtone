package dev.musicplayer.compat;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.util.FormattedCharSequence;

public final class CanvasImpl extends Canvas {
    final GuiGraphics ctx;

    public CanvasImpl(GuiGraphics ctx) { this.ctx = ctx; }

    @Override public int screenWidth() { return ctx.guiWidth(); }
    @Override public int screenHeight() { return ctx.guiHeight(); }

    @Override
    public void fill(int x1, int y1, int x2, int y2, int argb) { ctx.fill(x1, y1, x2, y2, argb); }

    @Override
    public void text(Font font, String value, int x, int y, int argb, boolean shadow) {
        ctx.drawString(font, value, x, y, argb, shadow);
    }

    @Override
    public void text(Font font, FormattedCharSequence value, int x, int y, int argb) {
        ctx.drawString(font, value, x, y, argb, true);
    }

    @Override
    public void texture(Tex texture, int x, int y, int width, int height, int textureSize, float alpha) {
        RenderSystem.enableBlend();
        ctx.setColor(1f, 1f, 1f, Math.max(0f, Math.min(1f, alpha)));
        ctx.blit(texture.id, x, y, width, height, 0.0F, 0.0F,
                textureSize, textureSize, textureSize, textureSize);
        ctx.setColor(1f, 1f, 1f, 1f);
    }

    @Override public void pushMatrix() { ctx.pose().pushPose(); }
    @Override public void translate(float x, float y) { ctx.pose().translate(x, y, 0f); }
    @Override public void scale(float x, float y) { ctx.pose().scale(x, y, 1f); }
    @Override public void popMatrix() { ctx.pose().popPose(); }
}
