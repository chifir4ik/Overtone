package dev.musicplayer.ui;

import dev.musicplayer.compat.Tex;
import dev.musicplayer.util.T;
import dev.musicplayer.compat.Canvas;
import dev.musicplayer.config.PlayerConfig;
import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.hud.CoverArt;
import dev.musicplayer.model.Track;
import net.minecraft.client.gui.Font;

/**
 * Цвета и примитивы окон мода (плеер, настройки, обучение). Все цвета выводятся из одного акцента.
 * Иконки рисуются прямоугольниками — не зависят от шрифта и версии игры.
 */
final class PlayerTheme {
    final int accent, accentHover;
    final int panelTop, panel2, fieldBg, trackBg, hover, rowCurrent, border, dim;
    final int radius;
    final int accentRgb;
    private final long key;

    static final int TEXT_MAIN = 0xFFFFFFFF;
    static final int TEXT_MID = 0xFFB4B4C0;
    static final int TEXT_DIM = 0xFF6E6E7C;
    static final int WARN = 0xFFE0AF68;

    private static PlayerTheme cached;

    /**
     * Почти чёрные полупрозрачные панели, как подсказки и меню самой игры, рамка в пиксель и скругление
     * ступенькой. Акцент только там, где он что-то значит.
     */
    private PlayerTheme(PlayerConfig pc, long key) {
        this.key = key;
        accentRgb = pc.accentRgb() & 0xFFFFFF;
        int bg = Math.round(pc.windowOpacity * 2.55f);
        float k = pc.tint * 0.05f;
        accent = 0xFF000000 | accentRgb;
        accentHover = 0xFF000000 | mix(accentRgb, 0xFFFFFF, 0.15f);
        panelTop = argb(mix(0x111115, accentRgb, k * 0.6f), Math.round(bg * 0.94f));
        // список треков, текст песни и боковые вкладки — углубления темнее самого окна, а не светлые плашки
        panel2 = argb(mix(0x08080A, accentRgb, k * 0.4f), Math.min(255, bg));
        fieldBg = 0xFF000000 | mix(0x09090C, accentRgb, k * 0.3f);
        trackBg = 0xFF000000 | mix(0x24242A, accentRgb, k * 0.5f);
        hover = 0xFF000000 | mix(0x303038, accentRgb, k * 0.5f + 0.08f);
        rowCurrent = 0xFF000000 | mix(0x1A1A20, accentRgb, 0.3f);
        border = argb(0xFFFFFF, 30);
        dim = argb(0x000000, Math.round(pc.dim * 2.55f));
        radius = (pc.cornerRadius + 1) / 2;
    }

    /** Тема пересобирается только при смене настроек, а не каждый кадр. */
    static PlayerTheme current() {
        PlayerConfig pc = PlayerConfig.get();
        long key = (pc.accentRgb() & 0xFFFFFFL) | (long) pc.tint << 24 | (long) pc.dim << 26
                | (long) pc.cornerRadius << 33 | (long) pc.windowOpacity << 38;
        if (cached == null || cached.key != key) cached = new PlayerTheme(pc, key);
        return cached;
    }

    /** Мелкие элементы скругляются не сильнее окна, но хотя бы на 3 px, иначе они выглядят острыми. */
    private static int small(int r) { return Math.min(r, Math.max(3, current().radius)); }

    void window(Canvas g, int x, int y, int w, int h) {
        Shapes.shadow(g, x + 2, y, w, h, radius, 3, argb(0x000000, (panelTop >>> 24) / 3));
        Shapes.fill(g, x, y, w, h, radius, panelTop);
        Shapes.ring(g, x, y, w, h, radius, border);
        // светлая линия по верхнему краю: панель читается как объёмная, как кнопки самой игры
        g.fill(x + radius + 1, y + 1, x + w - radius - 1, y + 2, argb(0xFFFFFF, 14));
    }

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

    static void rrect(Canvas g, int x, int y, int w, int h, int r, int color) {
        Shapes.fill(g, x, y, w, h, small(r), color);
    }

    static void ring(Canvas g, int x, int y, int w, int h, int r, int color) {
        Shapes.ring(g, x, y, w, h, small(r), color);
    }

    /** Круги в пиксельной сетке выглядят рвано, поэтому это квадраты со скруглёнными углами. */
    static void circle(Canvas g, int cx, int cy, int r, int color) {
        if (r <= 0) return;
        Shapes.fill(g, cx - r, cy - r, r * 2, r * 2, Math.max(1, Math.min(3, r / 3)), color);
    }

    static void tri(Canvas g, int x, int cy, int w, int h, boolean right, int color) {
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

    static void iconPlay(Canvas g, int cx, int cy, int h, int color) {
        int w = Math.round(h * 0.8f);
        tri(g, cx - w / 2 + 1, cy, w, h, true, color);
    }

    static void iconPause(Canvas g, int cx, int cy, int h, int color) {
        int bw = Math.max(2, h / 4), gap = Math.max(2, h / 4);
        int x0 = cx - (bw * 2 + gap) / 2;
        g.fill(x0, cy - h / 2, x0 + bw, cy - h / 2 + h, color);
        g.fill(x0 + bw + gap, cy - h / 2, x0 + bw * 2 + gap, cy - h / 2 + h, color);
    }

    static void iconSkip(Canvas g, int cx, int cy, int h, int color, boolean forward) {
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

    static void iconVolume(Canvas g, int cx, int cy, int waves, int color) {
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

    static void iconHeart(Canvas g, int x, int y, int color) {
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

    static void miniPreview(Canvas g, Font font, int x, int y, int w, int h) {
        PlayerTheme th = current();
        PlayerConfig pc = PlayerConfig.get();
        th.window(g, x, y, w, h);

        final int pad = 7;
        int cs = pc.showCover ? h - pad * 2 - 2 : 0;
        int cy = y + h / 2 + 1;
        if (cs > 0) {
            Track t = PlayerController.I.current;
            Tex art = t == null ? null : CoverArt.getBig(t.id());
            int cx0 = x + pad, cy0 = y + pad + 1;
            if (art != null) {
                g.texture(art, cx0, cy0, cs, cs, CoverArt.BIG_SIZE, 1f);
            } else {
                rrect(g, cx0, cy0, cs, cs, 4, th.trackBg);
                g.text(font, "♪", cx0 + (cs - font.width("♪")) / 2, cy0 + (cs - font.lineHeight) / 2 + 1, TEXT_DIM);
            }
        }

        int textX = x + pad + (cs > 0 ? cs + 8 : 0);
        int controlsLeft = x + w - pad - 44 - 24 - 7;
        int textW = controlsLeft - 8 - textX;
        g.text(font, font.plainSubstrByWidth(T.get("demo.title"), textW), textX, y + 9,
                pc.accentText ? th.accent : TEXT_MAIN);
        g.text(font, font.plainSubstrByWidth(T.get("demo.artist"), textW), textX, y + 20, TEXT_DIM);

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
