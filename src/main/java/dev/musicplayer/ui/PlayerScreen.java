package dev.musicplayer.ui;

import dev.musicplayer.MusicPlayerMod;
import dev.musicplayer.compat.Canvas;
import dev.musicplayer.config.PlayerConfig;
import dev.musicplayer.config.SessionState;
import dev.musicplayer.config.TokenStore;
import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.hud.CoverArt;
import dev.musicplayer.input.Hotkeys;
import dev.musicplayer.lyrics.LrcParser;
import dev.musicplayer.model.LyricLine;
import dev.musicplayer.model.Track;
import dev.musicplayer.provider.YandexMusicProvider;
import dev.musicplayer.util.Browser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/**
 * Окно плеера. Цвета берутся из {@link PlayerTheme} (акцент как у HUD-виджета), кнопки и ползунки
 * нарисованы вручную — ванильными остались только текстовые поля. Клики обрабатываются в
 * невидимые ванильные кнопки, поэтому внешний вид кнопок ничем не ограничен.
 */
public class PlayerScreen extends Screen {
    private enum Kind { PRIMARY, GHOST, CIRCLE, CIRCLE_BIG, BAR, WAVE }
    private enum Icon { NONE, PLAY, PREV, NEXT, VOL, HEART, LEFT, RIGHT, LIKE, SHUFFLE, REPEAT, QUEUE, WAVE, DISLIKE }

    private static final class Btn {
        final Kind kind;
        final int x, y, w, h;
        String label = "";
        Icon icon = Icon.NONE;
        DoubleConsumer click = mx -> { };
        BooleanSupplier enabled = () -> true;
        BooleanSupplier visible = () -> true;

        Btn(Kind kind, int x, int y, int w, int h) { this.kind = kind; this.x = x; this.y = y; this.w = w; this.h = h; }

        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    private static final int ROW_H = 24;
    private static final int VOL_BAR_W = 64;
    private static final int VOL_GROUP_W = 120;

    private final PlayerController ctl = PlayerController.I;
    private final List<Btn> btns = new ArrayList<>();
    private PlayerConfig pc;
    private PlayerTheme th;

    private EditBox searchBox, tokenBox, field;
    private int fx, fy, fw, fh;
    private int px, py, pw, ph, lw, rx, rw;
    private int lx, listTop, rows;
    private int by, cy, coverPx, lyTop, lyBottom;
    private int volGroupX, volBarX;
    private int page = 0;
    private boolean queueMode;
    private boolean wasLoggedIn, draggingVolume, draggingSeek;
    private boolean addAccountMode;
    /** Список сохранённых аккаунтов читается с диска один раз при построении экрана, а не каждый кадр. */
    private java.util.List<TokenStore.Account> savedAccounts = java.util.List.of();
    private String animTrackId;
    private long animStart;
    private double seekFrac;
    private int mouseX, mouseY;

    private List<LyricLine> wrappedFor;
    private List<List<FormattedCharSequence>> wrapped = List.of();
    private int[] tops = new int[0];
    private int totalH, wrapW = -1;
    private double scrollPx = 0, manualPx = 0;

    private final long openStart = System.nanoTime();

    public PlayerScreen() { super(Component.translatable("screen.musicplayer.title")); }

    @Override
    protected void init() {
        pc = PlayerConfig.get();
        pw = Math.min(width - 20, pc.size.w);
        ph = Math.min(height - 20, pc.size.h);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        lw = Math.min(pc.size.listW, pw / 3);
        rx = px + lw + 32;
        rw = pw - lw - 32 - 14;

        wasLoggedIn = ctl.yandex.isLoggedIn();
        String oldQuery = searchBox != null ? searchBox.getValue() : SessionState.get().query;
        searchBox = null; tokenBox = null; field = null;
        btns.clear();

        if (wasLoggedIn) buildMain(oldQuery); else buildLogin();
    }

    private Btn btn(Kind kind, int x, int y, int w, int h, String label, Icon icon, DoubleConsumer click) {
        Btn b = new Btn(kind, x, y, w, h);
        b.label = label; b.icon = icon; b.click = click;
        btns.add(b);
        // Клик принимает невидимая ванильная кнопка (этот путь ввода гарантированно работает), а вид рисуем сами.
        Button hit = Button.builder(Component.empty(), press -> { if (b.visible.getAsBoolean() && b.enabled.getAsBoolean()) b.click.accept(mouseX); })
                .bounds(x, y, w, h).build();
        hit.setAlpha(0f);
        addRenderableWidget(hit);
        return b;
    }

    private EditBox styledField(int x, int y, int w, int h, String hint) {
        fx = x; fy = y; fw = w; fh = h;
        EditBox box = new EditBox(font, x + 6, y + (h - 9) / 2, w - 12, 10, Component.empty());
        box.setBordered(false);
        box.setHint(Component.literal(hint));
        return box;
    }

    private static final int MAX_ACCOUNT_ROWS = 4;

    private void buildLogin() {
        java.util.List<TokenStore.Account> saved = ctl.yandex.savedAccounts();
        savedAccounts = saved;
        if (saved.isEmpty()) addAccountMode = true;
        if (addAccountMode) buildTokenForm(!saved.isEmpty()); else buildAccountList(saved);
        addSettingsButton();
    }

