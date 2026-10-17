package dev.musicplayer.ui;

import dev.musicplayer.MusicPlayerMod;
import dev.musicplayer.input.Hotkeys;
import dev.musicplayer.util.T;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** Пошаговая инструкция: от первого запуска до входа в Яндекс. Показывает реальные клавиши игрока. */
public class HelpScreen extends Screen {
    private enum Ill { NONE, KEY_M, CARDS, SEARCH, KEYS, LOGIN_BTN, ADDRESS, PASTE, DROP }

    private record Step(String id, Ill ill) {}

    private static final String[] PAGE_IDS = {"quick", "yandex", "other", "keys", "faq"};
    private static final Step[][] PAGES = {
            {new Step("quick.1", Ill.KEY_M), new Step("quick.2", Ill.CARDS), new Step("quick.3", Ill.SEARCH), new Step("quick.4", Ill.KEYS)},
            {new Step("yandex.1", Ill.LOGIN_BTN), new Step("yandex.2", Ill.NONE), new Step("yandex.3", Ill.ADDRESS), new Step("yandex.4", Ill.PASTE)},
            {new Step("other.1", Ill.NONE), new Step("other.2", Ill.NONE), new Step("other.3", Ill.DROP), new Step("other.4", Ill.NONE)},
            null,
            {new Step("faq.1", Ill.NONE), new Step("faq.2", Ill.NONE), new Step("faq.3", Ill.NONE), new Step("faq.4", Ill.NONE)},
    };
    private static final int ILL_W = 118;

    private final Screen parent;
    private int page;
    private PlayerTheme th;
    private int px, py, pw, ph, mouseX, mouseY;
    private final int[] backBtn = new int[4], nextBtn = new int[4];
    // Невидимые ванильные кнопки под нарисованными: клик принимают они (как в окне плеера).
    private Button nextHit, backHit;
    private final Button[] dotHits = new Button[PAGES.length];

    public HelpScreen(Screen parent) {
        super(Component.literal(MusicPlayerMod.NAME));
        this.parent = parent;
    }

    @Override
    protected void init() {
        pw = Math.min(width - 16, 460);
        ph = Math.min(height - 16, 320);
        px = (width - pw) / 2;
        py = (height - ph) / 2;

        nextHit = Hit.make(() -> go(1));
        backHit = Hit.make(() -> go(-1));
        addWidget(nextHit);
        addWidget(backHit);
        for (int i = 0; i < dotHits.length; i++) {
            int n = i;
            dotHits[i] = Hit.make(() -> page = n);
            addWidget(dotHits[i]);
        }
    }

    private void syncHits() {
        Hit.place(nextHit, nextBtn, true);
        Hit.place(backHit, backBtn, page > 0);
        int y = py + ph - 22 - 4, x0 = px + (pw - PAGES.length * 14) / 2;
        for (int i = 0; i < dotHits.length; i++) Hit.place(dotHits[i], x0 + i * 14, y, 14, 14, true);
    }

