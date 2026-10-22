package dev.musicplayer.hud;

import dev.musicplayer.compat.Canvas;
import dev.musicplayer.config.HudConfig;
import dev.musicplayer.lyrics.WordTimings;
import dev.musicplayer.model.LyricLine;

import java.util.List;

import static dev.musicplayer.hud.Draw.a255;
import static dev.musicplayer.hud.Draw.argb;

/** Текст песни на экране: «Субтитры» (текущая строка + следующая) и «Караоке» (прокручивающийся список). */
public final class LyricsRenderer {
    private LyricsRenderer() {}

    private static final int C_DIM = 0x9A9AA8;

    private static float alpha;
    private static long lastNanos;
    private static List<LyricLine> lastLyrics;
    private static int lastIdx = -2;
    private static long changedAt;

    public static void render(Canvas c, boolean preview) {
        HudConfig cfg = HudConfig.get();
        HudBounds.lyrics = null;
        if (cfg.lyricsMode == HudConfig.LyricsMode.OFF) { alpha = 0f; return; }

        Snapshot s = Snapshot.live();
        if (preview && (s == null || !s.synced)) s = Snapshot.demo();

        long now = System.nanoTime();
        float delta = lastNanos == 0 ? 0 : Math.min(0.1f, (now - lastNanos) / 1_000_000_000f);
        lastNanos = now;

        int idx = s == null ? -1 : s.lyricIndex();
        boolean karaoke = cfg.lyricsMode == HudConfig.LyricsMode.KARAOKE;
        boolean hasText = s != null && s.synced && (karaoke || idx >= 0);
        // Летающий текст в мире включён и у трека есть таймкоды — HUD-текст не нужен; иначе работает как раньше.
        boolean wanted = hasText && (preview || (cfg.master && !Draw.hidden(cfg)
                && !WorldLyricsRenderer.takesOver()));

        alpha += ((wanted ? 1f : 0f) - alpha) * Math.min(1f, delta * (wanted ? 6f : 4f));
        float a = preview ? 1f : alpha;
        if (a < 0.02f) { if (!preview) alpha = 0f; return; }
        if (s == null || !s.synced) return;

        if (s.lyrics != lastLyrics) { lastLyrics = s.lyrics; lastIdx = -2; }
        if (idx != lastIdx) { lastIdx = idx; changedAt = now; }
        float f = Draw.easeOut((now - changedAt) / 1_000_000f / 280f);

        Draw.lastBox = null;
        if (karaoke) drawKaraoke(c, cfg, s, idx, a, f);
        else drawSubtitle(c, cfg, s, idx, a, f);
        HudBounds.lyrics = Draw.lastBox;
    }

    /**
     * Текущая строка по словам: спетые слова — цвет акцента, ещё не спетые — приглушены, звучащее плавно «заливается»
     * (небольшой подъём). Возвращает false, если строка не помещается в ширину — тогда рисуется обычным способом.
     */
    private static boolean drawWords(Canvas c, HudConfig cfg, Snapshot s, int li, int x, int ty, int w, int inner, float a) {
        LyricLine next = li + 1 < s.lyrics.size() ? s.lyrics.get(li + 1) : null;
        WordTimings wt = WordTimings.of(s.lyrics.get(li), next);
        if (wt == null || wt.words.length == 0) return false;
        int space = c.textWidth(" "), total = 0;
        int[] ws = new int[wt.words.length];
        for (int i = 0; i < ws.length; i++) { ws[i] = c.textWidth(wt.words[i]); total += ws[i] + (i > 0 ? space : 0); }
        if (total > inner) return false;

        long pos = s.posMs - cfg.lyricsShiftMs;
        int sung = currentColor(cfg);
        int unsung = Draw.lerpRgb(C_DIM, 0xFFFFFF, 0.6f);
        int px = x + (w - total) / 2;
        for (int i = 0; i < ws.length; i++) {
            float p = Math.max(0f, Math.min(1f, (pos - wt.start[i]) / (float) Math.max(1, wt.end[i] - wt.start[i])));
            p = p * p * (3f - 2f * p);
            int lift = p > 0f && p < 1f ? -1 : 0;
            c.text(wt.words[i], px, ty + lift, argb(Draw.lerpRgb(unsung, sung, p), a255(a)), cfg.lyricsShadow);
            px += ws[i] + space;
        }
        return true;
    }

