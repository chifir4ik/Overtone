package dev.musicplayer.hud;

import dev.musicplayer.compat.Canvas;
import dev.musicplayer.config.HudConfig;
import dev.musicplayer.model.LyricLine;
import net.minecraft.resources.Identifier;

import java.util.List;

import static dev.musicplayer.hud.Draw.a255;
import static dev.musicplayer.hud.Draw.argb;

/**
 * Виджет «Сейчас играет» в шести стилях. Все строки обрезаются по ширине ДО расчёта размеров панели,
 * а блок времени имеет фиксированную ширину, поэтому текст не вылезает за границы и панель не «дрожит».
 */
public final class WidgetRenderer {
    private WidgetRenderer() {}

    private static final int PAD = 5, COVER = 32, GAP = 7, LINE = 11, MAX_TEXT = 140;
    private static final int BAR_COUNT = 5, BAR_W = 2, BAR_GAP = 2, BARS_W = BAR_COUNT * BAR_W + (BAR_COUNT - 1) * BAR_GAP;

    private static final int C_TITLE = 0xFFFFFF, C_ARTIST = 0xB3B3B3, C_TIME = 0x8C8C8C, C_BARS = 0xDCDCDC;

    private static final int V_BG = 0xF0100010, V_BORDER_TOP = 0x505000FF, V_BORDER_BOTTOM = 0x5028007F;

    private static float alpha;
    private static boolean cardLyricsHidden;
    private static long lastNanos;
    private static String lastId;
    private static long changedAt;

    public static void render(Canvas c, boolean preview) {
        HudConfig cfg = HudConfig.get();
        HudBounds.widget = null;
        Snapshot s = preview ? Snapshot.liveOrDemo() : Snapshot.live();
        long now = System.nanoTime();
        float delta = lastNanos == 0 ? 0 : Math.min(0.1f, (now - lastNanos) / 1_000_000_000f);
        lastNanos = now;

        if (s != null && !s.id.equals(lastId)) { lastId = s.id; changedAt = now; }

        boolean wanted = s != null && (preview || (cfg.master && cfg.enabled && shouldShow(cfg, s, now)));
        alpha += ((wanted ? 1f : 0f) - alpha) * Math.min(1f, delta * (wanted ? 4.5f : 3f));
        float a = preview ? 1f : alpha;
        if (a < 0.02f) { if (!preview) alpha = 0f; return; }
        if (s == null) return;

        if (cfg.showCover && s.coverUrl != null) CoverArt.offerRemote(s.id, s.coverUrl);

        Draw.lastBox = null;
        cardLyricsHidden = !preview && WorldLyricsRenderer.takesOver();   // текст уже летает в мире
        switch (cfg.layout) {
            case CLASSIC -> drawClassic(c, cfg, s, a, now);
            case BAR -> drawBar(c, cfg, s, a, now);
            case COMPACT -> drawCompact(c, cfg, s, a);
            case HOTBAR -> drawHotbar(c, cfg, s, a, now);
            case VANILLA -> drawVanilla(c, cfg, s, a, now);
            default -> drawCard(c, cfg, s, a, now);
        }
        HudBounds.widget = Draw.lastBox;
    }

    private static boolean shouldShow(HudConfig cfg, Snapshot s, long now) {
        if (cfg.hideWithGui && Canvas.guiOpen()) return false;
        if (!cfg.showMode.timed()) return true;
        long window = cfg.showMode.seconds * 1000L;
        if (cfg.showOnSongStart) {
            if (s.posMs <= window) return true;
            if ((now - changedAt) / 1_000_000L <= window) return true;
        }
        return cfg.showOnSongEnd && s.durationMs > 0 && (s.durationMs - s.posMs) <= window;
    }

