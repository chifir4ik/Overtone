package dev.musicplayer.hud;

import dev.musicplayer.compat.Canvas;
import dev.musicplayer.config.HudConfig;
import dev.musicplayer.lyrics.WordTimings;
import dev.musicplayer.model.LyricLine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static dev.musicplayer.hud.Draw.a255;
import static dev.musicplayer.hud.Draw.argb;

/** Текст песни на экране: «Субтитры» (текущая строка + следующая) и «Караоке» (прокручивающийся список). */
public final class LyricsRenderer {
    private LyricsRenderer() {}

    private static final int C_DIM = 0x9A9AA8;
    private static final int MAX_BITS = 700;
    private static final float GRAVITY = 650f;

    private static float alpha;
    private static long lastNanos;
    private static List<LyricLine> lastLyrics;
    private static int lastIdx = -2;
    private static long changedAt;

    private static final List<Bit> bits = new ArrayList<>();
    private static final Random RND = new Random();
    /** Где строки были нарисованы в прошлом кадре: уходящая строка рассыпается с того места, где она стояла. */
    private static Map<Integer, List<Run>> runs = new HashMap<>();
    private static Map<Integer, List<Run>> building = new HashMap<>();

    private record Run(String text, int x, int y, int rgb, float alpha, float scale, boolean shadow) {}

