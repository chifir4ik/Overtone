package dev.musicplayer.fabric;

import dev.musicplayer.MusicPlayerMod;
import dev.musicplayer.compat.Canvas;
import dev.musicplayer.compat.CanvasImpl;
import dev.musicplayer.compat.Game;
import dev.musicplayer.compat.KeyInput;
import dev.musicplayer.compat.WorldTextImpl;
import dev.musicplayer.hud.LyricsRenderer;
import dev.musicplayer.hud.VolumeRenderer;
import dev.musicplayer.hud.VisualizerRenderer;
import dev.musicplayer.hud.WidgetRenderer;
import dev.musicplayer.hud.WorldLyricsRenderer;
import dev.musicplayer.input.Hotkeys;
import dev.musicplayer.ui.HudConfigScreen;
import dev.musicplayer.ui.MenuIntegration;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public final class OvertoneFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        FabricLoader loader = FabricLoader.getInstance();
        MusicPlayerMod.init(loader.getConfigDir(), loader.getGameDir());

        for (KeyMapping km : Hotkeys.mappings()) KeyMappingHelper.registerKeyMapping(km);

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MusicPlayerMod.MOD_ID, "overtone"), (graphics, tick) -> hud(new CanvasImpl(graphics)));
        LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> WorldLyricsRenderer.render(ctx.poseStack(), new WorldTextImpl(ctx.submitNodeCollector())));

        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!MenuIntegration.isMenu(screen)) return;
            Screens.getWidgets(screen).add(MenuIntegration.button(screen));
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> !Hotkeys.handleOnMenu(s, new KeyInput(event)));
        });

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> MusicPlayerMod.onClientStarted());
        ClientTickEvents.END_CLIENT_TICK.register(client -> MusicPlayerMod.onClientTick());
    }

    private static void hud(Canvas c) {
        if (HudConfigScreen.OPEN && Game.guiOpen()) return;   // в окне настроек вместо HUD рисуется предпросмотр
        WidgetRenderer.render(c, false);
        LyricsRenderer.render(c, false);
        VolumeRenderer.render(c, false);
        VisualizerRenderer.render(c, false);
    }
}
