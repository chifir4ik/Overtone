package dev.musicplayer.ui;

import dev.musicplayer.input.Hotkeys;
import dev.musicplayer.compat.Canvas;
import dev.musicplayer.compat.Game;
import dev.musicplayer.compat.ModScreen;
import dev.musicplayer.share.ShareClient;
import dev.musicplayer.share.ShareProto;
import dev.musicplayer.util.T;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Кому дать «наушник»: игроки с Overtone рядом и те, кто уже слушает. Выглядит как список в окне плеера. */
public class ShareScreen extends ModScreen {
    private static final int ROW_H = 24;

    private record Click(int x, int y, int w, int h, Runnable action) {}

    private final Screen parent;
    private final List<Click> clicks = new ArrayList<>();
    private final Map<String, Float> glow = new HashMap<>();
    private final long openedNs = System.nanoTime();
    private long lastNs;
    private float dt = 0.016f;
    private int px, py, pw, ph, mouseX, mouseY;
    private PlayerTheme th;

    public ShareScreen(Screen parent) {
        super(Component.literal(T.get("share.title")));
        this.parent = parent;
    }

    @Override
    protected void init() {
        pw = Math.min(width - 16, 300);
        ph = Math.min(height - 16, 230);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
    }

    @Override
    protected void draw(Canvas g, int mx, int my, float delta) {
        mouseX = mx;
        mouseY = my;
        long now = System.nanoTime();
        dt = lastNs == 0 ? 0.016f : Math.min(0.1f, (now - lastNs) / 1e9f);
        lastNs = now;
        th = PlayerTheme.current();
        clicks.clear();
        ShareClient sc = ShareClient.I;

        th.window(g, px, py, pw, ph);
        g.fill(px + 10, py + 24, px + pw - 10, py + 25, PlayerTheme.argb(0xFFFFFF, 16));
        ShareHud.headphones(g, px + 14, py + 9, 1, th.accent);
        g.text(font, T.get("share.title"), px + 26, py + 9, th.accent);

        String done = T.get("share.done");
        int dw = font.width(done) + 16, hx = px + pw - 12 - dw;
        button(g, hx, py + 5, dw, 16, done, false, "done", this::onClose);
        if (!sc.listeners().isEmpty()) {
            String stop = T.get("share.stop_all");
            int sw = font.width(stop) + 16;
            button(g, hx - 4 - sw, py + 5, sw, 16, stop, false, "stop", sc::stopSharing);
        }

        int lx = px + 12, lw = pw - 24, top = py + 32, bottom = py + ph - 22;
        PlayerTheme.rrect(g, px + 8, py + 28, pw - 16, bottom - py - 26, Math.max(0, th.radius - 2), th.panel2);

        String why = sc.whyCantShare();
        if (!sc.available()) {
            empty(g, T.get("share.no_server"), T.get("share.no_server_2"), top, bottom);
        } else {
            int y = top;
            int order = 0;
            List<ShareProto.Listener> listening = sc.listeners();
            if (!listening.isEmpty()) {
                y = section(g, T.get("share.listening_now", listening.size()), lx, y);
                for (ShareProto.Listener l : listening) {
                    if (y + ROW_H > bottom) break;
                    row(g, lx, lw, y, l.id(), l.name(), order++, Kind.LISTENER);
                    y += ROW_H;
                }
                y += 4;
            }
            List<ShareClient.Aim> near = new ArrayList<>();
            for (ShareClient.Aim a : sc.nearby()) if (!sc.isListener(a.id())) near.add(a);
            if (!near.isEmpty()) {
                y = section(g, T.get("share.nearby"), lx, y);
                for (ShareClient.Aim a : near) {
                    if (y + ROW_H > bottom) break;
                    row(g, lx, lw, y, a.id(), a.name(), order++, sc.invitedRecently(a.id()) ? Kind.SENT : Kind.NEARBY);
                    y += ROW_H;
                }
            } else if (listening.isEmpty()) {
                empty(g, T.get("share.nobody"), T.get("share.nobody_2"), top, bottom);
            }
        }

        String foot = why != null && sc.available() && !sc.listening() ? T.get(why) : T.get("share.footer", Hotkeys.shareHoldName());
        int fc = why != null && sc.available() && !sc.listening() ? PlayerTheme.WARN : PlayerTheme.TEXT_DIM;
        String fit = font.plainSubstrByWidth(foot, pw - 24);
        g.text(font, fit, px + (pw - font.width(fit)) / 2, py + ph - 15, fc);
    }

    private enum Kind { NEARBY, SENT, LISTENER }

    private int section(Canvas g, String title, int x, int y) {
        g.text(font, title, x + 2, y + 2, PlayerTheme.TEXT_DIM);
        return y + 13;
    }

