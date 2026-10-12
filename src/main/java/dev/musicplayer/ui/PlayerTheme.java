package dev.musicplayer.ui;

import dev.musicplayer.compat.Canvas;
import dev.musicplayer.config.PlayerConfig;
import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.hud.CoverArt;
import dev.musicplayer.model.Track;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * Цвета и примитивы окна плеера. Все цвета выводятся из одного акцента (как у HUD-виджета при синхронизации).
 * Иконки рисуются прямоугольниками — не зависят от шрифта и версии игры.
 */
final class PlayerTheme {
    final int accent, accentHover, accentSoft;
    final int panelTop, panelBottom, panel2, fieldBg, trackBg, hover, rowCurrent, border, dim;
    final int radius;
    final int accentRgb;

    static final int TEXT_MAIN = 0xFFFFFFFF;
    static final int TEXT_MID = 0xFFB4B4C0;
    static final int TEXT_DIM = 0xFF6E6E7C;
    static final int WARN = 0xFFE0AF68;

    private PlayerTheme(PlayerConfig pc) {
        accentRgb = pc.accentRgb() & 0xFFFFFF;
        float k = pc.tint * 0.05f;
        accent = 0xFF000000 | accentRgb;
        accentHover = 0xFF000000 | mix(accentRgb, 0xFFFFFF, 0.2f);
        accentSoft = argb(accentRgb, 70);
        panelTop = 0xFF000000 | mix(0x1C1D27, accentRgb, k);
        panelBottom = 0xFF000000 | mix(0x101117, accentRgb, k * 0.6f);
        panel2 = 0xFF000000 | mix(0x0F1015, accentRgb, k * 0.5f);
        fieldBg = 0xFF000000 | mix(0x0B0C10, accentRgb, k * 0.4f);
        trackBg = 0xFF000000 | mix(0x2C2D39, accentRgb, k * 0.8f);
        hover = 0xFF000000 | mix(0x2C2D39, accentRgb, k * 0.8f + 0.14f);
        rowCurrent = 0xFF000000 | mix(0x0F1015, accentRgb, 0.22f);
        border = argb(accentRgb, 110);
        dim = argb(0x000000, Math.round(pc.dim * 2.55f));
        radius = pc.cornerRadius;
    }

    static PlayerTheme current() { return new PlayerTheme(PlayerConfig.get()); }

    static int argb(int rgb, int alpha) {
        return ((Math.max(0, Math.min(255, alpha)) & 0xFF) << 24) | (rgb & 0xFFFFFF);
    }

    static int mix(int from, int to, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (r << 16) | (g << 8) | b;
    }

    private static int inset(int row, int h, int r) {
        if (r <= 0) return 0;
        double d = 0;
        if (row < r) d = (r - 0.5) - row;
        else if (row >= h - r) d = row - (h - r - 0.5);
        if (d <= 0) return 0;
        double inner = (double) r * r - d * d;
        if (inner <= 0) return r;
        return (int) Math.round(r - Math.sqrt(inner));
    }

    /** Строки с одинаковым скруглением сливаются в один fill: каждый fill в GUI стоит дорого. */
    static void rrect(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        int i = 0;
        while (i < r) {
            int in = inset(i, h, r);
            if (in == 0) break;
            int j = i + 1;
            while (j < r && inset(j, h, r) == in) j++;
            g.fill(x + in, y + i, x + w - in, y + j, color);
            g.fill(x + in, y + h - j, x + w - in, y + h - i, color);
            i = j;
        }
        g.fill(x, y + i, x + w, y + h - i, color);
    }

