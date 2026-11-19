package dev.musicplayer.ui;

import dev.musicplayer.compat.Canvas;
import dev.musicplayer.compat.Game;
import dev.musicplayer.input.Hotkeys;
import dev.musicplayer.share.ShareClient;
import dev.musicplayer.util.T;

import java.util.ArrayList;
import java.util.List;

/** «Наушник» в HUD: кольцо удержания у прицела, карточка приглашения и плашки. Всё в пиксельной сетке, как сама игра. */
public final class ShareHud {
    private ShareHud() {}

    private static final int RING_R = 11;
    /** Пиксели кольца по часовой стрелке от верхней точки: заполнение идёт ровно по ним. */
    private static final int[][] RING = ring(RING_R, false), RING_SHADOW = ring(RING_R, true);

    /** Тот же рисунок, что у значка в табе. */
    private static final String[] HEADPHONES = {
            "..####..",
            ".#....#.",
            "#......#",
            "#......#",
            "##....##",
            "##....##",
            "##....##",
    };

    private static float ringAlpha, hintAlpha, cardIn;
    private static long lastNs;
    private static ShareClient.Invite shownInvite;

    public static void render(Canvas c) {
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : Math.min(0.1f, (now - lastNs) / 1e9f);
        lastNs = now;
        ShareClient sc = ShareClient.I;
        if (!sc.available()) { ringAlpha = hintAlpha = cardIn = 0; return; }
        PlayerTheme th = PlayerTheme.current();
        if (Game.screen() == null) drawCrosshair(c, th, sc, dt);
        drawInvite(c, th, sc, dt);
        drawNotes(c, th, sc);
    }

    /** Пиксельные наушники 8×7, scale — во сколько раз крупнее. (x, y) — левый верхний угол. */
    static void headphones(Canvas c, int x, int y, int scale, int argb) {
        for (int row = 0; row < HEADPHONES.length; row++) {
            String line = HEADPHONES[row];
            int start = -1;
            for (int col = 0; col <= line.length(); col++) {
                boolean on = col < line.length() && line.charAt(col) == '#';
                if (on && start < 0) start = col;
                if (!on && start >= 0) {
                    c.fill(x + start * scale, y + row * scale, x + col * scale, y + (row + 1) * scale, argb);
                    start = -1;
                }
            }
        }
    }

    // --- у прицела ---

    private static void drawCrosshair(Canvas c, PlayerTheme th, ShareClient sc, float dt) {
        int cx = c.screenWidth() / 2, cy = c.screenHeight() / 2;
        float p = sc.holdProgress();
        ringAlpha = approach(ringAlpha, p > 0 ? 1f : 0f, dt, p > 0 ? 20f : 12f);
        ShareClient.Aim aim = sc.aim();
        long now = System.currentTimeMillis();
        long sent = now - sc.sentPulseAt(), blocked = now - sc.blockedAt();

        boolean hintWanted = aim != null && p == 0 && sc.aimedFor() > 300 && !sc.isListener(aim.id())
                && dev.musicplayer.config.PlayerConfig.get().shareHint && !sc.listening() && sc.listeners().isEmpty();
        hintAlpha = approach(hintAlpha, hintWanted ? 1f : 0f, dt, 10f);

        int a = Math.round(255 * ringAlpha);
        if (a > 6) {
            ring(c, cx, cy, p, a, th);
            if (aim != null) label(c, T.get("share.hold", aim.name()), cx, cy + RING_R + 6, a, 0xFFFFFF);
        }
        if (sc.sentPulseAt() > 0 && sent < 700) {
            float k = sent / 700f;
            int fade = Math.round(255 * (1f - k));
            int grow = Math.round(k * 4f);
            for (int[] px : ring(RING_R + grow, false)) c.fill(cx + px[0], cy + px[1], cx + px[0] + 1, cy + px[1] + 1, PlayerTheme.argb(th.accentRgb, fade));
            label(c, T.get("share.sent", sc.sentPulseName()), cx, cy + RING_R + 6, Math.min(255, fade * 2), 0xFFFFFF);
        } else if (sc.blockedAt() > 0 && blocked < 2200) {
            int fade = blocked > 1700 ? Math.round(255 * (2200 - blocked) / 500f) : 255;
            label(c, sc.blockedReason(), cx, cy + RING_R + 6, fade, PlayerTheme.WARN & 0xFFFFFF);
        } else if (a <= 6 && hintAlpha > 0.02f) {
            label(c, T.get("share.hint", Hotkeys.shareHoldName()), cx, cy + RING_R + 6, Math.round(230 * hintAlpha), PlayerTheme.TEXT_MID & 0xFFFFFF);
        }
    }

