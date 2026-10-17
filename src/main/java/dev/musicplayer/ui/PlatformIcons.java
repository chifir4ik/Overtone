package dev.musicplayer.ui;

import dev.musicplayer.model.Platform;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Значки площадок, нарисованные примитивами GUI на сетке 24×24: упрощённые, но узнаваемые, и не зависят от картинок.
 * Названия и цвета принадлежат их владельцам; мод с ними не связан.
 */
final class PlatformIcons {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int INK = 0xFF16161A;

    private PlatformIcons() {}

    static void draw(GuiGraphicsExtractor g, Platform p, int x, int y, int size) {
        switch (p) {
            case YANDEX -> yandex(g, x, y, size);
            case SOUNDCLOUD -> soundcloud(g, x, y, size);
            case LOCAL -> folder(g, x, y, size);
        }
    }

    private static int u(float v, int size) { return Math.round(v * size / 24f); }

    private static void yandex(GuiGraphicsExtractor g, int x, int y, int s) {
        PlayerTheme.rrect(g, x, y, s, s, u(7, s), 0xFFFFCC00);
        PlayerTheme.circle(g, x + u(9.5f, s), y + u(16.5f, s), u(3.6f, s), INK);
        g.fill(x + u(11.8f, s), y + u(6, s), x + u(14, s), y + u(16.5f, s), INK);
        PlayerTheme.rrect(g, x + u(11.8f, s), y + u(5.5f, s), u(7, s), u(3, s), u(1.2f, s), INK);
    }

    private static void soundcloud(GuiGraphicsExtractor g, int x, int y, int s) {
        PlayerTheme.rrect(g, x, y, s, s, u(7, s), 0xFFFF5500);
        int[] heights = {5, 8, 6};
        for (int i = 0; i < heights.length; i++) {
            int bx = x + u(3.2f + i * 2.2f, s);
            g.fill(bx, y + u(18.5f - heights[i], s), bx + Math.max(1, u(1.2f, s)), y + u(18.5f, s), WHITE);
        }
        PlayerTheme.rrect(g, x + u(9.5f, s), y + u(13.5f, s), u(11, s), u(5, s), u(2.5f, s), WHITE);
        PlayerTheme.circle(g, x + u(13.8f, s), y + u(12.8f, s), u(3.3f, s), WHITE);
        PlayerTheme.circle(g, x + u(17.4f, s), y + u(14.2f, s), u(2.6f, s), WHITE);
    }

    private static void folder(GuiGraphicsExtractor g, int x, int y, int s) {
        PlayerTheme.rrect(g, x, y, s, s, u(7, s), 0xFF34D399);
        PlayerTheme.rrect(g, x + u(5, s), y + u(6.5f, s), u(7, s), u(4, s), u(1.4f, s), WHITE);
        PlayerTheme.rrect(g, x + u(5, s), y + u(8.6f, s), u(14, s), u(9.4f, s), u(2, s), WHITE);
    }
}