    private static void drawCard(Canvas c, HudConfig cfg, Snapshot s, float a, long now) {
        final int cover = cfg.showCover ? 40 : 0;
        final int pad = 6, tw = 150;
        final int innerX = pad + (cover > 0 ? cover + 8 : 0);
        int w = innerX + tw + pad;

        int lyricIdx = s.lyricIndex();
        boolean lyr = cfg.cardLyrics && s.synced && lyricIdx >= 0 && !cardLyricsHidden;

        int top = Math.max(cover, 24);
        int barY = pad + top + 6;
        int h = barY + 3 + (cfg.showTimestamps ? 4 + c.lineHeight() : 0) + pad;
        int lyrH = lyr ? 30 : 0;

        int[] o = Draw.begin(c, cfg.anchor, cfg.offsetX, cfg.offsetY, cfg.scale, w, h + lyrH, a);
        int x = o[0], y = o[1];
        int accent = cfg.accent.rgb;

        Draw.panel(c, x, y, w, h + lyrH, cfg.backgroundOpacity, a);
        c.rect(x + 1, y + 4, 2, h + lyrH - 8, argb(accent, a255(a)));

        if (cover > 0) artwork(c, s, x + pad, y + pad, cover, a);

        int textW = tw - (cfg.showWaveform ? BARS_W + 6 : 0);
        c.text(c.trim(s.title, textW), x + innerX, y + pad + 4, argb(C_TITLE, a255(a)), true);
        c.text(c.trim(s.artist, textW), x + innerX, y + pad + 4 + LINE + 2, argb(C_ARTIST, a255(a)), true);
        if (cfg.showWaveform) equaliser(c, x + w - pad - BARS_W, y + pad + 12, s, a, now, accent);

        Draw.progress(c, x + pad, y + barY, w - pad * 2, 3, s.fraction(), accent, a);
        if (cfg.showTimestamps) {
            int ty = y + barY + 6;
            c.text(Snapshot.fmt(s.posMs), x + pad, ty, argb(C_TIME, a255(a)), true);
            String total = Snapshot.fmt(s.durationMs);
            c.text(total, x + w - pad - c.textWidth(total), ty, argb(C_TIME, a255(a)), true);
            if (!s.playing) {
                String p = "II";
                c.text(p, x + w / 2 - c.textWidth(p) / 2, ty, argb(accent, a255(a)), true);
            }
        }

        if (lyr) {
            int ly = y + h;
            c.rect(x + pad, ly - 1, w - pad * 2, 1, argb(0xFFFFFF, Math.round(40 * a)));
            int lw = w - pad * 2;
            String cur = lyricText(s.lyrics, lyricIdx);
            c.text(c.trim(cur, lw), x + pad, ly + 4, argb(0xFFFFFF, a255(a)), true);
            if (lyricIdx + 1 < s.lyrics.size()) {
                String nx = lyricText(s.lyrics, lyricIdx + 1);
                c.text(c.trim(nx, lw), x + pad, ly + 16, argb(C_TIME, a255(a)), true);
            }
        }
        c.pop();
    }

    private static void drawClassic(Canvas c, HudConfig cfg, Snapshot s, float a, long now) {
        int timeW = cfg.showTimestamps ? c.textWidth("88:88 / 88:88") : 0;
        String title = c.trim(s.title, MAX_TEXT);
        String artist = c.trim(s.artist, MAX_TEXT);
        int textW = Math.min(MAX_TEXT, Math.max(Math.max(c.textWidth(title), c.textWidth(artist)), timeW));

        int w = PAD * 2 + textW;
        if (cfg.showCover) w += COVER + GAP;
        if (cfg.showWaveform) w += BARS_W + GAP;
        int h = PAD * 2 + COVER;

        int[] o = Draw.begin(c, cfg.anchor, cfg.offsetX, cfg.offsetY, cfg.scale, w, h, a);
        int x = o[0], y = o[1];
        Draw.panel(c, x, y, w, h, cfg.backgroundOpacity, a);

        int cx = x + PAD;
        if (cfg.showCover) { artwork(c, s, cx, y + PAD, COVER, a); cx += COVER + GAP; }

        int ty = y + PAD + 1;
        c.text(title, cx, ty, argb(C_TITLE, a255(a)), true);
        c.text(artist, cx, ty + LINE, argb(C_ARTIST, a255(a)), true);
        if (cfg.showTimestamps) c.text(s.timeLabel(), cx, ty + LINE * 2, argb(C_TIME, a255(a)), true);
        if (cfg.showWaveform) equaliser(c, x + w - PAD - BARS_W, y + h / 2, s, a, now, C_BARS);
        c.pop();
    }

    private static void drawBar(Canvas c, HudConfig cfg, Snapshot s, float a, long now) {
        int timeW = cfg.showTimestamps ? c.textWidth("88:88 / 88:88") : 0;
        String title = c.trim(s.title, MAX_TEXT);
        String artist = c.trim(s.artist, Math.max(40, MAX_TEXT - (timeW > 0 ? timeW + GAP : 0)));
        int second = c.textWidth(artist) + (timeW > 0 ? GAP + timeW : 0);
        int textW = Math.min(MAX_TEXT, Math.max(c.textWidth(title), Math.max(second, 80)));

        int w = PAD * 2 + textW;
        if (cfg.showCover) w += COVER + GAP;
        if (cfg.showWaveform) w += BARS_W + GAP;
        int h = PAD * 2 + COVER;

        int[] o = Draw.begin(c, cfg.anchor, cfg.offsetX, cfg.offsetY, cfg.scale, w, h, a);
        int x = o[0], y = o[1];
        Draw.panel(c, x, y, w, h, cfg.backgroundOpacity, a);

        int cx = x + PAD;
        if (cfg.showCover) { artwork(c, s, cx, y + PAD, COVER, a); cx += COVER + GAP; }
        int right = x + w - PAD - (cfg.showWaveform ? BARS_W + GAP : 0);

        c.text(title, cx, y + PAD + 2, argb(C_TITLE, a255(a)), true);
        c.text(artist, cx, y + PAD + 2 + LINE, argb(C_ARTIST, a255(a)), true);
        if (cfg.showTimestamps) {
            String t = s.timeLabel();
            c.text(t, right - c.textWidth(t), y + PAD + 2 + LINE, argb(C_TIME, a255(a)), true);
        }
        if (cfg.showWaveform) equaliser(c, x + w - PAD - BARS_W, y + PAD + 8, s, a, now, C_BARS);
        Draw.progress(c, cx, y + h - PAD - 4, x + w - PAD - cx, 4, s.fraction(), cfg.accent.rgb, a);
        c.pop();
    }