    private void buildAccountList(java.util.List<TokenStore.Account> saved) {
        int cx = px + pw / 2, w = 280, xBtn = 24, gap = 4;
        int mainW = w - xBtn - gap, left = cx - w / 2;
        int y = py + 56;
        int n = Math.min(MAX_ACCOUNT_ROWS, saved.size());
        for (int i = 0; i < n; i++) {
            TokenStore.Account a = saved.get(i);
            String label = font.plainSubstrByWidth(a.displayName(), mainW - 16);
            btn(Kind.PRIMARY, left, y, mainW, 20, label, Icon.NONE, mx -> ctl.loginSaved(a));
            btn(Kind.GHOST, left + mainW + gap, y, xBtn, 20, "x", Icon.NONE, mx -> {
                ctl.yandex.forgetAccount(a);
                rebuildWidgets();
            });
            y += 26;
        }
        btn(Kind.GHOST, left, y + 6, w, 20, "+ Добавить другой аккаунт", Icon.NONE, mx -> {
            addAccountMode = true;
            rebuildWidgets();
        });
    }

    private void buildTokenForm(boolean canGoBack) {
        int cx = px + pw / 2, w = 280;
        btn(Kind.PRIMARY, cx - w / 2, py + 92, w, 20, "1. Открыть страницу входа Яндекса", Icon.NONE,
                mx -> Browser.open(YandexMusicProvider.OAUTH_URL));

        tokenBox = styledField(cx - w / 2, py + 138, w, 22, "Вставьте сюда ссылку из адресной строки");
        tokenBox.setMaxLength(4096);
        field = tokenBox;
        addRenderableWidget(tokenBox);

        btn(Kind.PRIMARY, cx - w / 2, py + 168, w, 20, "2. Подтвердить", Icon.NONE,
                mx -> ctl.submitToken(tokenBox.getValue()));
        if (canGoBack) {
            btn(Kind.GHOST, cx - w / 2, py + 214, w, 18, "← К сохранённым аккаунтам", Icon.NONE, mx -> {
                addAccountMode = false;
                rebuildWidgets();
            });
        }
    }

    private int headerButtonsW;   // ширина кнопок справа в шапке — чтобы текст статуса их не перекрывал

    private void addSettingsButton() {
        String label = "Настройки";
        int w = font.width(label) + 16;
        btn(Kind.GHOST, px + pw - 12 - w, py + 5, w, 16, label, Icon.NONE,
                mx -> Minecraft.getInstance().gui.setScreen(HudConfigScreen.playerTab(this)));
        headerButtonsW = w;
        if (wasLoggedIn) {
            String out = "Выйти";
            int ow = font.width(out) + 16;
            btn(Kind.GHOST, px + pw - 12 - w - 4 - ow, py + 5, ow, 16, out, Icon.NONE, mx -> { page = 0; queueMode = false; addAccountMode = false; ctl.logout(); });
            headerButtonsW = w + 4 + ow;
        }
    }

    private void buildMain(String oldQuery) {
        addSettingsButton();
        lx = px + 12;

        int sbW = 50;
        searchBox = styledField(lx, py + 34, lw - sbW - 4, 18, "Поиск треков…");
        searchBox.setMaxLength(100);
        searchBox.setValue(oldQuery == null ? "" : oldQuery);
        field = searchBox;
        addRenderableWidget(searchBox);
        btn(Kind.PRIMARY, lx + lw - sbW, py + 34, sbW, 18, "Найти", Icon.NONE, mx -> doSearch());
        int qW = 66;
        btn(Kind.GHOST, lx, py + 56, lw - qW - 4, 18, "Любимые", Icon.HEART, mx -> { page = 0; queueMode = false; ctl.loadLiked(); });
        btn(Kind.GHOST, lx + lw - qW, py + 56, qW, 18, "Очередь", Icon.QUEUE, mx -> { page = 0; queueMode = !queueMode; });

        btn(Kind.WAVE, lx, py + 78, lw, 22, "", Icon.WAVE, mx -> { page = 0; queueMode = false; ctl.toggleWave(); });

        listTop = py + 106;
        int listBottom = py + ph - 34;
        rows = Math.max(1, (listBottom - listTop) / ROW_H);
        for (int i = 0; i < rows; i++) {
            final int row = i;
            int ry = listTop + i * ROW_H;
            rowButton(lx, ry, lw - ACT_W * 2 - 4, () -> {
                int idx = page * rows + row;
                if (queueMode) ctl.playQueued(idx);
                else if (waveShown()) ctl.playWaveIndex(idx);
                else { List<Track> res = ctl.results; if (idx < res.size()) ctl.playFrom(res, idx); }
            });
            rowButton(lx + lw - ACT_W * 2 - 2, ry, ACT_W, () -> {
                int idx = page * rows + row;
                if (queueMode) ctl.queueMoveUp(idx);
                else { List<Track> res = shown(); if (idx < res.size()) ctl.addToQueue(res.get(idx)); }
            });
            rowButton(lx + lw - ACT_W, ry, ACT_W, () -> {
                int idx = page * rows + row;
                if (queueMode) ctl.queueRemove(idx);
                else { List<Track> res = shown(); if (idx < res.size()) ctl.playNext(res.get(idx)); }
            });
        }
        Btn prevPage = btn(Kind.GHOST, lx, py + ph - 28, 34, 18, "", Icon.LEFT, mx -> page = Math.max(0, page - 1));
        prevPage.enabled = () -> page > 0;
        Btn nextPage = btn(Kind.GHOST, lx + lw - 34, py + ph - 28, 34, 18, "", Icon.RIGHT, mx -> page = Math.min(maxPage(), page + 1));
        nextPage.enabled = () -> page < maxPage();

        coverPx = pc.showCover ? Math.min(pc.coverSize.px, Math.max(40, ph - 230)) : 0;
        by = py + ph - 62;
        cy = py + ph - 24;
        lyTop = py + 30 + Math.max(coverPx, 44) + 10;
        lyBottom = by - 12;

        Btn seek = btn(Kind.BAR, rx, by - 6, rw, 14, "", Icon.NONE, mx -> {
            Track t = ctl.current;
            if (t != null) { draggingSeek = true; seekFrac = clamp01((mx - rx) / rw); }
        });
        seek.enabled = () -> ctl.current != null && !ctl.idle;

        int mw1 = 64, mw2 = 84, mgap = 6, mx0 = rx + (rw - mw1 - mw2 - mgap) / 2;
        btn(Kind.GHOST, mx0, by + 8, mw1, 12, "", Icon.SHUFFLE, mx -> ctl.toggleShuffle());
        btn(Kind.GHOST, mx0 + mw1 + mgap, by + 8, mw2, 12, "", Icon.REPEAT, mx -> ctl.cycleRepeat());

        btn(Kind.CIRCLE, rx + rw - 18, py + 32, 18, 18, "", Icon.LIKE, mx -> ctl.toggleLike()).enabled = () -> ctl.current != null;
        Btn dis = btn(Kind.CIRCLE, rx + rw - 40, py + 32, 18, 18, "", Icon.DISLIKE, mx -> ctl.waveDislike());
        dis.enabled = () -> ctl.current != null && ctl.waveActive;
        dis.visible = () -> ctl.waveActive;

        int groupCx = rx + (rw - VOL_GROUP_W) / 2;
        int x = groupCx - 82;
        BooleanSupplier hasTrack = () -> ctl.current != null;
        BooleanSupplier playing = () -> ctl.current != null && !ctl.idle;
        btn(Kind.GHOST, x, cy - 9, 30, 18, "-10", Icon.NONE, mx -> ctl.seekBy(-10_000)).enabled = playing;
        x += 38;
        btn(Kind.CIRCLE, x, cy - 11, 22, 22, "", Icon.PREV, mx -> ctl.prev()).enabled = hasTrack;
        x += 30;
        btn(Kind.CIRCLE_BIG, x, cy - 14, 28, 28, "", Icon.PLAY, mx -> ctl.togglePause()).enabled = hasTrack;
        x += 36;
        btn(Kind.CIRCLE, x, cy - 11, 22, 22, "", Icon.NEXT, mx -> ctl.next()).enabled = hasTrack;
        x += 30;
        btn(Kind.GHOST, x, cy - 9, 30, 18, "+10", Icon.NONE, mx -> ctl.seekBy(10_000)).enabled = playing;

        volGroupX = rx + rw - VOL_GROUP_W;
        btn(Kind.CIRCLE, volGroupX, cy - 9, 18, 18, "", Icon.VOL, mx -> ctl.toggleMute());
        volBarX = volGroupX + 24;
        btn(Kind.BAR, volBarX, cy - 7, VOL_BAR_W, 14, "", Icon.NONE, mx -> { draggingVolume = true; setVolumeFromMouse(mx); });
    }

