package dev.musicplayer.hud;

import dev.musicplayer.compat.Canvas;
import dev.musicplayer.config.HudConfig;
import dev.musicplayer.controller.PlayerController;

import static dev.musicplayer.hud.Draw.a255;
import static dev.musicplayer.hud.Draw.argb;

/** Плашка громкости, как в Windows: появляется при смене громкости клавишами и сама гаснет. */
public final class VolumeRenderer {
    private VolumeRenderer() {}

    private static final int PAD = 6, ICON_W = 13, ICON_H = 10, GAP = 6;
    private static final int C_TEXT = 0xFFFFFF, C_ICON = 0xE6E6EA;

    private static float alpha;
    private static long lastNanos;
    private static long pokedAt;
    private static boolean poked;

    public static void poke() {
        pokedAt = System.nanoTime();
        poked = true;
    }

    public static void render(Canvas c, boolean preview) {
        HudConfig cfg = HudConfig.get();
        HudBounds.volume = null;

        long now = System.nanoTime();
        float delta = lastNanos == 0 ? 0 : Math.min(0.1f, (now - lastNanos) / 1_000_000_000f);
        lastNanos = now;

        boolean active = poked && (now - pokedAt) / 1_000_000_000.0 < cfg.volumeSeconds;
        boolean wanted = active && cfg.master && cfg.volumeEnabled && !(cfg.hideWithGui && Canvas.guiOpen());
        alpha += ((wanted ? 1f : 0f) - alpha) * Math.min(1f, delta * (wanted ? 9f : 4f));
        float a = preview ? 1f : alpha;
        if (a < 0.02f) { if (!preview) alpha = 0f; return; }

        float vol = PlayerController.I.volume;
        int accent = cfg.accent.rgb;
        Draw.lastBox = null;
        switch (cfg.volumeStyle) {
            case COMPACT -> drawCompact(c, cfg, vol, a, accent);
            case VERTICAL -> drawVertical(c, cfg, vol, a, accent);
            default -> drawBar(c, cfg, vol, a, accent);
        }
        HudBounds.volume = Draw.lastBox;
    }

    private static String percent(float vol) { return Math.round(vol * 100f) + "%"; }

    private static void speaker(Canvas c, int x, int y, float vol, float a, int accent) {
        int col = argb(vol <= 0f ? 0x9A9AA8 : C_ICON, a255(a));
        c.rect(x, y + 3, 3, 4, col);
        c.rect(x + 3, y + 2, 1, 6, col);
        c.rect(x + 4, y + 1, 1, 8, col);
        c.rect(x + 5, y, 1, 10, col);
        if (vol <= 0f) {
            int red = argb(0xFF5A5A, a255(a));
            for (int i = 0; i < 5; i++) {
                c.rect(x + 8 + i, y + 2 + i, 1, 1, red);
                c.rect(x + 8 + i, y + 6 - i, 1, 1, red);
            }
            return;
        }
        int on = argb(accent, a255(a)), off = argb(0x55555F, a255(a));
        c.rect(x + 8, y + 3, 1, 4, vol > 0f ? on : off);
        c.rect(x + 10, y + 2, 1, 6, vol > 0.33f ? on : off);
        c.rect(x + 12, y + 1, 1, 8, vol > 0.66f ? on : off);
    }

    private static void drawBar(Canvas c, HudConfig cfg, float vol, float a, int accent) {
        final int barW = 72;
        int textW = cfg.volumePercent ? c.textWidth("100%") : 0;
        int w = PAD + ICON_W + GAP + barW + (textW > 0 ? GAP + textW : 0) + PAD;
        int h = PAD * 2 + Math.max(ICON_H, c.lineHeight());

        int[] o = Draw.begin(c, cfg.volumeAnchor, cfg.volumeOffsetX, cfg.volumeOffsetY, cfg.volumeScale, w, h, a);
        int x = o[0], y = o[1];
        Draw.panel(c, x, y, w, h, cfg.volumeBackground, a);
        c.rect(x + 1, y + 4, 2, h - 8, argb(accent, a255(a)));

        speaker(c, x + PAD, y + (h - ICON_H) / 2, vol, a, accent);
        Draw.progress(c, x + PAD + ICON_W + GAP, y + (h - 4) / 2, barW, 4, vol, accent, a);
        if (textW > 0) {
            String t = percent(vol);
            c.text(t, x + w - PAD - c.textWidth(t), y + (h - c.lineHeight()) / 2 + 1, argb(C_TEXT, a255(a)), true);
        }
        c.pop();
    }

    private static void drawCompact(Canvas c, HudConfig cfg, float vol, float a, int accent) {
        int textW = cfg.volumePercent ? c.textWidth("100%") : 0;
        int w = PAD + ICON_W + (textW > 0 ? GAP + textW : 0) + PAD;
        int h = PAD * 2 + Math.max(ICON_H, c.lineHeight());

        int[] o = Draw.begin(c, cfg.volumeAnchor, cfg.volumeOffsetX, cfg.volumeOffsetY, cfg.volumeScale, w, h, a);
        int x = o[0], y = o[1];
        Draw.panel(c, x, y, w, h, cfg.volumeBackground, a);

        speaker(c, x + PAD, y + (h - ICON_H) / 2, vol, a, accent);
        if (textW > 0) {
            String t = percent(vol);
            c.text(t, x + w - PAD - c.textWidth(t), y + (h - c.lineHeight()) / 2 + 1, argb(C_TEXT, a255(a)), true);
        }
        c.pop();
    }

    private static void drawVertical(Canvas c, HudConfig cfg, float vol, float a, int accent) {
        final int trackH = 56, trackW = 4;
        int textW = cfg.volumePercent ? c.textWidth("100%") : 0;
        int w = Math.max(ICON_W, textW) + PAD * 2;
        int h = PAD + ICON_H + GAP + trackH + (textW > 0 ? GAP + c.lineHeight() : 0) + PAD;

        int[] o = Draw.begin(c, cfg.volumeAnchor, cfg.volumeOffsetX, cfg.volumeOffsetY, cfg.volumeScale, w, h, a);
        int x = o[0], y = o[1];
        Draw.panel(c, x, y, w, h, cfg.volumeBackground, a);

        speaker(c, x + (w - ICON_W) / 2, y + PAD, vol, a, accent);

        int tx = x + (w - trackW) / 2, ty = y + PAD + ICON_H + GAP;
        Draw.roundedRect(c, tx, ty, trackW, trackH, 2, argb(0x3A3A44, Math.round(200 * a)));
        int fill = Math.round(trackH * Math.max(0f, Math.min(1f, vol)));
        if (fill > 0) Draw.roundedRect(c, tx, ty + trackH - fill, trackW, fill, 2, argb(accent, a255(a)));

        if (textW > 0) {
            String t = percent(vol);
            c.text(t, x + (w - c.textWidth(t)) / 2, ty + trackH + GAP, argb(C_TEXT, a255(a)), true);
        }
        c.pop();
    }
}
