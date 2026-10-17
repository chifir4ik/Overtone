package dev.musicplayer;

import dev.musicplayer.compat.Canvas;
import dev.musicplayer.config.HudConfig;
import dev.musicplayer.hud.CoverArt;
import dev.musicplayer.hud.LyricsRenderer;
import dev.musicplayer.hud.VolumeRenderer;
import dev.musicplayer.hud.WidgetRenderer;
import dev.musicplayer.hud.WorldLyricsRenderer;
import dev.musicplayer.input.Hotkeys;
import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.ui.HudConfigScreen;
import dev.musicplayer.ui.MenuIntegration;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;

public class MusicPlayerMod implements ClientModInitializer {
    /** ID остаётся прежним, чтобы не потерять конфиги, кэш и настройки клавиш пользователей. */
    public static final String MOD_ID = "musicplayer";
    public static final String NAME = "Overtone";
    public static final String AUTHORS = "chifir4ik, PabJlo";
    public static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(NAME);

    @Override
    public void onInitializeClient() {
        LOGGER.info("{} by {}", NAME, AUTHORS);

        HudConfig.get();
        Hotkeys.register();
        MenuIntegration.register();

        // Пока открыт экран настроек HUD, вместо обычного HUD рисуется предпросмотр.
        hud("now_playing", c -> WidgetRenderer.render(c, false));
        hud("lyrics", c -> LyricsRenderer.render(c, false));
        hud("volume", c -> VolumeRenderer.render(c, false));

        WorldLyricsRenderer.register();

        // options.txt к этому моменту уже прочитан
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> Hotkeys.onClientStarted());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            CoverArt.tick();
            Hotkeys.tick();
            PlayerController.I.tickPersist();
        });
    }

    private static void hud(String id, java.util.function.Consumer<Canvas> renderer) {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, id), (graphics, tick) -> {
            if (HudConfigScreen.OPEN && Canvas.guiOpen()) return;
            renderer.accept(new Canvas(graphics));
        });
    }
}