    private boolean in(int[] r) { return mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3]; }

    private void go(int delta) {
        int n = page + delta;
        if (n < 0) return;
        if (n >= PAGES.length) { onClose(); return; }
        page = n;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        mouseX = (int) event.x();
        mouseY = (int) event.y();
        if (in(nextBtn)) { go(1); return true; }
        if (in(backBtn)) { go(-1); return true; }
        int dotsY = py + ph - 22, x0 = px + (pw - PAGES.length * 14) / 2, i = (mouseX - x0) / 14;
        if (mouseY >= dotsY - 4 && mouseY < dotsY + 10 && mouseX >= x0 && i < PAGES.length) { page = i; return true; }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int k = event.key();
        if (k == 262 || k == 257) { go(1); return true; }
        if (k == 263) { go(-1); return true; }
        if (k == 32 || k == 335) return false;   // пробел не должен жать невидимую кнопку в фокусе
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        go(sy < 0 ? 1 : -1);
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        mouseX = mx; mouseY = my;
        th = PlayerTheme.current();
        super.extractRenderState(g, mx, my, delta);
        g.fill(0, 0, width, height, th.dim);

        th.window(g, px, py, pw, ph);

        g.text(font, "♪ " + MusicPlayerMod.NAME, px + 14, py + 10, th.accent);
        String title = font.plainSubstrByWidth(T.get("help.page." + PAGE_IDS[page]), pw - 40 - font.width("♪ " + MusicPlayerMod.NAME));
        g.text(font, title, px + pw - 14 - font.width(title), py + 10, PlayerTheme.TEXT_MAIN);
        g.fill(px + 10, py + 26, px + pw - 10, py + 27, PlayerTheme.argb(0xFFFFFF, 16));

        int top = py + 34, bottom = py + ph - 36;
        if (PAGES[page] == null) drawKeys(g, top, bottom);
        else drawSteps(g, PAGES[page], top, bottom);
        drawFooter(g);
        syncHits();
    }

    private void drawSteps(GuiGraphicsExtractor g, Step[] steps, int top, int bottom) {
        boolean faq = PAGE_IDS[page].equals("faq");
        boolean ill = pw >= 400;
        int rowH = Math.min(66, (bottom - top) / steps.length);
        int textX = px + 46, textW = pw - 46 - 16 - (ill ? ILL_W + 8 : 0);
        int maxLines = Math.max(1, (rowH - font.lineHeight - 6) / (font.lineHeight + 1));
        for (int i = 0; i < steps.length; i++) {
            Step s = steps[i];
            int y = top + i * rowH;
            PlayerTheme.circle(g, px + 27, y + 11, 10, th.accent);
            String num = faq ? "?" : String.valueOf(i + 1);
            g.text(font, num, px + 27 - font.width(num) / 2, y + 7, PlayerTheme.TEXT_MAIN);
            g.text(font, font.plainSubstrByWidth(T.get("help." + s.id + ".t"), textW), textX, y + 3, PlayerTheme.TEXT_MAIN);
            List<FormattedCharSequence> lines = font.split(Component.literal(T.get("help." + s.id + ".d")), textW);
            for (int k = 0; k < Math.min(maxLines, lines.size()); k++)
                g.text(font, lines.get(k), textX, y + 3 + font.lineHeight + 3 + k * (font.lineHeight + 1), PlayerTheme.TEXT_MID);
            if (ill && s.ill != Ill.NONE) drawIllustration(g, s.ill, px + pw - 16 - ILL_W, y, ILL_W, rowH - 6);
            if (i < steps.length - 1) g.fill(textX, y + rowH - 3, px + pw - 16, y + rowH - 2, PlayerTheme.argb(0xFFFFFF, 10));
        }
    }

    private void drawKeys(GuiGraphicsExtractor g, int top, int bottom) {
        Hotkeys.Action[] all = Hotkeys.Action.values();
        int rowH = Math.min(22, (bottom - top - 30) / all.length);
        int colW = pw - 56;
        for (int i = 0; i < all.length; i++) {
            int y = top + i * rowH;
            String name = Hotkeys.keyName(all[i]);
            int capW = Math.max(22, font.width(name) + 12);
            g.text(font, font.plainSubstrByWidth(all[i].label(), colW - capW - 16), px + 28, y + 6, PlayerTheme.TEXT_MID);
            keycap(g, px + 28 + colW - capW, y + 1, capW, 18, name);
        }
        List<FormattedCharSequence> lines = font.split(Component.literal(T.get("help.keys.note")), pw - 56);
        int ny = top + all.length * rowH + 6;
        for (int k = 0; k < Math.min(2, lines.size()); k++)
            g.text(font, lines.get(k), px + 28, ny + k * (font.lineHeight + 1), PlayerTheme.TEXT_DIM);
    }

    private void keycap(GuiGraphicsExtractor g, int x, int y, int w, int h, String text) {
        PlayerTheme.rrect(g, x, y + 2, w, h, 4, PlayerTheme.argb(0x000000, 120));
        PlayerTheme.rrect(g, x, y, w, h, 4, th.border);
        PlayerTheme.rrect(g, x + 1, y + 1, w - 2, h - 2, 3, th.trackBg);
        String t = font.plainSubstrByWidth(text, w - 4);
        g.text(font, t, x + (w - font.width(t)) / 2, y + (h - font.lineHeight) / 2 + 1, PlayerTheme.TEXT_MAIN);
    }

    private void drawIllustration(GuiGraphicsExtractor g, Ill ill, int x, int y, int w, int h) {
        PlayerTheme.rrect(g, x, y, w, h, 5, th.panel2);
        int cy = y + h / 2;
        switch (ill) {
            case KEY_M -> {
                keycap(g, x + 10, cy - 9, 24, 18, Hotkeys.keyName(Hotkeys.Action.OPEN_PLAYER));
                g.text(font, T.get("help.or"), x + 40, cy - 4, PlayerTheme.TEXT_DIM);
                String b = "♪ " + MusicPlayerMod.NAME;
                int bw = Math.min(w - 66, font.width(b) + 10);
                PlayerTheme.rrect(g, x + 56, cy - 8, bw, 16, 3, th.trackBg);
                g.text(font, font.plainSubstrByWidth(b, bw - 6), x + 59, cy - 4, PlayerTheme.TEXT_MID);
            }
            case CARDS -> {
                int[] colors = {0xFFCC00, 0xFF5500, 0x34D399};
                int cw = (w - 16) / 3;
                for (int i = 0; i < 3; i++) {
                    int cx = x + 6 + i * (cw + 2);
                    PlayerTheme.rrect(g, cx, cy - 14, cw, 28, 3, th.trackBg);
                    PlayerTheme.circle(g, cx + cw / 2, cy - 4, 5, 0xFF000000 | colors[i]);
                    g.fill(cx + 5, cy + 6, cx + cw - 5, cy + 8, PlayerTheme.TEXT_DIM);
                }
            }
            case SEARCH -> {
                PlayerTheme.rrect(g, x + 8, cy - 18, w - 16, 14, 3, th.fieldBg);
                g.text(font, font.plainSubstrByWidth("Daft Punk", w - 26), x + 12, cy - 15, PlayerTheme.TEXT_MID);
                PlayerTheme.rrect(g, x + 8, cy, w - 16, 12, 3, th.rowCurrent);
                PlayerTheme.circle(g, x + w - 18, cy + 6, 5, th.accent);
                PlayerTheme.iconPlay(g, x + w - 18, cy + 6, 6, PlayerTheme.TEXT_MAIN);
                g.fill(x + 12, cy + 5, x + w - 38, cy + 7, th.accent);
            }
            case KEYS -> {
                Hotkeys.Action[] row = {Hotkeys.Action.PREV, Hotkeys.Action.NEXT, Hotkeys.Action.PAUSE, Hotkeys.Action.VOL_DOWN, Hotkeys.Action.VOL_UP};
                int cx = x + 8;
                for (Hotkeys.Action a : row) {
                    String n = Hotkeys.keyName(a);
                    int cw = Math.max(16, font.width(n) + 8);
                    keycap(g, cx, cy - 9, cw, 18, n);
                    cx += cw + 3;
                }
            }
            case LOGIN_BTN -> {
                PlayerTheme.rrect(g, x + 8, cy - 10, w - 16, 20, 4, th.accent);
                String b = "1. " + T.get("help.btn.login");
                g.text(font, font.plainSubstrByWidth(b, w - 24), x + 14, cy - 4, PlayerTheme.TEXT_MAIN);
            }
            case ADDRESS -> {
                PlayerTheme.rrect(g, x + 6, cy - 9, w - 12, 18, 9, th.fieldBg);
                PlayerTheme.rrect(g, x + 9, cy - 6, w - 18, 12, 6, PlayerTheme.argb(th.accentRgb, 90));
                g.text(font, font.plainSubstrByWidth("…#access_token=y0_AgA…", w - 28), x + 14, cy - 4, PlayerTheme.TEXT_MAIN);
                keycap(g, x + 10, cy + 12, 28, 14, "Ctrl");
                keycap(g, x + 42, cy + 12, 14, 14, "C");
            }
            case PASTE -> {
                keycap(g, x + 8, cy - 22, 28, 14, "Ctrl");
                keycap(g, x + 40, cy - 22, 14, 14, "V");
                PlayerTheme.rrect(g, x + 8, cy - 4, w - 16, 12, 3, th.fieldBg);
                g.text(font, "…access_token=…", x + 12, cy - 2, PlayerTheme.TEXT_DIM);
                PlayerTheme.rrect(g, x + 8, cy + 11, w - 16, 12, 3, th.accent);
                String b = "2. " + T.get("help.btn.confirm");
                g.text(font, font.plainSubstrByWidth(b, w - 24), x + 12, cy + 13, PlayerTheme.TEXT_MAIN);
            }
            case DROP -> {
                PlayerTheme.rrect(g, x + 14, cy - 14, 30, 24, 3, PlayerTheme.argb(0x34D399, 200));
                PlayerTheme.rrect(g, x + 14, cy - 17, 14, 5, 2, PlayerTheme.argb(0x34D399, 200));
                for (int i = 0; i < 5; i++) g.fill(x + 58 + i, cy - 6 + i, x + 59 + i, cy - 5 + i + 1, PlayerTheme.TEXT_MID);
                g.fill(x + 58, cy - 6, x + 59, cy + 6, PlayerTheme.TEXT_MID);
                PlayerTheme.rrect(g, x + 70, cy - 12, w - 80, 22, 3, th.trackBg);
                g.text(font, "♪", x + 70 + (w - 80 - font.width("♪")) / 2, cy - 4, th.accent);
            }
            default -> { }
        }
    }

    private void drawFooter(GuiGraphicsExtractor g) {
        int y = py + ph - 28;
        boolean last = page == PAGES.length - 1;
        String back = "‹ " + T.get("btn.back"), next = last ? T.get("btn.done") : T.get("btn.next") + " ›";
        int bw = font.width(back) + 20, nw = font.width(next) + 20;
        backBtn[0] = px + 12; backBtn[1] = y; backBtn[2] = bw; backBtn[3] = 20;
        nextBtn[0] = px + pw - 12 - nw; nextBtn[1] = y; nextBtn[2] = nw; nextBtn[3] = 20;
        if (page > 0) {
            PlayerTheme.rrect(g, backBtn[0], y, bw, 20, 5, in(backBtn) ? th.hover : th.trackBg);
            g.text(font, back, backBtn[0] + 10, y + 6, PlayerTheme.TEXT_MID);
        } else {
            backBtn[2] = 0;
        }
        PlayerTheme.rrect(g, nextBtn[0], y, nw, 20, 5, in(nextBtn) ? th.accentHover : th.accent);
        g.text(font, next, nextBtn[0] + 10, y + 6, PlayerTheme.TEXT_MAIN);

        int x0 = px + (pw - PAGES.length * 14) / 2;
        for (int i = 0; i < PAGES.length; i++)
            PlayerTheme.circle(g, x0 + i * 14 + 5, y + 10, i == page ? 4 : 3, i == page ? th.accent : PlayerTheme.TEXT_DIM);
    }

    @Override
    public void onClose() { Minecraft.getInstance().gui.setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }
}
