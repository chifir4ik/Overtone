package dev.musicplayer.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Тонкая обёртка над контекстом отрисовки. Основано на SpotifyMusicMod (Kart-Modding, MIT):
 * https://github.com/Kart-Modding/SpotifyMusicMod, см. LICENSE-SpotifyMusicMod.txt.
 */
public final class Canvas {
    private final GuiGraphicsExtractor ctx;

    public Canvas(GuiGraphicsExtractor ctx) { this.ctx = ctx; }

    private static Font font() { return Minecraft.getInstance().font; }

    public int screenWidth() { return ctx.guiWidth(); }
    public int screenHeight() { return ctx.guiHeight(); }

    public void rect(int x, int y, int width, int height, int argb) {
        if (width <= 0 || height <= 0) return;
        ctx.fill(x, y, x + width, y + height, argb);
    }

    public void text(String value, int x, int y, int argb, boolean shadow) {
        ctx.text(font(), value, x, y, argb, shadow);
    }

    public void texture(Identifier texture, int x, int y, int width, int height, int textureSize, float alpha) {
        int tint = (Math.max(0, Math.min(255, Math.round(alpha * 255f))) << 24) | 0xFFFFFF;
        ctx.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0.0F, 0.0F,
                width, height, textureSize, textureSize, textureSize, textureSize, tint);
    }

    public void push(float scale) {
        ctx.pose().pushMatrix();
        if (scale != 1.0F) ctx.pose().scale(scale, scale);
    }

    public void pop() { ctx.pose().popMatrix(); }

    public int textWidth(String value) { return font().width(value); }
    public int lineHeight() { return font().lineHeight; }

    public String trim(String value, int maxWidth) {
        if (value == null) return "";
        Font font = font();
        if (font.width(value) <= maxWidth) return value;
        int ellipsis = font.width("...");
        StringBuilder sb = new StringBuilder();
        int w = 0;
        for (int i = 0; i < value.length(); i++) {
            int cw = font.width(String.valueOf(value.charAt(i)));
            if (w + cw + ellipsis > maxWidth) break;
            w += cw;
            sb.append(value.charAt(i));
        }
        return sb.append("...").toString();
    }

    /** Перенос по словам, не больше maxLines строк; последняя строка получает «...», если текст обрезан. */
    public List<String> wrap(String text, int maxWidth, int maxLines) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        StringBuilder cur = new StringBuilder();
        String[] words = text.trim().split("\\s+");
        int i = 0;
        for (; i < words.length; i++) {
            String test = cur.length() == 0 ? words[i] : cur + " " + words[i];
            if (font().width(test) <= maxWidth) {
                cur.setLength(0);
                cur.append(test);
            } else {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    if (out.size() >= maxLines) break;
                }
                cur.append(words[i]);
            }
        }
        boolean truncated = i < words.length;
        if (!truncated && cur.length() > 0 && out.size() < maxLines) out.add(cur.toString());
        for (int k = 0; k < out.size(); k++) {
            String line = out.get(k);
            if (truncated && k == out.size() - 1) line = line + "...";
            out.set(k, trim(line, maxWidth));
        }
        return out;
    }

    /** False в творческом режиме и спектаторе: там нет полос здоровья/опыта. */
    public static boolean statusBarsVisible() {
        Minecraft mc = Minecraft.getInstance();
        return mc.gameMode != null && mc.gameMode.canHurtPlayer();
    }

    public static boolean guiOpen() { return Minecraft.getInstance().gui.screen() != null; }
}
