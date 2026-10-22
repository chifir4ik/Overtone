package dev.musicplayer.ui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Ванильные кнопки-«подложки» под вручную нарисованные элементы: клик принимают они, вид рисуем сами.
 * Регистрировать только через addWidget: в рендер они не попадают, иначе каждая тратит время на пустой спрайт.
 */
final class Hit {
    private Hit() {}

    static Button make(Runnable action) {
        Button b = Button.builder(Component.empty(), press -> action.run()).bounds(0, 0, 0, 0).build();
        b.active = false;
        return b;
    }

    static void place(Button b, int[] r, boolean on) {
        place(b, r[0], r[1], r[2], r[3], on);
    }

    static void place(Button b, int x, int y, int w, int h, boolean on) {
        if (b == null) return;
        b.setX(x);
        b.setY(y);
        b.setWidth(Math.max(0, w));
        b.setHeight(Math.max(0, h));
        b.active = on && w > 0 && h > 0;
    }
}