    static void rrectV(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int top, int bottom) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        int tr = top & 0xFFFFFF, br = bottom & 0xFFFFFF;
        int start = 0, runIn = inset(0, h, r), runCol = gradientColor(tr, br, 0, h);
        for (int row = 1; row <= h; row++) {
            int in = row < h ? inset(row, h, r) : -1;
            int col = row < h ? gradientColor(tr, br, row, h) : 0;
            if (in == runIn && col == runCol) continue;
            g.fill(x + runIn, y + start, x + w - runIn, y + row, runCol);
            start = row; runIn = in; runCol = col;
        }
    }

    private static int gradientColor(int topRgb, int bottomRgb, int row, int h) {
        return 0xFF000000 | mix(topRgb, bottomRgb, h <= 1 ? 0f : row / (float) (h - 1));
    }

    static void circle(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
        if (r <= 0) return;
        int start = 0, runHalf = circleHalf(r, 0);
        for (int k = 1; k <= 2 * r; k++) {
            int half = k < 2 * r ? circleHalf(r, k) : -1;
            if (half == runHalf) continue;
            if (runHalf > 0) g.fill(cx - runHalf, cy - r + start, cx + runHalf, cy - r + k, color);
            start = k; runHalf = half;
        }
    }

    private static int circleHalf(int r, int k) {
        double yy = k - r + 0.5;
        return (int) Math.round(Math.sqrt(r * r - yy * yy));
    }

    static void tri(GuiGraphicsExtractor g, int x, int cy, int w, int h, boolean right, int color) {
        if (h <= 0) return;
        int top = cy - h / 2, start = 0, runW = triWidth(w, h, 0);
        for (int r = 1; r <= h; r++) {
            int cw = r < h ? triWidth(w, h, r) : -1;
            if (cw == runW) continue;
            if (right) g.fill(x, top + start, x + runW, top + r, color);
            else g.fill(x + w - runW, top + start, x + w, top + r, color);
            start = r; runW = cw;
        }
    }

    private static int triWidth(int w, int h, int r) {
        float t = 1f - Math.abs((r + 0.5f) / h * 2f - 1f);
        return Math.max(1, Math.round(w * t));
    }

    static void iconPlay(GuiGraphicsExtractor g, int cx, int cy, int h, int color) {
        int w = Math.round(h * 0.8f);
        tri(g, cx - w / 2 + 1, cy, w, h, true, color);
    }

    static void iconPause(GuiGraphicsExtractor g, int cx, int cy, int h, int color) {
        int bw = Math.max(2, h / 4), gap = Math.max(2, h / 4);
        int x0 = cx - (bw * 2 + gap) / 2;
        g.fill(x0, cy - h / 2, x0 + bw, cy - h / 2 + h, color);
        g.fill(x0 + bw + gap, cy - h / 2, x0 + bw * 2 + gap, cy - h / 2 + h, color);
    }

    static void iconSkip(GuiGraphicsExtractor g, int cx, int cy, int h, int color, boolean forward) {
        int w = Math.round(h * 0.7f), bar = 2;
        int x0 = cx - (w + bar + 1) / 2;
        if (forward) {
            tri(g, x0, cy, w, h, true, color);
            g.fill(x0 + w + 1, cy - h / 2, x0 + w + 1 + bar, cy - h / 2 + h, color);
        } else {
            g.fill(x0, cy - h / 2, x0 + bar, cy - h / 2 + h, color);
            tri(g, x0 + bar + 1, cy, w, h, false, color);
        }
    }

    static void iconVolume(GuiGraphicsExtractor g, int cx, int cy, int waves, int color) {
        g.fill(cx - 5, cy - 2, cx - 2, cy + 2, color);
        for (int i = 0; i < 4; i++) g.fill(cx - 2 + i, cy - 2 - i, cx - 1 + i, cy + 2 + i, color);
        if (waves >= 1) g.fill(cx + 4, cy - 2, cx + 5, cy + 2, color);
        if (waves >= 2) g.fill(cx + 6, cy - 4, cx + 7, cy + 4, color);
        if (waves == 0) {
            for (int i = 0; i < 4; i++) {
                g.fill(cx + 3 + i, cy - 2 + i, cx + 4 + i, cy - 1 + i, color);
                g.fill(cx + 3 + i, cy + 1 - i, cx + 4 + i, cy + 2 - i, color);
            }
        }
    }

    private static final String[] HEART = {
            ".XX.XX.",
            "XXXXXXX",
            "XXXXXXX",
            ".XXXXX.",
            "..XXX..",
            "...X..."};

    static final int HEART_W = 7, HEART_H = 6;

    static void iconHeart(GuiGraphicsExtractor g, int x, int y, int color) {
        for (int row = 0; row < HEART.length; row++) {
            int rows = 1;
            while (row + rows < HEART.length && HEART[row + rows].equals(HEART[row])) rows++;
            String line = HEART[row];
            for (int col = 0; col < line.length(); col++) {
                if (line.charAt(col) != 'X') continue;
                int end = col;
                while (end < line.length() && line.charAt(end) == 'X') end++;
                g.fill(x + col, y + row, x + end, y + row + rows, color);
                col = end;
            }
            row += rows - 1;
        }
    }

    static void miniPreview(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h) {
        PlayerTheme th = current();
        PlayerConfig pc = PlayerConfig.get();
        int r = th.radius;
        rrect(g, x - 1, y - 1, w + 2, h + 2, r + 1, th.border);
        rrectV(g, x, y, w, h, r, th.panelTop, th.panelBottom);
        g.fill(x + r, y, x + w - r, y + 2, th.accent);

        final int pad = 7;
        int cs = pc.showCover ? h - pad * 2 - 2 : 0;
        int cy = y + h / 2 + 1;
        if (cs > 0) {
            Track t = PlayerController.I.current;
            Identifier art = t == null ? null : CoverArt.getBig(t.id());
            int cx0 = x + pad, cy0 = y + pad + 1;
            if (art != null) {
                new Canvas(g).texture(art, cx0, cy0, cs, cs, CoverArt.BIG_SIZE, 1f);
            } else {
                rrect(g, cx0, cy0, cs, cs, 4, th.trackBg);
                g.text(font, "♪", cx0 + (cs - font.width("♪")) / 2, cy0 + (cs - font.lineHeight) / 2 + 1, TEXT_DIM);
            }
        }

        int textX = x + pad + (cs > 0 ? cs + 8 : 0);
        int controlsLeft = x + w - pad - 44 - 24 - 7;
        int textW = controlsLeft - 8 - textX;
        g.text(font, font.plainSubstrByWidth("Название трека", textW), textX, y + 9,
                pc.accentText ? th.accent : TEXT_MAIN);
        g.text(font, font.plainSubstrByWidth("Исполнитель", textW), textX, y + 20, TEXT_DIM);

        int by = y + h - 11;
        rrect(g, textX, by, textW, 4, 2, th.trackBg);
        int fill = textW * 4 / 10;
        rrect(g, textX, by, Math.max(4, fill), 4, 2, th.accent);
        circle(g, textX + fill, by + 2, 4, TEXT_MAIN);

        int cxPlay = x + w - pad - 44;
        circle(g, cxPlay - 24, cy, 7, th.trackBg);
        iconSkip(g, cxPlay - 24, cy, 6, TEXT_MID, false);
        circle(g, cxPlay, cy, 10, th.accent);
        iconPlay(g, cxPlay, cy, 9, TEXT_MAIN);
        circle(g, cxPlay + 24, cy, 7, th.trackBg);
        iconSkip(g, cxPlay + 24, cy, 6, TEXT_MID, true);
    }
}
