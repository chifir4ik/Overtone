package dev.musicplayer.neoforge;

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
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = MusicPlayerMod.MOD_ID, dist = Dist.CLIENT)
public final class OvertoneNeoForge {
    public OvertoneNeoForge(IEventBus modBus) {
        MusicPlayerMod.init(FMLPaths.CONFIGDIR.get(), FMLPaths.GAMEDIR.get());

        modBus.addListener((RegisterKeyMappingsEvent event) -> {
            for (KeyMapping km : Hotkeys.mappings()) event.register(km);
        });
        modBus.addListener((RegisterGuiLayersEvent event) ->
                event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(MusicPlayerMod.MOD_ID, "overtone"), (graphics, delta) -> hud(new CanvasImpl(graphics))));
        modBus.addListener((FMLClientSetupEvent event) -> MusicPlayerMod.onClientStarted());

        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> MusicPlayerMod.onClientTick());
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterTranslucentBlocks event) ->
                WorldLyricsRenderer.render(event.getPoseStack(), new WorldTextImpl()));

        NeoForge.EVENT_BUS.addListener((ScreenEvent.Init.Post event) -> {
            Screen screen = event.getScreen();
            if (MenuIntegration.isMenu(screen)) event.addListener(MenuIntegration.button(screen));
        });
        NeoForge.EVENT_BUS.addListener((ScreenEvent.KeyPressed.Pre event) -> {
            Screen screen = event.getScreen();
            if (MenuIntegration.isMenu(screen) && Hotkeys.handleOnMenu(screen, new KeyInput(event.getKeyCode(), event.getScanCode(), event.getModifiers()))) event.setCanceled(true);
        });
    }

    private static void hud(Canvas c) {
        if (HudConfigScreen.OPEN && Game.guiOpen()) return;   // в окне настроек вместо HUD рисуется предпросмотр
        WidgetRenderer.render(c, false);
        LyricsRenderer.render(c, false);
        VolumeRenderer.render(c, false);
        VisualizerRenderer.render(c, false);
    }
}
