package dev.musicplayer.ui;

import dev.musicplayer.MusicPlayerMod;
import dev.musicplayer.input.Hotkeys;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/**
 * Плеер можно открыть и без мира: в главном меню, на паузе и после кика с сервера.
 * Музыка живёт в PlayerController и от экранов не зависит, так что играть она продолжает.
 */
public final class MenuIntegration {
    private MenuIntegration() {}

    private static final int BTN_W = 86, BTN_H = 20, MARGIN = 6;

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!isMenu(screen)) return;
            Button open = Button.builder(Component.literal("♪ " + MusicPlayerMod.NAME),
                            b -> client.gui.setScreen(new PlayerScreen(screen)))
                    .bounds(MARGIN, MARGIN, BTN_W, BTN_H).build();
            Screens.getWidgets(screen).add(open);
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> !Hotkeys.handleOnMenu(s, event));
        });
    }

    private static boolean isMenu(Screen s) {
        return s instanceof TitleScreen || s instanceof PauseScreen || s instanceof DisconnectedScreen;
    }
}