    private static void ring(Canvas c, int cx, int cy, float p, int a, PlayerTheme th) {
        for (int[] px : RING_SHADOW) dot(c, cx, cy, px, PlayerTheme.argb(0x000000, a * 2 / 5));
        int lit = Math.round(RING.length * p);
        for (int i = 0; i < RING.length; i++) {
            int color = i < lit ? PlayerTheme.argb(th.accentRgb, a) : PlayerTheme.argb(0xFFFFFF, a / 4);
            dot(c, cx, cy, RING[i], color);
        }
        // светлый «кончик» заполнения: два последних пикселя, чтобы при толщине 2 он не терялся
        for (int i = Math.max(0, lit - 2); i < lit && lit < RING.length; i++) dot(c, cx, cy, RING[i], PlayerTheme.argb(0xFFFFFF, a));
    }

    private static void dot(Canvas c, int cx, int cy, int[] px, int argb) {
        c.fill(cx + px[0], cy + px[1], cx + px[0] + 1, cy + px[1] + 1, argb);
    }

    /** Кольцо в два пикселя толщиной; shadow — пиксель снаружи, тёмная подложка для контраста на светлом небе. */
    private static int[][] ring(int r, boolean shadow) {
        List<int[]> out = new ArrayList<>();
        float lo = shadow ? r + 0.5f : r - 1.5f, hi = shadow ? r + 1.5f : r + 0.5f;
        for (int y = -r - 2; y <= r + 1; y++) {
            for (int x = -r - 2; x <= r + 1; x++) {
                double d = Math.hypot(x + 0.5, y + 0.5);
                if (d >= lo && d < hi) out.add(new int[]{x, y});
            }
        }
        out.sort((u, v) -> Double.compare(angle(u), angle(v)));
        return out.toArray(new int[0][]);
    }

    private static double angle(int[] px) {
        double a = Math.atan2(px[0] + 0.5, -(px[1] + 0.5));
        return a < 0 ? a + Math.PI * 2 : a;
    }

    /** Подпись как всплывающая подсказка самой игры: тёмная плашка, тонкая рамка, текст с тенью. */
    private static void label(Canvas c, String text, int cx, int y, int alpha, int rgb) {
        if (alpha < 8) return;
        int w = c.textWidth(text) + 8, h = 12, x = cx - w / 2;
        Shapes.fill(c, x, y, w, h, 2, PlayerTheme.argb(0x111115, Math.round(alpha * 0.86f)));
        Shapes.ring(c, x, y, w, h, 2, PlayerTheme.argb(0xFFFFFF, Math.round(alpha * 0.12f)));
        c.text(text, x + 4, y + 2, PlayerTheme.argb(rgb, alpha), true);
    }

    // --- приглашение ---