    private static void drawCompact(Canvas c, HudConfig cfg, Snapshot s, float a) {
        final int art = 16, pad = 3, h = 22;
        final String dot = "·";
        String title = c.trim(s.title, 120);
        String artist = c.trim(s.artist, 90);
        String rest = "-" + Snapshot.fmt(Math.max(0, s.durationMs - s.posMs));
        int restW = cfg.showTimestamps ? c.textWidth("-88:88") : 0;

        int textW = c.textWidth(title);
        if (!artist.isEmpty()) textW += 5 + c.textWidth(dot) + 5 + c.textWidth(artist);
        int w = pad * 2 + textW + (restW > 0 ? 8 + restW : 0) + (cfg.showCover ? art + 5 : 0);

        int[] o = Draw.begin(c, cfg.anchor, cfg.offsetX, cfg.offsetY, cfg.scale, w, h, a);
        int x = o[0], y = o[1];
        Draw.panel(c, x, y, w, h, cfg.backgroundOpacity, a);

        int cx = x + pad;
        if (cfg.showCover) { artwork(c, s, cx, y + pad, art, a); cx += art + 5; }
        int ty = y + 5;
        c.text(title, cx, ty, argb(s.playing ? C_TITLE : 0xC6C6CC, a255(a)), true);
        int after = cx + c.textWidth(title);
        if (!artist.isEmpty()) {
            c.text(dot, after + 5, ty, argb(0x63636F, a255(a)), true);
            c.text(artist, after + 5 + c.textWidth(dot) + 5, ty, argb(C_ARTIST, a255(a)), true);
        }
        if (cfg.showTimestamps) c.text(rest, x + w - pad - c.textWidth(rest), ty, argb(C_TIME, a255(a)), true);
        Draw.progress(c, x + pad, y + h - pad - 1, w - pad * 2, 2, s.fraction(), cfg.accent.rgb, a);
        c.pop();
    }

    private static void drawHotbar(Canvas c, HudConfig cfg, Snapshot s, float a, long now) {
        final String dot = "·";
        float sc = Draw.clampScale(cfg.scale);
        int avail = Math.round(c.screenWidth() / sc) - 20;
        String title = c.trim(s.title, Math.min(200, Math.round(avail * 0.40f)));
        String artist = c.trim(s.artist, Math.min(160, Math.round(avail * 0.25f)));
        String time = s.timeLabel();
        int titleW = c.textWidth(title), dotW = c.textWidth(dot);
        int artistW = artist.isEmpty() ? 0 : 5 + dotW + 5 + c.textWidth(artist);
        int timeW = cfg.showTimestamps ? 8 + c.textWidth("88:88 / 88:88") : 0;
        int w = titleW + artistW + timeW;
        int barsW = cfg.showWaveform ? BARS_W + 6 : 0;

        float scale = Draw.clampScale(cfg.scale);
        c.push(scale);
        int sw = Math.round(c.screenWidth() / scale), sh = Math.round(c.screenHeight() / scale);
        int x = (sw - (w + barsW)) / 2;
        int y = sh - (Canvas.statusBarsVisible() ? 72 : 58) - cfg.offsetY + Math.round((1f - a) * 4f);

        int ta = a255(a);
        if (cfg.showWaveform) { equaliser(c, x, y + 4, s, a, now, C_BARS); x += barsW; }
        c.text(title, x, y, argb(s.playing ? C_TITLE : 0xC6C6CC, ta), true);
        int after = x + titleW;
        if (!artist.isEmpty()) {
            c.text(dot, after + 5, y, argb(0x8A8A96, ta), true);
            c.text(artist, after + 5 + dotW + 5, y, argb(C_ARTIST, ta), true);
        }
        if (cfg.showTimestamps) {
            int tw = c.textWidth(time);
            c.text(time, x + w - tw, y, argb(C_TIME, ta), true);
        }
        c.pop();
    }