    private static int currentColor(HudConfig cfg) { return cfg.lyricsAccent ? cfg.accentRgb() : 0xFFFFFF; }

    private static void drawSubtitle(Canvas c, HudConfig cfg, Snapshot s, int idx, float a, float f) {
        final int pad = 6;
        int maxW = cfg.lyricsWidth;
        int inner = maxW - pad * 2;
        int lh = c.lineHeight() + 3;

        List<String> cur = c.wrap(WidgetRenderer.lyricText(s.lyrics, idx), inner, 2);
        if (cur.isEmpty()) cur = List.of("♪");
        String next = idx + 1 < s.lyrics.size() ? c.trim(WidgetRenderer.lyricText(s.lyrics, idx + 1), inner) : null;

        int textW = 0;
        for (String l : cur) textW = Math.max(textW, c.textWidth(l));
        if (next != null) textW = Math.max(textW, c.textWidth(next));

        boolean bg = cfg.lyricsBackground > 0;
        int w = bg ? Math.max(textW + pad * 2, Math.min(maxW, 160)) : textW;
        int rows = cur.size() + (next != null ? 1 : 0);
        int h = rows * lh - 3 + (bg ? pad * 2 : 0);

        int[] o = Draw.begin(c, cfg.lyricsAnchor, cfg.lyricsOffsetX, cfg.lyricsOffsetY, cfg.lyricsScale, w, h, a);
        int x = o[0], y = o[1];
        if (bg) Draw.roundedRect(c, x, y, w, h, 5, argb(0x101018, Math.round(255 * (cfg.lyricsBackground / 100f) * a)));

        int ty = y + (bg ? pad : 0) + Math.round((1f - f) * 3f);
        float curA = a * (0.35f + 0.65f * f);
        for (String l : cur) {
            c.text(l, x + (w - c.textWidth(l)) / 2, ty, argb(currentColor(cfg), a255(curA)), cfg.lyricsShadow);
            ty += lh;
        }
        if (next != null) {
            c.text(next, x + (w - c.textWidth(next)) / 2, ty, argb(C_DIM, a255(a * 0.8f)), cfg.lyricsShadow);
        }
        c.pop();
    }

    private static void drawKaraoke(Canvas c, HudConfig cfg, Snapshot s, int idx, float a, float f) {
        final int pad = 8;
        int n = Math.max(3, cfg.lyricsLines), mid = n / 2;
        int w = cfg.lyricsWidth;
        int slot = c.lineHeight() + 5;
        int h = n * slot - 5 + pad * 2;
        int inner = w - pad * 2;

        int[] o = Draw.begin(c, cfg.lyricsAnchor, cfg.lyricsOffsetX, cfg.lyricsOffsetY, cfg.lyricsScale, w, h, a);
        int x = o[0], y = o[1];
        if (cfg.lyricsBackground > 0) {
            Draw.roundedRect(c, x, y, w, h, 6, argb(0x101018, Math.round(255 * (cfg.lyricsBackground / 100f) * a)));
        }

        float shift = (1f - f);
        for (int r = 0; r < n; r++) {
            int li = idx + (r - mid);
            if (li < 0 || li >= s.lyrics.size()) continue;
            float d = Math.abs((r - mid) + shift);
            float fade = Math.max(0.12f, 1f - 0.38f * d) * Math.max(0f, Math.min(1f, mid + 1 - d));
            if (fade <= 0.02f) continue;

            int color = Draw.lerpRgb(C_DIM, currentColor(cfg), 1f - Math.min(1f, d));
            String t = c.trim(WidgetRenderer.lyricText(s.lyrics, li), inner);
            int ty = y + pad + Math.round((r + shift) * slot);
            if (cfg.lyricsWordKaraoke && li == idx && drawWords(c, cfg, s, li, x, ty, w, inner, a * fade)) continue;
            c.text(t, x + (w - c.textWidth(t)) / 2, ty, argb(color, a255(a * fade)), cfg.lyricsShadow);
        }
        c.pop();
    }
}
