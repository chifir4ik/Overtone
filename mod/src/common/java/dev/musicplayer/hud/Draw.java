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
        dev.musicplayer.ui.Shapes.fill(c, x, y, w, h, Math.min(r, 3), color);
    }

    static void roundedOutline(Canvas c, int x, int y, int w, int h, int r, int color) {
        dev.musicplayer.ui.Shapes.ring(c, x, y, w, h, Math.min(r, 3), color);
    }

    static void panel(Canvas c, int x, int y, int w, int h, int opacityPercent, float alpha) {
        int pa = Math.round(255 * (opacityPercent / 100f) * alpha);
        roundedRect(c, x, y, w, h, 2, argb(0x111115, pa));
        roundedOutline(c, x, y, w, h, 2, argb(0xFFFFFF, Math.round(pa * 0.12f)));
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