    private static void drawVanilla(Canvas c, HudConfig cfg, Snapshot s, float a, long now) {
        final int icon = 16, line = 10;
        String title = c.trim(s.title, MAX_TEXT);
        String artist = c.trim(s.artist, MAX_TEXT);
        int timeW = cfg.showTimestamps ? c.textWidth("88:88 / 88:88") : 0;
        int lines = 2 + (cfg.showTimestamps ? 1 : 0);
        int textW = Math.max(Math.max(c.textWidth(title), c.textWidth(artist)), timeW);

        int cw = textW;
        if (cfg.showCover) cw += icon + 4;
        if (cfg.showWaveform) cw += BARS_W + 6;
        int ch = Math.max(lines * line - 2, cfg.showCover ? icon : 0);
        int w = cw + 8, h = ch + 8;

        int[] o = Draw.begin(c, cfg.anchor, cfg.offsetX, cfg.offsetY, cfg.scale, w, h, a);
        int cx = o[0] + 4, cy = o[1] + 4;
        tooltipFrame(c, cx, cy, cw, ch, a);

        int tx = cx;
        if (cfg.showCover) { artwork(c, s, cx, cy, icon, a); tx += icon + 4; }
        int ta = a255(a);
        c.text(title, tx, cy, argb(0xFFFFFF, ta), true);
        c.text(artist, tx, cy + line, argb(0xAAAAAA, ta), true);
        if (cfg.showTimestamps) c.text(s.timeLabel(), tx, cy + line * 2, argb(0x555555, ta), true);
        if (cfg.showWaveform) equaliser(c, cx + cw - BARS_W, cy + ch / 2, s, a, now, C_BARS);
        c.pop();
    }

    private static void tooltipFrame(Canvas c, int x, int y, int w, int h, float a) {
        int bg = scaleAlpha(V_BG, a);
        c.rect(x - 3, y - 4, w + 6, 1, bg);
        c.rect(x - 3, y + h + 3, w + 6, 1, bg);
        c.rect(x - 3, y - 3, w + 6, h + 6, bg);
        c.rect(x - 4, y - 3, 1, h + 6, bg);
        c.rect(x + w + 3, y - 3, 1, h + 6, bg);
        int top = scaleAlpha(V_BORDER_TOP, a), bottom = scaleAlpha(V_BORDER_BOTTOM, a);
        int bh = h + 4;
        for (int row = 0; row < bh; row++) {
            int col = lerpArgb(top, bottom, bh <= 1 ? 0f : row / (float) (bh - 1));
            c.rect(x - 3, y - 2 + row, 1, 1, col);
            c.rect(x + w + 2, y - 2 + row, 1, 1, col);
        }
        c.rect(x - 3, y - 3, w + 6, 1, top);
        c.rect(x - 3, y + h + 2, w + 6, 1, bottom);
    }

    private static void artwork(Canvas c, Snapshot s, int x, int y, int size, float a) {
        Identifier art = CoverArt.get(s.id);
        if (art != null) {
            c.texture(art, x, y, size, size, CoverArt.TEXTURE_SIZE, a);
        } else {
            Draw.roundedRect(c, x, y, size, size, Math.max(3, size / 8), argb(0x2A2A32, a255(a)));
            if (size >= 16) {
                String note = "♪";
                c.text(note, x + (size - c.textWidth(note)) / 2, y + (size - c.lineHeight()) / 2 + 1,
                        argb(0x6A6A78, a255(a)), false);
            }
        }
    }

    private static void equaliser(Canvas c, int x, int centerY, Snapshot s, float a, long nanos, int rgb) {
        double t = nanos / 1_000_000_000.0;
        for (int i = 0; i < BAR_COUNT; i++) {
            double ph = t * 6.0 + i * 1.3;
            double wave = s.playing ? (Math.sin(ph) * 0.5 + Math.sin(ph * 0.6 + 1.1) * 0.5) : 0.0;
            int bh = s.playing ? (int) Math.round(5 + Math.abs(wave) * 11) : 4 + (i % 2) * 2;
            c.rect(x + i * (BAR_W + BAR_GAP), centerY - bh / 2, BAR_W, bh, argb(rgb, Math.round(230 * a)));
        }
    }

    static String lyricText(List<LyricLine> lines, int idx) {
        if (idx < 0 || idx >= lines.size()) return "";
        String t = lines.get(idx).text();
        return t == null || t.isBlank() ? "♪" : t;
    }

    private static int scaleAlpha(int argb, float a) {
        int al = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, a)));
        return (al << 24) | (argb & 0xFFFFFF);
    }

    private static int lerpArgb(int from, int to, float t) {
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int p = (from >>> shift) & 0xFF, q = (to >>> shift) & 0xFF;
            out |= (Math.round(p + (q - p) * t) & 0xFF) << shift;
        }
        return out;
    }
}
