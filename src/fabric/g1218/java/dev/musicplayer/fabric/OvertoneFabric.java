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
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.ResourceLocation;

public final class OvertoneFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        FabricLoader loader = FabricLoader.getInstance();
        MusicPlayerMod.init(loader.getConfigDir(), loader.getGameDir());

        for (KeyMapping km : Hotkeys.mappings()) KeyBindingHelper.registerKeyBinding(km);

        HudElementRegistry.addLast(ResourceLocation.fromNamespaceAndPath(MusicPlayerMod.MOD_ID, "overtone"), (graphics, tick) -> hud(new CanvasImpl(graphics)));
        WorldRenderEvents.AFTER_TRANSLUCENT.register(ctx -> WorldLyricsRenderer.render(ctx.matrixStack(), new WorldTextImpl()));

        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!MenuIntegration.isMenu(screen)) return;
            Screens.getButtons(screen).add(MenuIntegration.button(screen));
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, key, scancode, modifiers) -> !Hotkeys.handleOnMenu(s, new KeyInput(key, scancode, modifiers)));
        });

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> MusicPlayerMod.onClientStarted());
        ClientTickEvents.END_CLIENT_TICK.register(client -> MusicPlayerMod.onClientTick());
    }

    private static void hud(Canvas c) {
        dev.musicplayer.hud.HudLayers.render(c);
    }
}