    private static void drawInvite(Canvas c, PlayerTheme th, ShareClient sc, float dt) {
        ShareClient.Invite inv = sc.invite();
        if (inv != null) shownInvite = inv;
        cardIn = approach(cardIn, inv != null ? 1f : 0f, dt, inv != null ? 9f : 14f);
        ShareClient.Invite show = shownInvite;
        if (cardIn < 0.02f || show == null) return;

        boolean playing = !show.playing().isEmpty();
        int w = Math.min(c.screenWidth() - 16, 220), h = playing ? 54 : 44;
        int x = (c.screenWidth() - w) / 2;
        int y = Math.round(-h + (h + 6) * easeOut(cardIn));
        c.setAlpha(Math.min(1f, cardIn * 1.3f));

        th.window(c, x, y, w, h);
        PlayerTheme.rrect(c, x + 8, y + 8, 24, 24, 4, th.accent);
        headphones(c, x + 12, y + 13, 2, 0xFFFFFFFF);

        int tx = x + 40, tw = x + w - 10 - tx;
        c.text(c.trim(T.get("share.invite", show.name()), tw), tx, y + 9, PlayerTheme.TEXT_MAIN, true);
        int line = y + 20;
        if (playing) {
            c.text(c.trim("♪ " + show.playing(), tw), tx, line, PlayerTheme.TEXT_MID, true);
            line += 11;
        }
        int kx = keycap(c, th, tx, line, Hotkeys.keyName(Hotkeys.Action.SHARE_ACCEPT), true);
        c.text(T.get("share.listen"), kx + 4, line + 3, PlayerTheme.TEXT_MAIN, true);
        kx = keycap(c, th, kx + 8 + c.textWidth(T.get("share.listen")), line, Hotkeys.keyName(Hotkeys.Action.SHARE_DECLINE), false);
        c.text(T.get("share.no"), kx + 4, line + 3, PlayerTheme.TEXT_MID, true);

        long left = Math.max(0, ShareClient.INVITE_TTL_MS - (System.currentTimeMillis() - show.at()));
        int barW = w - 16, fill = Math.round(barW * (left / (float) ShareClient.INVITE_TTL_MS));
        PlayerTheme.rrect(c, x + 8, y + h - 5, barW, 2, 1, th.trackBg);
        if (inv != null && fill > 0) PlayerTheme.rrect(c, x + 8, y + h - 5, Math.max(2, fill), 2, 1, th.accent);
        c.setAlpha(1f);
    }

    /** Клавиша как в справке плеера: тень, рамка, плитка. Возвращает правый край. */
    private static int keycap(Canvas c, PlayerTheme th, int x, int y, String key, boolean primary) {
        int w = Math.max(14, c.textWidth(key) + 8), h = 13;
        PlayerTheme.rrect(c, x, y + 1, w, h, 3, PlayerTheme.argb(0x000000, 120));
        PlayerTheme.rrect(c, x, y, w, h, 3, primary ? th.accentHover : th.border);
        PlayerTheme.rrect(c, x + 1, y + 1, w - 2, h - 2, 2, primary ? th.accent : th.trackBg);
        c.text(key, x + (w - c.textWidth(key)) / 2, y + 3, PlayerTheme.TEXT_MAIN, false);
        return x + w;
    }

    // --- плашки ---

    private static void drawNotes(Canvas c, PlayerTheme th, ShareClient sc) {
        List<ShareClient.Note> notes = sc.notes();
        if (notes.isEmpty()) return;
        long now = System.currentTimeMillis();
        int y = 6 + (cardIn > 0.02f ? Math.round(58 * easeOut(cardIn)) : 0);
        for (int i = notes.size() - 1; i >= 0; i--) {
            ShareClient.Note n = notes.get(i);
            long age = now - n.at();
            float in = easeOut(Math.min(1f, age / 200f));
            float out = age > 3600 ? 1f - Math.min(1f, (age - 3600) / 600f) : 1f;
            int a = Math.round(255 * in * out);
            if (a < 8) continue;
            String text = c.trim(n.text(), c.screenWidth() - 50);
            int w = c.textWidth(text) + 24, h = 15;
            int x = (c.screenWidth() - w) / 2, yy = y - Math.round((1f - in) * 4);
            Shapes.fill(c, x, yy, w, h, 2, PlayerTheme.argb(0x111115, Math.round(a * 0.88f)));
            Shapes.ring(c, x, yy, w, h, 2, PlayerTheme.argb(0xFFFFFF, Math.round(a * 0.12f)));
            headphones(c, x + 5, yy + 4, 1, PlayerTheme.argb(n.good() ? th.accentRgb : PlayerTheme.WARN & 0xFFFFFF, a));
            c.text(text, x + 17, yy + 4, PlayerTheme.argb(0xFFFFFF, a), true);
            y += h + 3;
        }
    }

    private static float approach(float v, float target, float dt, float speed) {
        return v + (target - v) * (1f - (float) Math.exp(-dt * speed));
    }

    private static float easeOut(float t) { return 1f - (1f - t) * (1f - t) * (1f - t); }
}