    private static final class Bit {
        String ch;
        int w;
        float x, y, s;
        int rgb;
        float alpha;
        boolean shadow;
        HudConfig.WorldExit mode;
        float vx, vy, rot, rotV, rest, phase, delay, age, landedAt, ground;
        boolean landed, bounced;
    }

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
                && !WorldLyricsRenderer.hidesHud()));

        alpha += ((wanted ? 1f : 0f) - alpha) * Math.min(1f, delta * (wanted ? 6f : 4f));
        float a = preview ? 1f : alpha;
        if (a >= 0.02f && s != null && s.synced) {
            if (s.lyrics != lastLyrics) { lastLyrics = s.lyrics; lastIdx = -2; }

            runs = building;
            building = new HashMap<>();
            if (idx != lastIdx) {
                boolean nextLine = lastIdx >= 0 && idx > lastIdx && idx - lastIdx <= 3;
                if (nextLine && cfg.lyricsExit != HudConfig.WorldExit.FADE) {
                    List<Run> gone = runs.get(lastIdx);
                    if (gone != null) crumble(c, cfg, gone);
                }
                lastIdx = idx;
                changedAt = now;
            }
            float f = Draw.easeOut((now - changedAt) / 1_000_000f / 280f);

            Draw.lastBox = null;
            if (karaoke) drawKaraoke(c, cfg, s, idx, a, f);
            else drawSubtitle(c, cfg, s, idx, a, f);
            HudBounds.lyrics = Draw.lastBox;
        } else if (!preview) {
            if (a < 0.02f) alpha = 0f;
        }
        drawBits(c, cfg, delta);
    }

    private static void record(int line, String text, int x, int y, int rgb, float a, HudConfig cfg) {
        building.computeIfAbsent(line, k -> new ArrayList<>())
                .add(new Run(text, x, y, rgb, a, Draw.clampScale(cfg.lyricsScale), cfg.lyricsShadow));
    }

    /** Разбивает уходящую строку на буквы, которые дальше живут по простой физике. */
    private static void crumble(Canvas c, HudConfig cfg, List<Run> line) {
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        for (Run r : line) {
            minX = Math.min(minX, r.x);
            maxX = Math.max(maxX, r.x + c.textWidth(r.text));
        }
        float span = Math.max(1f, maxX - minX), mid = (minX + maxX) / 2f;
        float stagger = cfg.lyricsLetterMs / 1000f;
        int k = 0;

        for (Run r : line) {
            float x = r.x;
            for (int i = 0; i < r.text.length(); ) {
                int cp = r.text.codePointAt(i);
                String ch = new String(Character.toChars(cp));
                int w = c.textWidth(ch);
                i += Character.charCount(cp);
                if (!Character.isWhitespace(cp)) {
                    Bit b = new Bit();
                    b.ch = ch;
                    b.w = w;
                    b.x = x + w / 2f;
                    b.y = r.y + 4f;
                    b.s = r.scale;
                    b.rgb = r.rgb;
                    b.alpha = r.alpha;
                    b.shadow = r.shadow;
                    b.mode = cfg.lyricsExit;
                    b.ground = c.screenHeight() / r.scale - 5f;
                    switch (b.mode) {
                        case FALL -> {
                            b.delay = k * stagger;
                            b.vx = (RND.nextFloat() - 0.5f) * 50f;
                            b.vy = -30f - RND.nextFloat() * 60f;
                            b.rotV = (RND.nextFloat() - 0.5f) * 10f;
                        }
                        case SHATTER -> {
                            b.vx = (b.x - mid) * 2.5f + (RND.nextFloat() - 0.5f) * 80f;
                            b.vy = -140f - RND.nextFloat() * 100f;
                            b.rotV = (RND.nextFloat() - 0.5f) * 24f;
                        }
                        case FLOAT -> {
                            b.delay = k * 0.015f;
                            b.vy = -25f - RND.nextFloat() * 20f;
                            b.phase = RND.nextFloat() * 6.28f;
                        }
                        case WIND -> {
                            b.delay = (b.x - minX) / span * 0.4f;
                            b.vx = 160f + RND.nextFloat() * 100f;
                            b.vy = -10f - RND.nextFloat() * 30f;
                            b.rotV = 4f + RND.nextFloat() * 6f;
                            b.phase = RND.nextFloat() * 6.28f;
                        }
                        default -> b.delay = k * stagger;
                    }
                    bits.add(b);
                    k++;
                }
                x += w;
            }
        }
        while (bits.size() > MAX_BITS) bits.remove(0);
    }

    private static void drawBits(Canvas c, HudConfig cfg, float dt) {
        for (int i = bits.size() - 1; i >= 0; i--) {
            Bit b = bits.get(i);
            b.age += dt;
            float t = b.age - b.delay;
            float alpha = b.alpha, k = 1f;
            boolean dead = false;
            if (t > 0f) {
                switch (b.mode) {
                    case FALL, SHATTER -> {
                        if (!b.landed) {
                            b.vy += GRAVITY * dt;
                            b.x += b.vx * dt;
                            b.y += b.vy * dt;
                            b.rot += b.rotV * dt;
                            if (b.y >= b.ground) {
                                b.y = b.ground;
                                if (b.vy > 220f && !b.bounced) {
                                    b.bounced = true;
                                    b.vy = -b.vy * 0.28f;
                                    b.vx *= 0.5f;
                                    b.rotV *= 0.5f;
                                } else {
                                    b.landed = true;
                                    b.landedAt = t;
                                    b.rest = (RND.nextFloat() - 0.5f) * 0.5f;
                                }
                            }
                        } else {
                            b.rot += (b.rest - b.rot) * Math.min(1f, dt * 12f);
                            float left = t - b.landedAt - cfg.lyricsLinger;
                            alpha *= 1f - Math.max(0f, Math.min(1f, left / 0.8f));
                            dead = left > 0.8f;
                        }
                    }
                    case FLOAT -> {
                        b.y += b.vy * dt;
                        b.x += (float) Math.sin(t * 2.5 + b.phase) * 12f * dt;
                        k = 1f + t * 0.2f;
                        alpha *= 1f - Math.min(1f, t / 2.2f);
                        dead = t > 2.2f;
                    }
                    case WIND -> {
                        b.x += b.vx * dt;
                        b.y += (b.vy + (float) Math.sin(t * 8.0 + b.phase) * 25f) * dt;
                        b.rot += b.rotV * dt;
                        alpha *= 1f - Math.min(1f, t / 1.8f);
                        dead = t > 1.8f;
                    }
                    case SHRINK -> {
                        k = 1f - Draw.easeOut(Math.min(1f, t / 0.3f));
                        dead = t > 0.3f;
                    }
                    case SINK -> {
                        b.y += 35f * dt;
                        alpha *= 1f - Math.min(1f, t / 0.8f);
                        dead = t > 0.8f;
                    }
                    default -> dead = true;
                }
            }

            if (dead) {
                bits.remove(i);
            } else if (alpha >= 0.03f && k > 0.01f && cfg.master) {
                c.pushMatrix();
                c.scale(b.s, b.s);
                c.translate(b.x, b.y);
                if (b.rot != 0f) c.rotate(b.rot);
                if (k != 1f) c.scale(k, k);
                c.text(b.ch, -b.w / 2, -4, argb(b.rgb, a255(alpha)), b.shadow);
                c.popMatrix();
            }
        }
    }

    /** Строка по буквам с выбранной анимацией появления; t — миллисекунды с момента, когда строка стала текущей. */
    private static void lettersIn(Canvas c, HudConfig cfg, String line, int x, int y, int rgb, float a, float t, int before) {
        int j = before;
        for (int i = 0; i < line.length(); j++) {
            int cp = line.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            int w = c.textWidth(ch);
            float p = Math.max(0f, Math.min(1f, (t - j * cfg.lyricsLetterMs) / 220f));
            if (p > 0f) {
                float la = a, dy = 0f, k = 1f;
                switch (cfg.lyricsEnter) {
                    case RISE -> {
                        dy = 7f * (1f - backOut(p));
                        la = a * Math.min(1f, p * 2f);
                    }
                    case POP -> {
                        k = Math.max(0f, backOut(p));
                        la = a * Math.min(1f, p * 3f);
                    }
                    default -> la = p > 0.05f ? a : 0f;
                }
                if (la > 0.02f && k > 0.01f) {
                    c.pushMatrix();
                    c.translate(x + w / 2f, y + 4f + dy);
                    if (k != 1f) c.scale(k, k);
                    c.text(ch, -w / 2, -4, argb(rgb, a255(la)), cfg.lyricsShadow);
                    c.popMatrix();
                }
            }
            x += w;
            i += Character.charCount(cp);
        }
    }

    private static float backOut(float t) {
        float u = t - 1f;
        return 1f + 2.70158f * u * u * u + 1.70158f * u * u;
    }

    /**
     * Текущая строка по словам: спетые слова — цвет акцента, ещё не спетые — приглушены. Цвет проходит по слову
     * буква за буквой, а звучащая буква чуть подпрыгивает. Возвращает false, если строка не помещается в ширину —
     * тогда она рисуется обычным способом.
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
            String word = wt.words[i];
            int letters = word.codePointCount(0, word.length());
            int lx = px;
            int j = 0;
            for (int k = 0; k < word.length(); j++) {
                int cp = word.codePointAt(k);
                String ch = new String(Character.toChars(cp));
                float q = Math.max(0f, Math.min(1f, p * letters - j));
                q = q * q * (3f - 2f * q);
                float hop = (float) Math.sin(Math.PI * q) * 1.6f;
                int col = argb(Draw.lerpRgb(unsung, sung, q), a255(a));
                if (hop > 0.05f) {
                    c.pushMatrix();
                    c.translate(0f, -hop);
                    c.text(ch, lx, ty, col, cfg.lyricsShadow);
                    c.popMatrix();
                } else {
                    c.text(ch, lx, ty, col, cfg.lyricsShadow);
                }
                lx += c.textWidth(ch);
                k += Character.charCount(cp);
            }
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

        boolean letters = cfg.lyricsEnter != HudConfig.LyricsEnter.SLIDE;
        int ty = y + (bg ? pad : 0) + (letters ? 0 : Math.round((1f - f) * 3f));
        float curA = letters ? a : a * (0.35f + 0.65f * f);
        float since = (System.nanoTime() - changedAt) / 1_000_000f;
        int before = 0;
        for (String l : cur) {
            int lx = x + (w - c.textWidth(l)) / 2;
            if (letters) {
                lettersIn(c, cfg, l, lx, ty, currentColor(cfg), curA, since, before);
                before += l.length();
            } else {
                c.text(l, lx, ty, argb(currentColor(cfg), a255(curA)), cfg.lyricsShadow);
            }
            record(idx, l, lx, ty, currentColor(cfg), a, cfg);
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
            // спетая строка уходит рассыпаясь, а не едет вверх вместе со списком
            if (li < idx && cfg.lyricsExit != HudConfig.WorldExit.FADE) continue;
            float d = Math.abs((r - mid) + shift);
            float fade = Math.max(0.12f, 1f - 0.38f * d) * Math.max(0f, Math.min(1f, mid + 1 - d));
            if (fade <= 0.02f) continue;

            int color = Draw.lerpRgb(C_DIM, currentColor(cfg), 1f - Math.min(1f, d));
            String t = c.trim(WidgetRenderer.lyricText(s.lyrics, li), inner);
            int ty = y + pad + Math.round((r + shift) * slot);
            record(li, t, x + (w - c.textWidth(t)) / 2, ty, color, a * fade, cfg);
            if (cfg.lyricsWordKaraoke && li == idx && drawWords(c, cfg, s, li, x, ty, w, inner, a * fade)) continue;
            c.text(t, x + (w - c.textWidth(t)) / 2, ty, argb(color, a255(a * fade)), cfg.lyricsShadow);
        }
        c.pop();
    }
}
