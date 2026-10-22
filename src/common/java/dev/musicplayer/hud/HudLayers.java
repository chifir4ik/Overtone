package dev.musicplayer.hud;

import dev.musicplayer.cinema.Cinematic;
import dev.musicplayer.compat.Canvas;
import dev.musicplayer.compat.Game;
import dev.musicplayer.config.HudConfig;
import dev.musicplayer.ui.HudConfigScreen;

/** Все слои HUD мода в одном месте: загрузчики зовут отсюда, чтобы не повторять порядок отрисовки. */
public final class HudLayers {
    private HudLayers() {}

    public static void render(Canvas c) {
        Cinematic.drawBars(c);
        if (HudConfigScreen.OPEN && Game.guiOpen()) return;   // в окне настроек вместо HUD рисуется предпросмотр

        HudConfig cfg = HudConfig.get();
        float hidden = Cinematic.hudHidden();
        c.setAlpha(1f - hidden);
        WidgetRenderer.render(c, false);
        VolumeRenderer.render(c, false);
        c.setAlpha(cfg.cineKeepMusicHud ? 1f : 1f - hidden);
        LyricsRenderer.render(c, false);
        VisualizerRenderer.render(c, false);
        c.setAlpha(1f);
    }
}
