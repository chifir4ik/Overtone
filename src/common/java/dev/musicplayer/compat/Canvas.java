package dev.musicplayer.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Тонкая обёртка над контекстом отрисовки; реализация зависит от версии игры.
 * Основано на SpotifyMusicMod (Kart-Modding, MIT): https://github.com/Kart-Modding/SpotifyMusicMod,
 * см. LICENSE-SpotifyMusicMod.txt.
 */
public abstract class Canvas {
    /** Прозрачность, которая домножается на всё, что рисуется (выцветание окон). */
    protected float opacity = 1f;

    public float opacity() { return opacity; }

    public void setAlpha(float a) { opacity = Math.max(0f, Math.min(1f, a)); }

    protected final int faded(int argb) {
        if (opacity >= 1f) return argb;
        return (Math.round((argb >>> 24) * opacity) << 24) | (argb & 0xFFFFFF);
    }

    /** 0 — цвет получился бы невидимым. Шрифт считает альфу меньше 4 за непрозрачную, поэтому её чиним заранее. */
    protected final int fadedText(int argb) {
        if (opacity >= 1f) return argb;
        int a = (argb >>> 24) < 4 ? 255 : argb >>> 24;
        a = Math.round(a * opacity);
        return a < 5 ? 0 : (a << 24) | (argb & 0xFFFFFF);
    }

    public abstract int screenWidth();
    public abstract int screenHeight();

    /** Углы, а не размер: как у ванильного fill. */
    public abstract void fill(int x1, int y1, int x2, int y2, int argb);
    public abstract void text(Font font, String value, int x, int y, int argb, boolean shadow);
    public abstract void text(Font font, FormattedCharSequence value, int x, int y, int argb);
    public abstract void texture(Tex texture, int x, int y, int width, int height, int textureSize, float alpha);

    public abstract void pushMatrix();
    public abstract void translate(float x, float y);
    public abstract void scale(float x, float y);
    public abstract void rotate(float rad);
    public abstract void popMatrix();

    private static Font font() { return Minecraft.getInstance().font; }

    public void rect(int x, int y, int width, int height, int argb) {
        if (width <= 0 || height <= 0) return;
        fill(x, y, x + width, y + height, argb);
    }

    public void text(String value, int x, int y, int argb, boolean shadow) { text(font(), value, x, y, argb, shadow); }
    public void text(Font font, String value, int x, int y, int argb) { text(font, value, x, y, argb, true); }

    public void push(float scale) {
        pushMatrix();
        if (scale != 1.0F) scale(scale, scale);
    }

    public void pop() { popMatrix(); }

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
}
