package dev.musicplayer;

import com.mojang.blaze3d.platform.InputConstants;
import dev.musicplayer.compat.Canvas;
import dev.musicplayer.config.HudConfig;
import dev.musicplayer.hud.CoverArt;
import dev.musicplayer.hud.LyricsRenderer;
import dev.musicplayer.hud.VolumeRenderer;
import dev.musicplayer.hud.WorldLyricsRenderer;
import dev.musicplayer.hud.WidgetRenderer;
import dev.musicplayer.input.Hotkeys;
import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.ui.HudConfigScreen;
import dev.musicplayer.ui.PlayerScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

public class MusicPlayerMod implements ClientModInitializer {
    /** ID остаётся прежним, чтобы не потерять конфиги, кэш и настройки клавиш пользователей. */
    public static final String MOD_ID = "musicplayer";
    public static final String NAME = "Overtone";
    public static final String AUTHORS = "chifir4ik, PabJlo";
    public static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(NAME);
    private static KeyMapping openKey, hudKey, settingsKey;

    @Override
    public void onInitializeClient() {
        LOGGER.info("{} by {}", NAME, AUTHORS);

        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.musicplayer.open",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_M,
                KeyMapping.Category.MISC
        ));

        hudKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.musicplayer.toggle_hud",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_H,
                KeyMapping.Category.MISC
        ));

        settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.musicplayer.hud_settings",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_K,
                KeyMapping.Category.MISC
        ));

        HudConfig.get();
        Hotkeys.register();

        // Пока открыт экран настроек HUD, вместо обычного HUD рисуется предпросмотр.
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "now_playing"), (graphics, tick) -> {
            if (HudConfigScreen.OPEN && Canvas.guiOpen()) return;
            WidgetRenderer.render(new Canvas(graphics), false);
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "lyrics"), (graphics, tick) -> {
            if (HudConfigScreen.OPEN && Canvas.guiOpen()) return;
            LyricsRenderer.render(new Canvas(graphics), false);
        });

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "volume"), (graphics, tick) -> {
            if (HudConfigScreen.OPEN && Canvas.guiOpen()) return;
            VolumeRenderer.render(new Canvas(graphics), false);
        });

        WorldLyricsRenderer.register();

        // options.txt к этому моменту уже прочитан: переносим клавиши из старого конфига (один раз)
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> Hotkeys.migrateLegacy());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.gui.screen() == null) mc.gui.setScreen(new PlayerScreen());
            }
            CoverArt.tick();
            Hotkeys.tick();
            PlayerController.I.tickPersist();
            while (hudKey.consumeClick()) {
                HudConfig c = HudConfig.get();
                c.master = !c.master;
                c.save();
            }
            while (settingsKey.consumeClick()) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.gui.screen() == null) mc.gui.setScreen(new HudConfigScreen(null));
            }
        });
    }
}