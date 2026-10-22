package dev.musicplayer.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Текущий экран игры; где он лежит, зависит от версии. */
public final class Game {
    private Game() {}

    public static Screen screen() {
        Minecraft mc = Minecraft.getInstance();
        return mc.gui.screen();
    }

    public static boolean guiOpen() { return screen() != null; }

    /** Спрятан ли ванильный интерфейс (F1). */
    public static boolean hudHidden() { return Minecraft.getInstance().gui.hud.isHidden(); }

    public static void setHudHidden(boolean hidden) {
        var hud = Minecraft.getInstance().gui.hud;
        if (hud.isHidden() != hidden) hud.toggle();
    }

    public static boolean playerListHeld() { return Minecraft.getInstance().options.keyPlayerList.isDown(); }

    public static void open(Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        mc.gui.setScreen(screen);
    }
}