    private void row(Canvas g, int lx, int lw, int y, UUID id, String name, int order, Kind kind) {
        float in = enter(order);
        if (in < 0.05f) return;
        g.pushMatrix();
        g.translate((1f - in) * 8f, 0f);
        boolean hov = mouseX >= lx && mouseX < lx + lw && mouseY >= y && mouseY < y + ROW_H - 2;
        float gl = glow("row" + id, hov);
        if (kind == Kind.LISTENER) PlayerTheme.rrect(g, lx, y, lw, ROW_H - 2, 4, th.rowCurrent);
        else if (gl > 0.02f) PlayerTheme.rrect(g, lx, y, lw, ROW_H - 2, 4, blend(th.panel2, th.hover, gl));

        int size = ROW_H - 6;
        int box = kind == Kind.LISTENER ? th.accent : PlayerTheme.argb(th.accentRgb, hov ? 160 : 110);
        PlayerTheme.rrect(g, lx + 4, y + 2, size, size, 4, box);
        String letter = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        g.text(font, letter, lx + 4 + (size - font.width(letter)) / 2, y + 2 + (size - font.lineHeight) / 2 + 1, PlayerTheme.TEXT_MAIN);

        int tx = lx + size + 10, tw = lw - size - 100;
        g.text(font, font.plainSubstrByWidth(name, tw), tx, y + 3, kind == Kind.LISTENER ? th.accent : PlayerTheme.TEXT_MAIN);
        String sub = kind == Kind.LISTENER ? T.get("share.is_listening") : distance(id);
        g.text(font, font.plainSubstrByWidth(sub, tw), tx, y + 3 + font.lineHeight, PlayerTheme.TEXT_DIM);

        int right = lx + lw - 4, by = y + 3, bh = ROW_H - 8;
        switch (kind) {
            case NEARBY -> {
                String s = T.get("share.give");
                int bw = font.width(s) + 14;
                button(g, right - bw, by, bw, bh, s, true, "give" + id, () -> ShareClient.I.sendInvite(id, name));
            }
            case SENT -> {
                int dots = (int) (System.nanoTime() / 350_000_000L % 4);
                String s = T.get("share.waiting_answer") + ".".repeat(dots);
                int sw = font.width(T.get("share.waiting_answer") + "...");
                g.text(font, s, right - sw, y + (ROW_H - 2 - font.lineHeight) / 2 + 1, PlayerTheme.TEXT_DIM);
            }
            case LISTENER -> button(g, right - bh, by, bh, bh, "×", false, "kick" + id, () -> ShareClient.I.kick(id));
        }
        g.popMatrix();
    }

    private String distance(UUID id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return "";
        Player p = mc.level.getPlayerByUUID(id);
        return p == null ? "" : T.get("share.distance", Math.max(1, Math.round(p.distanceTo(mc.player))));
    }

    /** Кнопки как в окне плеера: акцентная с тенью или «призрачная». */
    private void button(Canvas g, int x, int y, int w, int h, String label, boolean primary, String key, Runnable action) {
        boolean can = !primary || ShareClient.I.canShare();
        float gl = glow(key, can && in(x, y, w, h));
        int br = Math.min(th.radius + 1, h / 2);
        if (primary) {
            int bg = !can ? th.trackBg : blend(th.accent, th.accentHover, gl);
            Shapes.shadow(g, x, y, w, h, br, 1, 0xFF000000 | PlayerTheme.mix(can ? th.accentRgb : 0x24242A, 0x000000, 0.45f));
            PlayerTheme.rrect(g, x, y, w, h, br, bg);
        } else {
            PlayerTheme.rrect(g, x, y, w, h, br, blend(th.trackBg, th.hover, gl));
        }
        int fg = primary ? (can ? PlayerTheme.TEXT_MAIN : PlayerTheme.TEXT_DIM) : blend(PlayerTheme.TEXT_MID, PlayerTheme.TEXT_MAIN, gl);
        g.text(font, label, x + (w - font.width(label)) / 2, y + (h - font.lineHeight) / 2 + 1, fg);
        if (can) clicks.add(new Click(x, y, w, h, action));
    }

    private void empty(Canvas g, String line1, String line2, int top, int bottom) {
        int cx = px + pw / 2, cy = (top + bottom) / 2 - 16;
        ShareHud.headphones(g, cx - 12, cy - 12, 3, PlayerTheme.argb(th.accentRgb, 140));
        g.text(font, line1, cx - font.width(line1) / 2, cy + 16, PlayerTheme.TEXT_MID);
        String l2 = font.plainSubstrByWidth(line2, pw - 40);
        g.text(font, l2, cx - font.width(l2) / 2, cy + 28, PlayerTheme.TEXT_DIM);
    }

    private static int blend(int from, int to, float t) { return 0xFF000000 | PlayerTheme.mix(from, to, t); }

    private float enter(int order) {
        float ms = (System.nanoTime() - openedNs) / 1e6f - order * 50f;
        float e = Math.max(0f, Math.min(1f, ms / 220f));
        return 1f - (1f - e) * (1f - e) * (1f - e);
    }

    private float glow(String key, boolean on) {
        float v = glow.getOrDefault(key, 0f);
        v += ((on ? 1f : 0f) - v) * (1f - (float) Math.exp(-dt * 16f));
        glow.put(key, v);
        return v;
    }

    private boolean in(int x, int y, int w, int h) { return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h; }

    @Override
    protected boolean onClick(double x, double y, int button, boolean doubleClick) {
        for (Click c : clicks) {
            if (x >= c.x() && x < c.x() + c.w() && y >= c.y() && y < c.y() + c.h()) {
                c.action().run();
                return true;
            }
        }
        if (x < px || x >= px + pw || y < py || y >= py + ph) { onClose(); return true; }
        return super.onClick(x, y, button, doubleClick);
    }

    @Override
    public void onClose() { closeAnimated(() -> Game.open(parent)); }

    @Override
    protected boolean animated() { return true; }

    @Override
    protected void backdrop(Canvas g, float visible) {
        int dim = PlayerTheme.current().dim;
        g.fill(0, 0, width, height, (Math.round((dim >>> 24) * visible) << 24) | (dim & 0xFFFFFF));
    }

    @Override
    public boolean isPauseScreen() { return parent != null && parent.isPauseScreen(); }
}
