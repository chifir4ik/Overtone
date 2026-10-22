package dev.musicplayer.hud;

import dev.musicplayer.compat.Canvas;
import dev.musicplayer.compat.Game;
import dev.musicplayer.config.HudConfig;
import dev.musicplayer.config.HudConfig.Anchor;

/** Общие примитивы: скруглённые прямоугольники, цвета, позиционирование. */
final class Draw {

    /** Скрыть все элементы HUD сейчас: открыт экран или зажат Tab. Сами элементы плавно гаснут по этому флагу. */
    static boolean hidden(HudConfig cfg) {
        return (cfg.hideWithGui && Game.guiOpen()) || (cfg.hideOnTab && Game.playerListHeld());
    }
    private Draw() {}

    static int argb(int rgb, int alpha) {
        return ((Math.max(0, Math.min(255, alpha)) & 0xFF) << 24) | (rgb & 0xFFFFFF);
    }

    static int a255(float alpha) { return Math.round(255 * Math.max(0f, Math.min(1f, alpha))); }

    static int lerpRgb(int from, int to, float t) {
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (r << 16) | (g << 8) | b;
    }

    static float clampScale(float s) { return Math.max(0.5f, Math.min(2.0f, s)); }

    static float easeOut(float t) { float u = 1 - Math.max(0f, Math.min(1f, t)); return 1 - u * u * u; }

    /** Рамка, посчитанная последним вызовом begin (читает рендерер сразу после рисования). */
    static HudBounds.Box lastBox;

    /**
     * Считает левый-верхний угол блока w x h по якорю и отступам и включает масштаб (нужен pop()).
     * Вход/выход: блок «выезжает» от своего края экрана по мере роста alpha.
     */
    static int[] begin(Canvas c, Anchor anchor, int ox, int oy, float scale, int w, int h, float alpha) {
        scale = clampScale(scale);
        int sw = Math.round(c.screenWidth() / scale);
        int sh = Math.round(c.screenHeight() / scale);
        int x = anchor.h < 0 ? ox : anchor.h > 0 ? sw - w - ox : (sw - w) / 2 + ox;
        int y = anchor.v < 0 ? oy : anchor.v > 0 ? sh - h - oy : (sh - h) / 2 + oy;
        lastBox = new HudBounds.Box(Math.round(x * scale), Math.round(y * scale),
                Math.round(w * scale), Math.round(h * scale), w, h, scale);
        int slide = Math.round((1f - alpha) * 8f);
        if (anchor.h != 0) x += anchor.h < 0 ? -slide : slide;
        else y += anchor.v <= 0 ? -slide : slide;
        c.push(scale);
        return new int[]{x, y};
    }

    static void roundedRect(Canvas c, int x, int y, int w, int h, int r, int color) {
        if (h <= 0) return;
        int start = 0, runIn = inset(0, h, r);
        for (int row = 1; row <= h; row++) {
            int in = row < h ? inset(row, h, r) : -1;
            if (in == runIn) continue;
            c.rect(x + runIn, y + start, w - runIn * 2, row - start, color);
            start = row; runIn = in;
        }
    }

    static void roundedOutline(Canvas c, int x, int y, int w, int h, int r, int color) {
        int prev = -1;
        for (int row = 0; row < h; row++) {
            int in = inset(row, h, r);
            if (row == 0 || row == h - 1) {
                c.rect(x + in, y + row, w - in * 2, 1, color);
            } else {
                c.rect(x + in, y + row, 1, 1, color);
                c.rect(x + w - in - 1, y + row, 1, 1, color);
                if (prev >= 0 && prev != in) {
                    c.rect(x + in, y + row, prev - in, 1, color);
                    c.rect(x + w - prev, y + row, prev - in, 1, color);
                }
            }
            prev = in;
        }
    }

    private static int inset(int row, int height, int radius) {
        if (radius <= 0) return 0;
        double d = 0;
        if (row < radius) d = (radius - 0.5) - row;
        else if (row >= height - radius) d = row - (height - radius - 0.5);
        if (d <= 0) return 0;
        double inner = radius * radius - d * d;
        if (inner <= 0) return radius;
        return (int) Math.round(radius - Math.sqrt(inner));
    }

    static void panel(Canvas c, int x, int y, int w, int h, int opacityPercent, float alpha) {
        int pa = Math.round(255 * (opacityPercent / 100f) * alpha);
        int r = Math.min(8, h / 3);
        roundedRect(c, x, y, w, h, r, argb(0x14141A, pa));
        roundedOutline(c, x, y, w, h, r, argb(0x3A3B44, Math.round(pa * 0.8f)));
    }

    static void progress(Canvas c, int x, int y, int w, int h, double fraction, int accentRgb, float alpha) {
        if (w <= 2) return;
        int filled = (int) Math.round(w * Math.max(0, Math.min(1, fraction)));
        int back = argb(0x3A3A44, Math.round(200 * alpha));
        int fill = argb(accentRgb, a255(alpha));
        if (h >= 3) {
            roundedRect(c, x, y, w, h, h / 2, back);
            if (filled > 1) roundedRect(c, x, y, filled, h, h / 2, fill);
        } else {
            c.rect(x, y, w, h, back);
            if (filled > 0) c.rect(x, y, filled, h, fill);
        }
    }
}