    private static final int ACT_W = 16;

    private void rowButton(int x, int y, int w, Runnable action) {
        Button b = Button.builder(Component.empty(), press -> action.run()).bounds(x, y, w, ROW_H - 2).build();
        b.setAlpha(0f);
        addRenderableWidget(b);
    }

    private List<Track> shown() {
        if (queueMode) return ctl.queueSnapshot();
        return waveShown() ? ctl.waveSnapshot() : ctl.results;
    }

    private boolean waveShown() { return ctl.waveActive && ctl.waveView; }

    private void doSearch() {
        if (searchBox == null) return;
        page = 0;
        SessionState.get().query = searchBox.getValue();
        SessionState.markDirty();
        ctl.search(searchBox.getValue());
    }

    private int maxPage() { return Math.max(0, (shown().size() - 1) / Math.max(1, rows)); }

    private void setVolumeFromMouse(double mx) { ctl.setVolume((float) clamp01((mx - volBarX) / VOL_BAR_W)); }

    private static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }

    @Override
    public void removed() {
        if (searchBox != null) { SessionState.get().query = searchBox.getValue(); SessionState.markDirty(); }
        super.removed();
    }

    @Override
    public void tick() {
        if (ctl.yandex.isLoggedIn() != wasLoggedIn) rebuildWidgets();
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingVolume) { setVolumeFromMouse(mouseX); return true; }
        if (draggingSeek) { seekFrac = clamp01((mouseX - rx) / rw); return true; }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingVolume = false;
        if (draggingSeek) {   // перемотка один раз при отпускании — не дёргаем аудио во время перетаскивания
            draggingSeek = false;
            Track t = ctl.current;
            if (t != null) ctl.seekTo((long) (seekFrac * t.durationMs()));
            return true;
        }
        return super.mouseReleased(event);
    }

    /**
     * Колесо: над громкостью — громкость, над списком — страницы, над текстом — прокрутка
     * (для несинхронизированного текста).
     */
    @Override
    public boolean mouseScrolled(double mx0, double my0, double sx, double sy) {
        double mx = mouseX, my = mouseY;
        if (wasLoggedIn) {
            if (mx >= volGroupX && mx < volGroupX + VOL_GROUP_W && my >= cy - 12 && my < cy + 12) {
                ctl.changeVolume(sy > 0 ? 0.05f : -0.05f);
                return true;
            }
            if (mx < rx - 8) {
                if (sy < 0) page = Math.min(maxPage(), page + 1); else if (sy > 0) page = Math.max(0, page - 1);
                return true;
            }
        }
        int viewH = lyBottom - lyTop;
        manualPx = Math.max(0, Math.min(Math.max(0, totalH - viewH), manualPx - sy * 30));
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        boolean searching = searchBox != null && searchBox.isFocused();
        boolean typing = searching || (tokenBox != null && tokenBox.isFocused());
        if (typing) {
            if (searching && (key == 257 || key == 335)) { doSearch(); return true; }
            return super.keyPressed(event);
        }
        if (wasLoggedIn) {
            Hotkeys.Action a = Hotkeys.transportFor(event);
            if (a != null) { Hotkeys.perform(a); return true; }
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        mouseX = mx; mouseY = my;
        th = PlayerTheme.current();
        int r = th.radius;

        float ot = Math.min(1f, (System.nanoTime() - openStart) / 260_000_000f);
        float oe = 1f - (1f - ot) * (1f - ot) * (1f - ot);
        int dimA = Math.round((th.dim >>> 24) * oe);
        g.fill(0, 0, width, height, (dimA << 24) | (th.dim & 0xFFFFFF));
        boolean opening = oe < 1f;
        if (opening) {
            float sc = 0.94f + 0.06f * oe;
            float ccx = px + pw / 2f, ccy = py + ph / 2f;
            g.pose().pushMatrix();
            g.pose().translate(ccx, ccy + (1f - oe) * 12f);
            g.pose().scale(sc, sc);
            g.pose().translate(-ccx, -ccy);
        }
        PlayerTheme.rrect(g, px - 1, py - 1, pw + 2, ph + 2, r + 1, th.border);
        PlayerTheme.rrectV(g, px, py, pw, ph, r, th.panelTop, th.panelBottom);
        g.fill(px + r, py, px + pw - r, py + 2, th.accent);
        g.fill(px + 10, py + 24, px + pw - 10, py + 25, PlayerTheme.argb(0xFFFFFF, 16));

        String title = "♪ " + MusicPlayerMod.NAME;
        g.text(font, title, px + 14, py + 9, th.accent);
        String acc = ctl.yandex.accountName();
        if (wasLoggedIn && !acc.isEmpty())
            g.text(font, font.plainSubstrByWidth("· " + acc, Math.max(30, pw / 4)), px + 14 + font.width(title) + 6, py + 9, PlayerTheme.TEXT_DIM);
        String st = ctl.status;
        int pct = ctl.loadingPercent();
        if (pct >= 0 && st.startsWith("Загружаю трек")) st = "Загружаю трек… " + pct + "%";
        if (!st.isEmpty()) {
            String shown = font.plainSubstrByWidth(st, Math.max(40, pw - 190 - headerButtonsW));
            g.text(font, shown, px + pw - 12 - headerButtonsW - 8 - font.width(shown), py + 9, PlayerTheme.WARN);
        }

        if (!wasLoggedIn) renderLogin(g); else renderMain(g);

        drawField(g);
        for (Btn b : btns) drawBtn(g, b);

        super.extractRenderState(g, mx, my, delta);   // текстовые поля поверх нашей рамки

        if (opening) {
            float inv = 1f - oe;
            PlayerTheme.rrect(g, px - 1, py - 1, pw + 2, ph + 2, r + 1,
                    PlayerTheme.argb(0x12131A, Math.round(255 * inv * inv)));
            g.pose().popMatrix();
        }
    }

    private void drawField(GuiGraphicsExtractor g) {
        if (field == null) return;
        int br = Math.min(th.radius, 4);
        PlayerTheme.rrect(g, fx - 1, fy - 1, fw + 2, fh + 2, br + 1, field.isFocused() ? th.accent : th.trackBg);
        PlayerTheme.rrect(g, fx, fy, fw, fh, br, th.fieldBg);
    }

    private void renderLogin(GuiGraphicsExtractor g) {
        int cx = px + pw / 2;
        if (!addAccountMode) {
            center(g, "Выберите аккаунт", cx, py + 36, PlayerTheme.TEXT_MAIN);
            center(g, "Токен сохранён на этом компьютере — вставлять его заново не нужно.", cx, py + 46, PlayerTheme.TEXT_DIM);
            int n = Math.min(MAX_ACCOUNT_ROWS, savedAccounts.size());
            int more = savedAccounts.size() - n;
            if (more > 0) center(g, "…и ещё " + more + " (показаны последние " + n + ")", cx, py + 56 + n * 26 + 34, PlayerTheme.TEXT_DIM);
            center(g, "«x» — забыть аккаунт и удалить его токен", cx, py + ph - 20, PlayerTheme.TEXT_DIM);
            return;
        }
        center(g, "Вход в Яндекс Музыку", cx, py + 36, PlayerTheme.TEXT_MAIN);
        center(g, "Откроется страница входа Яндекса в браузере.", cx, py + 52, PlayerTheme.TEXT_MID);
        center(g, "После входа скопируйте ВСЮ ссылку из адресной строки и вставьте ниже (Ctrl+V).", cx, py + 64, PlayerTheme.TEXT_MID);
        center(g, "Токен хранится у вас локально, в config/musicplayer.json", cx, py + 198, PlayerTheme.TEXT_DIM);
    }

    private void renderMain(GuiGraphicsExtractor g) {
        PlayerTheme.rrect(g, px + 8, py + 26, lw + 8, ph - 34, Math.max(0, th.radius - 2), th.panel2);
        drawList(g);
        center(g, (page + 1) + " / " + (maxPage() + 1), lx + lw / 2, py + ph - 24, PlayerTheme.TEXT_DIM);
        drawVolume(g);

        Track t = ctl.current;
        if (t == null) {
            center(g, "Найдите трек или откройте «Любимые треки»", rx + rw / 2, py + 40 + (by - py - 40) / 2 - 8, PlayerTheme.TEXT_MID);
            drawProgress(g, null);
            return;
        }

        long nowNs = System.nanoTime();
        if (t.id() != null && !t.id().equals(animTrackId)) { animTrackId = t.id(); animStart = nowNs; }
        float ap = Math.min(1f, (nowNs - animStart) / 450_000_000f);
        float ae = 1f - (1f - ap) * (1f - ap) * (1f - ap);
        int slide = Math.round((1f - ae) * 14);
        int aa = Math.max(5, Math.round(255 * ae));
        double secs = nowNs / 1_000_000_000.0;
        boolean wave = ctl.waveActive;

        Canvas c = new Canvas(g);
        int textX = rx;
        if (coverPx > 0) {
            CoverArt.offerBig(t.id(), t.coverUrl());
            Identifier art = CoverArt.getBig(t.id());
            int cs = Math.round(coverPx * (0.9f + 0.1f * ae));
            int cx0 = rx + (coverPx - cs) / 2, cy0 = py + 32 + (coverPx - cs) / 2;
            if (wave && ctl.yandex.isPlaying()) drawWaveGlow(g, rx, py + 32, coverPx, secs);
            PlayerTheme.rrect(g, cx0 + 2, cy0 + 4, cs, cs, 8, PlayerTheme.argb(0x000000, Math.round(0x55 * ae)));
            if (art != null) {
                c.texture(art, cx0, cy0, cs, cs, CoverArt.BIG_SIZE, ae);
            } else {
                PlayerTheme.rrect(g, cx0, cy0, cs, cs, 8, th.trackBg);
                center(g, "♪", cx0 + cs / 2, cy0 + (cs - font.lineHeight) / 2 + 1, PlayerTheme.TEXT_DIM);
            }
            textX = rx + coverPx + 14;
        }

        int availW = rx + rw - textX - (wave ? 46 : 24);
        float ts = coverPx >= 60 ? 1.5f : 1f;
        int titleColor = PlayerTheme.argb(pc.accentText ? th.accent : PlayerTheme.TEXT_MAIN, aa);
        int midColor = PlayerTheme.argb(PlayerTheme.TEXT_MID, aa);
        int dimColor = PlayerTheme.argb(PlayerTheme.TEXT_DIM, aa);
        boolean showBadge = wave && coverPx >= 60;
        int blockH = Math.round(font.lineHeight * ts) + 4 + font.lineHeight + (coverPx >= 60 ? 2 + font.lineHeight : 0) + (showBadge ? 16 : 0);
        int ty = py + 32 + Math.max(0, (Math.max(coverPx, 44) - blockH) / 2);
        c.push(ts);
        c.text(c.trim(nz(t.title()), Math.round(availW / ts)), Math.round((textX + slide) / ts), Math.round(ty / ts), titleColor, true);
        c.pop();
        int ay = ty + Math.round(font.lineHeight * ts) + 4;
        g.text(font, font.plainSubstrByWidth(nz(t.artist()), availW), textX + slide, ay, midColor);
        int nextY = ay + font.lineHeight + 2;
        if (coverPx >= 60 && t.album() != null && !t.album().isBlank()) {
            g.text(font, font.plainSubstrByWidth(t.album(), availW), textX + slide, nextY, dimColor);
            nextY += font.lineHeight + 2;
        }
        if (showBadge) {
            String bl = "Моя волна";
            int bw = font.width(bl) + 14;
            drawWaveGradient(g, textX + slide, nextY + 3, bw, 11, 5, aa, secs * 0.35);
            g.text(font, bl, textX + slide + 7, nextY + 5, PlayerTheme.argb(0xFFFFFF, aa));
        }

        if (pc.showLyrics && lyBottom - lyTop > 24) renderLyrics(g);

        drawProgress(g, t);
    }

    private void renderLyrics(GuiGraphicsExtractor g) {
        PlayerTheme.rrect(g, rx - 4, lyTop - 4, rw + 8, lyBottom - lyTop + 8, 4, PlayerTheme.argb(th.panel2, 150));
        int midX = rx + rw / 2;

        if (ctl.idle) { center(g, "Нажмите кнопку воспроизведения", midX, (lyTop + lyBottom) / 2 - 4, PlayerTheme.TEXT_DIM); return; }
        List<LyricLine> src = ctl.lyrics;
        if (src.isEmpty()) {
            center(g, ctl.lyricsLoading ? "Ищу текст…" : "Текст не найден", midX, (lyTop + lyBottom) / 2 - 4, PlayerTheme.TEXT_DIM);
            return;
        }
        ensureWrapped(src);
        long pos = ctl.yandex.positionMs();
        boolean synced = src.get(0).timeMs() != LyricLine.UNSYNCED;
        int cur = synced ? LrcParser.currentIndex(src, pos) : -1;
        int lh = font.lineHeight + 2;
        int viewH = lyBottom - lyTop;

        double target = synced ? (cur >= 0 ? tops[cur] + wrapped.get(cur).size() * lh / 2.0 : 0) : manualPx;
        scrollPx += (target - scrollPx) * 0.15;
        int origin = synced ? lyTop + viewH / 2 : lyTop + 4;

        for (int i = 0; i < wrapped.size(); i++) {
            int y = origin + tops[i] - (int) scrollPx;
            int h = wrapped.get(i).size() * lh;
            if (y < lyTop || y + h > lyBottom) continue;
            int color;
            if (!synced) color = PlayerTheme.TEXT_MID;
            else if (i == cur) color = th.accent;
            else {
                float near = Math.max(0f, 1f - Math.abs(i - cur) / 4f);
                color = 0xFF000000 | PlayerTheme.mix(PlayerTheme.TEXT_DIM, PlayerTheme.TEXT_MID, near);
            }
            for (int k = 0; k < wrapped.get(i).size(); k++)
                g.text(font, wrapped.get(i).get(k), rx + 4, y + k * lh, color);
        }
    }

    private void drawProgress(GuiGraphicsExtractor g, Track t) {
        long dur = t == null ? 0 : Math.max(1, t.durationMs());
        long pos = t == null || ctl.idle ? 0 : ctl.yandex.positionMs();
        if (draggingSeek) pos = (long) (seekFrac * dur);
        boolean hover = t != null && !ctl.idle
                && (draggingSeek || mouseX >= rx && mouseX < rx + rw && mouseY >= by - 6 && mouseY < by + 10);

        int bh = hover ? 6 : 4;
        int barY = by + 2 - bh / 2;
        PlayerTheme.rrect(g, rx, barY, rw, bh, bh / 2, th.trackBg);
        int fill = dur == 0 ? 0 : (int) (rw * Math.min(1.0, pos / (double) dur));
        if (fill > 0) PlayerTheme.rrect(g, rx, barY, Math.max(bh, fill), bh, bh / 2, th.accent);
        if (t != null) {
            int kx = Math.max(rx + 4, Math.min(rx + rw - 4, rx + fill));
            PlayerTheme.circle(g, kx, by + 2, hover ? 6 : 5, th.accent);
            PlayerTheme.circle(g, kx, by + 2, hover ? 3 : 2, PlayerTheme.TEXT_MAIN);
        }

        g.text(font, fmt(pos), rx, by + 10, PlayerTheme.TEXT_MID);
        String d = fmt(dur);
        g.text(font, d, rx + rw - font.width(d), by + 10, PlayerTheme.TEXT_DIM);

        if (hover) {
            long hoverMs = (long) (clamp01((mouseX - rx) / (double) rw) * dur);
            String s = fmt(hoverMs);
            int sx = Math.max(rx, Math.min(rx + rw - font.width(s) - 8, mouseX - font.width(s) / 2 - 4));
            PlayerTheme.rrect(g, sx, by - 20, font.width(s) + 8, 12, 3, th.fieldBg);
            g.text(font, s, sx + 4, by - 18, PlayerTheme.TEXT_MAIN);
        }
    }

    private void drawVolume(GuiGraphicsExtractor g) {
        int bh = 4;
        int barY = cy - bh / 2;
        PlayerTheme.rrect(g, volBarX, barY, VOL_BAR_W, bh, 2, th.trackBg);
        int fill = Math.round(VOL_BAR_W * ctl.volume);
        if (fill > 0) PlayerTheme.rrect(g, volBarX, barY, Math.max(bh, fill), bh, 2, th.accent);
        PlayerTheme.circle(g, Math.max(volBarX + 3, Math.min(volBarX + VOL_BAR_W - 3, volBarX + fill)), cy, 4, PlayerTheme.TEXT_MAIN);
        g.text(font, Math.round(ctl.volume * 100) + "%", volBarX + VOL_BAR_W + 6, cy - font.lineHeight / 2 + 1, PlayerTheme.TEXT_DIM);
    }

    private void drawList(GuiGraphicsExtractor g) {
        List<Track> res = shown();
        if (res.isEmpty()) {
            center(g, queueMode ? "Очередь пуста" : ctl.waveLoading ? "Настраиваю волну…" : "Здесь появятся треки", lx + lw / 2, listTop + 14, PlayerTheme.TEXT_DIM);
            if (queueMode) center(g, "«+» у трека — в очередь", lx + lw / 2, listTop + 28, PlayerTheme.TEXT_DIM);
            return;
        }
        page = Math.max(0, Math.min(page, maxPage()));
        Track cur = ctl.current;
        String curId = cur == null ? null : cur.id();
        int textW = lw - 18 - ACT_W * 2;
        for (int i = 0; i < rows; i++) {
            int idx = page * rows + i;
            if (idx >= res.size()) break;
            Track t = res.get(idx);
            int y = listTop + i * ROW_H;
            boolean isCur = t.id() != null && t.id().equals(curId);
            boolean hov = mouseX >= lx && mouseX < lx + lw && mouseY >= y && mouseY < y + ROW_H - 2;
            if (isCur) {
                PlayerTheme.rrect(g, lx, y, lw, ROW_H - 2, 4, th.rowCurrent);
                drawEqualizer(g, lx + 5, y + 4, ROW_H - 10, ctl.yandex.isPlaying());
            } else if (hov) {
                PlayerTheme.rrect(g, lx, y, lw, ROW_H - 2, 4, th.hover);
            }
            int tx = isCur ? lx + 16 : lx + 8, tw = isCur ? textW - 8 : textW;
            g.text(font, font.plainSubstrByWidth(nz(t.title()), tw), tx, y + 3,
                    isCur ? th.accent : PlayerTheme.TEXT_MAIN);
            g.text(font, font.plainSubstrByWidth(nz(t.artist()), tw), tx, y + 3 + font.lineHeight,
                    PlayerTheme.TEXT_DIM);
            if (!hov && !isCur && ctl.yandex.isCached(t.id()))
                PlayerTheme.circle(g, lx + lw - ACT_W - 4, y + (ROW_H - 2) / 2, 2, PlayerTheme.argb(th.accentRgb, 150));
            if (hov || isCur) {
                String a1 = queueMode ? "^" : "+", a2 = queueMode ? "x" : ">";
                int ax1 = lx + lw - ACT_W * 2 - 2, ax2 = lx + lw - ACT_W;
                boolean h1 = mouseX >= ax1 && mouseX < ax1 + ACT_W && mouseY >= y && mouseY < y + ROW_H - 2;
                boolean h2 = mouseX >= ax2 && mouseX < ax2 + ACT_W && mouseY >= y && mouseY < y + ROW_H - 2;
                PlayerTheme.rrect(g, ax1 + 1, y + 3, ACT_W - 2, ROW_H - 8, 3, h1 ? th.accent : th.trackBg);
                PlayerTheme.rrect(g, ax2 + 1, y + 3, ACT_W - 2, ROW_H - 8, 3, h2 ? th.accent : th.trackBg);
                center(g, a1, ax1 + ACT_W / 2, y + (ROW_H - 2 - font.lineHeight) / 2 + 1, PlayerTheme.TEXT_MAIN);
                center(g, a2, ax2 + ACT_W / 2, y + (ROW_H - 2 - font.lineHeight) / 2 + 1, PlayerTheme.TEXT_MAIN);
            }
        }
    }

    private void drawBtn(GuiGraphicsExtractor g, Btn b) {
        if (b.kind == Kind.BAR) return;
        if (!b.visible.getAsBoolean()) return;
        if (b.kind == Kind.WAVE) { drawWaveButton(g, b); return; }
        boolean enabled = b.enabled.getAsBoolean();
        boolean hov = enabled && b.contains(mouseX, mouseY);
        int br = Math.min(th.radius + 1, b.h / 2);
        int cxB = b.x + b.w / 2, cyB = b.y + b.h / 2;

        switch (b.kind) {
            case PRIMARY -> {
                int bg = !enabled ? th.trackBg : hov ? th.accentHover : th.accent;
                PlayerTheme.rrect(g, b.x, b.y + 1, b.w, b.h, br, 0x44000000);
                PlayerTheme.rrect(g, b.x, b.y, b.w, b.h, br, bg);
                center(g, b.label, cxB, b.y + (b.h - font.lineHeight) / 2 + 1, PlayerTheme.TEXT_MAIN);
            }
            case GHOST -> {
                PlayerTheme.rrect(g, b.x, b.y, b.w, b.h, br, hov ? th.hover : th.trackBg);
                int fg = !enabled ? PlayerTheme.TEXT_DIM : hov ? PlayerTheme.TEXT_MAIN : PlayerTheme.TEXT_MID;
                String label = b.label;
                switch (b.icon) {
                    case SHUFFLE -> { label = "Случайно"; if (ctl.shuffle) fg = th.accent; }
                    case REPEAT -> { label = "Повтор: " + ctl.repeat.label; if (ctl.repeat != PlayerController.Repeat.OFF) fg = th.accent; }
                    case QUEUE -> { label = "Очередь " + ctl.queueSnapshot().size(); if (queueMode) fg = th.accent; }
                    default -> { }
                }
                if (b.icon == Icon.HEART) {
                    int tw = font.width(b.label);
                    int gx = cxB - (PlayerTheme.HEART_W + 6 + tw) / 2;
                    PlayerTheme.iconHeart(g, gx, cyB - PlayerTheme.HEART_H / 2, th.accent);
                    g.text(font, b.label, gx + PlayerTheme.HEART_W + 6, b.y + (b.h - font.lineHeight) / 2 + 1, fg);
                } else if (b.icon == Icon.LEFT || b.icon == Icon.RIGHT) {
                    PlayerTheme.tri(g, cxB - 2, cyB, 5, 9, b.icon == Icon.RIGHT, fg);
                } else {
                    center(g, label, cxB, b.y + (b.h - font.lineHeight) / 2 + 1, fg);
                }
            }
            case CIRCLE, CIRCLE_BIG -> {
                boolean big = b.kind == Kind.CIRCLE_BIG;
                int r = b.w / 2;
                int bg = big ? (!enabled ? th.trackBg : hov ? th.accentHover : th.accent) : (hov ? th.hover : th.trackBg);
                if (big) PlayerTheme.circle(g, cxB, cyB + 1, r, 0x44000000);
                PlayerTheme.circle(g, cxB, cyB, r, bg);
                int fg = big || hov ? PlayerTheme.TEXT_MAIN : (enabled ? PlayerTheme.TEXT_MID : PlayerTheme.TEXT_DIM);
                drawIcon(g, b.icon, cxB, cyB, fg);
            }
            default -> { }
        }
    }

    private void drawIcon(GuiGraphicsExtractor g, Icon icon, int cx, int cy, int color) {
        switch (icon) {
            case PLAY -> {
                if (ctl.yandex.isPlaying()) PlayerTheme.iconPause(g, cx, cy, 11, color);
                else PlayerTheme.iconPlay(g, cx, cy, 12, color);
            }
            case LIKE -> PlayerTheme.iconHeart(g, cx - PlayerTheme.HEART_W / 2, cy - PlayerTheme.HEART_H / 2,
                    ctl.isLiked(ctl.current) ? th.accent : color);
            case DISLIKE -> {
                for (int i = 0; i < 6; i++) {
                    g.fill(cx - 3 + i, cy - 3 + i, cx - 2 + i, cy - 2 + i, color);
                    g.fill(cx - 3 + i, cy + 2 - i, cx - 2 + i, cy + 3 - i, color);
                }
            }
            case PREV -> PlayerTheme.iconSkip(g, cx, cy, 8, color, false);
            case NEXT -> PlayerTheme.iconSkip(g, cx, cy, 8, color, true);
            case VOL -> {
                float v = ctl.volume;
                PlayerTheme.iconVolume(g, cx - 1, cy, v <= 0f ? 0 : v < 0.5f ? 1 : 2, color);
            }
            default -> { }
        }
    }

    private static final int[] WAVE_PALETTE = {0xFF8A3D, 0xFF4D8D, 0xA05CFF, 0x3D8BFF, 0x2FD6C3};

    private static int wavePaletteAt(double t) {
        int n = WAVE_PALETTE.length;
        t = t - Math.floor(t / n) * n;
        int i = (int) t;
        return PlayerTheme.mix(WAVE_PALETTE[i], WAVE_PALETTE[(i + 1) % n], (float) (t - i));
    }

    private void drawWaveGradient(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int alpha, double phase) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        for (int col = 0; col < w; col++) {
            double d = 0;
            if (col < r) d = r - 0.5 - col; else if (col >= w - r) d = col - (w - r - 0.5);
            int in = d <= 0 ? 0 : (int) Math.round(r - Math.sqrt(Math.max(0, r * r - d * d)));
            double t = phase * WAVE_PALETTE.length + col / (double) Math.max(1, w) * 2.2;
            int shade = (int) Math.round(Math.sin((col / (double) w) * Math.PI) * 18);
            int rgb = PlayerTheme.mix(wavePaletteAt(t), 0xFFFFFF, shade / 255f * 2f);
            g.fill(x + col, y + in, x + col + 1, y + h - in, PlayerTheme.argb(rgb, alpha));
        }
    }

    private void drawWaveButton(GuiGraphicsExtractor g, Btn b) {
        boolean hov = b.contains(mouseX, mouseY);
        boolean on = ctl.waveActive;
        double secs = System.nanoTime() / 1_000_000_000.0;
        int r = Math.min(th.radius + 3, b.h / 2);
        if (on || hov) {
            float pulse = on ? (float) (0.5 + 0.5 * Math.sin(secs * 2.4)) : 0.4f;
            for (int e = 3; e >= 1; e--)
                PlayerTheme.rrect(g, b.x - e, b.y - e, b.w + e * 2, b.h + e * 2, r + e,
                        PlayerTheme.argb(wavePaletteAt(secs * 0.35 * WAVE_PALETTE.length), Math.round((10 + 22 * pulse) / e)));
        }
        drawWaveGradient(g, b.x, b.y, b.w, b.h, r, hov ? 255 : 235, secs * 0.18);

        boolean playing = on && ctl.yandex.isPlaying();
        int bx = b.x + 9, base = b.y + b.h - 6;
        for (int i = 0; i < 4; i++) {
            double k = playing ? 0.5 + 0.5 * Math.sin(secs * (5.0 + i * 1.7) + i * 1.3) : 0.25;
            int bh = 3 + (int) Math.round(k * (b.h - 12));
            g.fill(bx + i * 3, base - bh, bx + i * 3 + 2, base, 0xFFFFFFFF);
        }

        String label;
        if (ctl.waveLoading) {
            int dots = (int) (secs * 3) % 4;
            label = "Запускаю" + ".".repeat(dots);
        } else label = on && ctl.waveView ? "Моя волна · стоп" : "Моя волна";
        int tx = b.x + b.w / 2 + 6;
        int ty = b.y + (b.h - font.lineHeight) / 2 + 1;
        g.text(font, label, tx - font.width(label) / 2 + 1, ty + 1, 0x66000000);
        g.text(font, label, tx - font.width(label) / 2, ty, 0xFFFFFFFF);
    }

    private void drawWaveGlow(GuiGraphicsExtractor g, int x, int y, int size, double secs) {
        float pulse = (float) (0.5 + 0.5 * Math.sin(secs * 2.0));
        int rgb = wavePaletteAt(secs * 0.5);
        for (int e = 7; e >= 1; e--) {
            float spread = e * (0.75f + 0.25f * pulse);
            int a = Math.round((7 - e + 1) * (2.2f + 2.6f * pulse));
            int gx = Math.round(spread);
            PlayerTheme.rrect(g, x - gx, y - gx, size + gx * 2, size + gx * 2, 8 + gx, PlayerTheme.argb(rgb, a));
        }
    }

    private void drawEqualizer(GuiGraphicsExtractor g, int x, int y, int h, boolean playing) {
        double secs = System.nanoTime() / 1_000_000_000.0;
        for (int i = 0; i < 3; i++) {
            double k = playing ? 0.5 + 0.5 * Math.sin(secs * (6.0 + i * 2.1) + i * 1.9) : 0.2;
            int bh = 2 + (int) Math.round(k * (h - 2));
            g.fill(x + i * 3, y + h - bh, x + i * 3 + 2, y + h, th.accent);
        }
    }

    private void ensureWrapped(List<LyricLine> src) {
        int w = rw - 8;
        if (src == wrappedFor && w == wrapW) return;
        wrapped = new ArrayList<>();
        tops = new int[src.size()];
        int y = 0, lh = font.lineHeight + 2;
        for (int i = 0; i < src.size(); i++) {
            List<FormattedCharSequence> l = new ArrayList<>(font.split(Component.literal(src.get(i).text()), w));
            if (l.isEmpty()) l.add(FormattedCharSequence.EMPTY);
            wrapped.add(l);
            tops[i] = y;
            y += l.size() * lh + 8;
        }
        totalH = y; wrappedFor = src; wrapW = w;
        manualPx = 0; scrollPx = 0;
    }

    private void center(GuiGraphicsExtractor g, String s, int cx, int y, int color) {
        g.text(font, s, cx - font.width(s) / 2, y, color);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String fmt(long ms) {
        long s = Math.max(0, ms) / 1000;
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    @Override
    public boolean isPauseScreen() { return false; }   // музыка и игра не должны останавливаться
}
