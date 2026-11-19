package dev.musicplayer.hud;

import dev.musicplayer.config.HudConfig.Anchor;

/**
 * Где на экране HUD-элементы нарисованы в последнем кадре (пиксели GUI, с учётом масштаба).
 * Редактор положения по этим рамкам находит элемент под курсором. null — в этом кадре не рисовался.
 */
public final class HudBounds {
    private HudBounds() {}

    public static volatile Box widget, lyrics, volume, visualizer;

    /** Рамка элемента: x, y, w, h — на экране; rawW/rawH — размер до масштабирования. */
    public static final class Box {
        public final int x, y, w, h, rawW, rawH;
        public final float scale;

        Box(int x, int y, int w, int h, int rawW, int rawH, float scale) {
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.rawW = rawW; this.rawH = rawH; this.scale = scale;
        }
    }

    public static final class Placement {
        public final Anchor anchor;
        public final int offsetX, offsetY;

        Placement(Anchor anchor, int offsetX, int offsetY) {
            this.anchor = anchor; this.offsetX = offsetX; this.offsetY = offsetY;
        }
    }

    /**
     * Переводит желаемый левый-верхний угол рамки (в пикселях экрана) в якорь и отступы.
     * Якорь выбирается по тому, в какой трети экрана оказался центр рамки; формулы обратны Draw.begin.
     */
    public static Placement place(Box b, int screenW, int screenH, int newX, int newY) {
        newX = Math.max(0, Math.min(Math.max(0, screenW - b.w), newX));
        newY = Math.max(0, Math.min(Math.max(0, screenH - b.h), newY));
        int cx = newX + b.w / 2, cy = newY + b.h / 2;
        int h = cx < screenW / 3 ? -1 : cx > screenW * 2 / 3 ? 1 : 0;
        int v = cy < screenH / 3 ? -1 : cy > screenH * 2 / 3 ? 1 : 0;
        Anchor a = Anchor.of(h, v);

        int sw = Math.round(screenW / b.scale), sh = Math.round(screenH / b.scale);
        int x = Math.round(newX / b.scale), y = Math.round(newY / b.scale);
        int ox = a.h < 0 ? x : a.h > 0 ? sw - b.rawW - x : x - (sw - b.rawW) / 2;
        int oy = a.v < 0 ? y : a.v > 0 ? sh - b.rawH - y : y - (sh - b.rawH) / 2;
        return new Placement(a, ox, oy);
    }
}
