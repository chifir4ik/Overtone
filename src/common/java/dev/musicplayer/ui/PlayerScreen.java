package dev.musicplayer.ui;

import dev.musicplayer.compat.Tex;
import dev.musicplayer.compat.Game;
import dev.musicplayer.compat.Keys;
import dev.musicplayer.compat.KeyInput;
import dev.musicplayer.compat.ModScreen;
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
import dev.musicplayer.model.MusicEntity;
import dev.musicplayer.model.Page;
import dev.musicplayer.model.Platform;
import dev.musicplayer.model.Track;
import dev.musicplayer.provider.MusicProvider;
import dev.musicplayer.provider.YandexMusicProvider;
import dev.musicplayer.lyrics.TrackLyrics;
import dev.musicplayer.util.Browser;
import dev.musicplayer.util.T;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * Окно плеера. Цвета берутся из {@link PlayerTheme} (акцент как у HUD-виджета), кнопки и ползунки
 * нарисованы вручную — ванильными остались только текстовые поля. Клики обрабатываются в
 * невидимые ванильные кнопки, поэтому внешний вид кнопок ничем не ограничен.
 */
public class PlayerScreen extends ModScreen {
    private enum Kind { PRIMARY, GHOST, CIRCLE, CIRCLE_BIG, BAR, WAVE, CARD, CHIP }
    private enum Mode { PICKER, LOGIN, MAIN }
    private enum Icon { NONE, PLAY, PREV, NEXT, VOL, HEART, LEFT, RIGHT, LIKE, SHUFFLE, REPEAT, QUEUE, WAVE, DISLIKE }

    private static final class Btn {
        final Kind kind;
        final int x, y, w, h;
        String label = "";
        Icon icon = Icon.NONE;
        Platform plat;
        float glow;
        int order;
        DoubleConsumer click = mx -> { };
        BooleanSupplier enabled = () -> true;
        BooleanSupplier visible = () -> true;
        BooleanSupplier on = () -> false;

        Btn(Kind kind, int x, int y, int w, int h) { this.kind = kind; this.x = x; this.y = y; this.w = w; this.h = h; }

        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    private static final int BASE_W = 560, BASE_H = 330;
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
    private Mode built = Mode.PICKER;
    /** true — пользователь сам открыл выбор площадки, хотя одна из них уже выбрана. */
    private boolean pickerOpen;
    /** true — открыта форма привязки аккаунта (SoundCloud): аккаунт нужен не для работы, а для лайков. */
    private boolean linkOpen;
    /** 0 — аккаунт SoundCloud не привязан, 1 — только профиль, 2 — есть токен для лайков. */
    private int accountBuilt;
    private boolean draggingVolume, draggingSeek;
    private boolean addAccountMode;
    private boolean codeBuilt;
    private int chipEnd;
    /** Список сохранённых аккаунтов читается с диска один раз при построении экрана, а не каждый кадр. */
    private java.util.List<TokenStore.Account> savedAccounts = java.util.List.of();
    private String animTrackId;
    private long animStart;
    private double seekFrac;
    private int mouseX, mouseY;
    private int builtSig, pageHeaderY;
    private final int[] artistRect = new int[4], albumRect = new int[4];
    private Button artistHit, albumHit;

    private List<LyricLine> wrappedFor;
    private List<List<FormattedCharSequence>> wrapped = List.of();
    private int[] tops = new int[0];
    private int totalH, wrapW = -1;
    private double scrollPx = 0, manualPx = 0;

    private final long openStart = System.nanoTime();
    private long builtAt = openStart;
    private long lastFrameNs;
    private float dt = 0.016f;
    private final float[] rowGlow = new float[64];
    private float seekGlow;

    private final Screen parent;
    private boolean helpChecked;

    /** parent — экран, поверх которого открыт плеер (меню, пауза, кик): на него вернёмся при закрытии. */
    public PlayerScreen(Screen parent) {
        super(Component.translatable("screen.musicplayer.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        pc = PlayerConfig.get();
        float k = pc.windowScale / 100f;
        GuiFit.fit(this, Math.round(BASE_W * k) + 16, Math.round(BASE_H * k) + 16);
        pw = Math.min(width - 16, Math.round(BASE_W * k));
        ph = Math.min(height - 16, Math.round(BASE_H * k));
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        lw = Math.min(Math.round(pw * 0.29f), pw / 3);
        rx = px + lw + 32;
        rw = pw - lw - 32 - 14;

        built = modeNow();
        // после входа экран уже не LOGIN; без сброса флаг навсегда расходился с tick() и окно пересобиралось каждый тик
        codeBuilt = built == Mode.LOGIN && ctl.yandexCode != null;
        builtAt = System.nanoTime();
        accountBuilt = accountState();
        String oldQuery = searchBox != null ? searchBox.getValue() : SessionState.get().query;
        searchBox = null; tokenBox = null; field = null;
        btns.clear();

        // Имя артиста и альбом: клик принимают невидимые ванильные кнопки (добавлены первыми, чтобы ничто не перекрыло).
        artistHit = Hit.make(() -> { if (ctl.current != null) { page = 0; queueMode = false; ctl.openArtistOf(ctl.current); } });
        albumHit = Hit.make(() -> { if (ctl.current != null) { page = 0; queueMode = false; ctl.openAlbumOf(ctl.current); } });
        addWidget(artistHit);
        addWidget(albumHit);

        switch (built) {
            case MAIN -> buildMain(oldQuery);
            case LOGIN -> buildLogin();
            case PICKER -> buildPicker();
        }
    }

    private int accountState() { return ctl.soundcloud.canLike() ? 2 : ctl.soundcloud.isLinked() ? 1 : 0; }

    private Mode modeNow() {
        if (ctl.platform == null || pickerOpen) return Mode.PICKER;
        if (linkOpen) return Mode.LOGIN;
        return ctl.ready() ? Mode.MAIN : Mode.LOGIN;
    }

    private Btn btn(Kind kind, int x, int y, int w, int h, String label, Icon icon, DoubleConsumer click) {
        Btn b = new Btn(kind, x, y, w, h);
        b.label = label; b.icon = icon; b.click = click;
        btns.add(b);
        // Клик принимает невидимая ванильная кнопка (этот путь ввода гарантированно работает), а вид рисуем сами.
        Button hit = Button.builder(Component.empty(), press -> { if (b.visible.getAsBoolean() && b.enabled.getAsBoolean()) b.click.accept(mouseX); })
                .bounds(x, y, w, h).build();
        addWidget(hit);
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
    private static final int CARD_H = 52, CARD_GAP = 10;

    private void buildLogin() {
        if (ctl.platform == Platform.YANDEX) {
            java.util.List<TokenStore.Account> saved = ctl.yandex.savedAccounts();
            savedAccounts = saved;
            if (saved.isEmpty()) addAccountMode = true;
            if (codeBuilt) {
                buildCodeWait();
            } else if (addAccountMode) {
                int cx = px + pw / 2, w = 280;
                btn(Kind.PRIMARY, cx - w / 2, py + 62, w, 24, T.get("login.code_button"), Icon.NONE, mx -> ctl.loginYandexByCode());
                buildTokenForm(T.get("login.open_yandex"), YandexMusicProvider.OAUTH_URL,
                        T.get("login.paste_hint"), ctl::submitToken,
                        saved.isEmpty() ? null : "‹ " + T.get("login.back_saved"), () -> { addAccountMode = false; rebuildWidgets(); }, py + 112);
            } else buildAccountList(saved);
        } else if (ctl.platform == Platform.SOUNDCLOUD) {
            buildTokenForm(T.get("login.open_soundcloud"), "https://soundcloud.com/",
                    T.get("login.sc_hint"), ctl::linkSoundCloud,
                    "‹ " + T.get("btn.back"), () -> { linkOpen = false; rebuildWidgets(); });
        } else if (ctl.platform == Platform.SPOTIFY) {
            buildTokenForm(T.get("login.open_spotify"), "https://developer.spotify.com/dashboard",
                    T.get("login.sp_hint"), ctl::loginSpotify,
                    "‹ " + T.get("btn.back"), () -> { pickerOpen = true; rebuildWidgets(); });
            tokenBox.setValue(ctl.spotify.savedClientId());
        }
        addSettingsButton();
    }

    private void buildPicker() {
        addSettingsButton();
        Platform[] all = Platform.values();
        int cardW = pw - 28, x0 = px + 14, y0 = py + 66;
        // все площадки должны уместиться над кнопкой «Назад»
        int step = Math.max(40, Math.min(CARD_H + CARD_GAP, (ph - 66 - 34) / all.length));
        int cardH = Math.min(CARD_H, step - 6);
        for (int i = 0; i < all.length; i++) {
            Platform pl = all[i];
            int y = y0 + i * step;
            Btn card = btn(Kind.CARD, x0, y, cardW, cardH, "", Icon.NONE, mx -> choose(pl));
            card.plat = pl;
            card.order = i;
            card.enabled = () -> ctl.has(pl);
        }
        if (ctl.platform != null) {
            btn(Kind.GHOST, px + 14, py + ph - 28, 70, 16, "‹ " + T.get("btn.back"), Icon.NONE, mx -> { pickerOpen = false; rebuildWidgets(); });
        }
    }

    private void buildSoundCloudAccountButtons(int lx, int lw) {
        boolean linked = ctl.soundcloud.isLinked(), canLike = ctl.soundcloud.canLike();
        if (!linked) {
            btn(Kind.GHOST, lx, py + 78, lw, 22, T.get("player.sc_login"), Icon.NONE, mx -> { linkOpen = true; rebuildWidgets(); });
        } else if (canLike) {
            btn(Kind.GHOST, lx, py + 78, lw, 22, T.get("player.sc_logout"), Icon.NONE, mx -> { ctl.soundcloud.logout(); rebuildWidgets(); });
        } else {
            int half = (lw - 4) / 2;
            btn(Kind.GHOST, lx, py + 78, half, 22, T.get("player.logout"), Icon.NONE, mx -> { ctl.soundcloud.logout(); rebuildWidgets(); });
            btn(Kind.GHOST, lx + half + 4, py + 78, lw - half - 4, 22, T.get("player.likes"), Icon.NONE, mx -> { linkOpen = true; rebuildWidgets(); });
        }
    }

    private void choose(Platform pl) {
        pickerOpen = false;
        linkOpen = false;
        page = 0;
        queueMode = false;
        addAccountMode = false;
        ctl.selectPlatform(pl);
        rebuildWidgets();
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
        btn(Kind.GHOST, left, y + 6, w, 20, "+ " + T.get("login.add_account"), Icon.NONE, mx -> {
            addAccountMode = true;
            rebuildWidgets();
        });
    }

    private void buildTokenForm(String openLabel, String openUrl, String hint, Consumer<String> submit, String backLabel, Runnable back) {
        buildTokenForm(openLabel, openUrl, hint, submit, backLabel, back, py + 92);
    }

    private void buildTokenForm(String openLabel, String openUrl, String hint, Consumer<String> submit, String backLabel, Runnable back, int top) {
        int cx = px + pw / 2, w = 280;
        boolean compact = top > py + 92;
        Kind kind = compact ? Kind.GHOST : Kind.PRIMARY;
        btn(kind, cx - w / 2, top, w, 20, "1. " + openLabel, Icon.NONE, mx -> Browser.open(openUrl));

        tokenBox = styledField(cx - w / 2, top + (compact ? 26 : 46), w, 22, hint);
        tokenBox.setMaxLength(4096);
        field = tokenBox;
        addRenderableWidget(tokenBox);

        btn(kind, cx - w / 2, top + (compact ? 54 : 76), w, 20, "2. " + T.get("login.confirm"), Icon.NONE, mx -> submit.accept(tokenBox.getValue()));
        if (back != null) btn(Kind.GHOST, cx - w / 2, top + (compact ? 82 : 122), w, 18, backLabel, Icon.NONE, mx -> back.run());
    }

    private void buildCodeWait() {
        int cx = px + pw / 2, w = 280;
        btn(Kind.PRIMARY, cx - w / 2, py + 144, w, 20, T.get("login.code_reopen"), Icon.NONE, mx -> {
            YandexMusicProvider.DeviceCode c = ctl.yandexCode;
            if (c != null) Browser.open(c.url());
        });
        btn(Kind.GHOST, cx - w / 2, py + 168, w, 18, T.get("login.code_copy"), Icon.NONE, mx -> {
            YandexMusicProvider.DeviceCode c = ctl.yandexCode;
            if (c != null) PlayerController.copyToClipboard(c.userCode());
        });
        btn(Kind.GHOST, cx - w / 2, py + 190, w, 18, "‹ " + T.get("login.code_cancel"), Icon.NONE, mx -> ctl.cancelYandexCode());
    }

    private int headerButtonsW;   // ширина кнопок справа в шапке — чтобы текст статуса их не перекрывал

    private void addSettingsButton() {
        String label = T.get("settings");
        int w = font.width(label) + 16;
        btn(Kind.GHOST, px + pw - 12 - w, py + 5, w, 16, label, Icon.NONE,
                mx -> Game.open(HudConfigScreen.playerTab(this)));
        headerButtonsW = w;
        int hw = 16;
        btn(Kind.GHOST, px + pw - 12 - w - 4 - hw, py + 5, hw, 16, "?", Icon.NONE,
                mx -> Game.open(new HelpScreen(this)));
        headerButtonsW = w + 4 + hw;

        int titleEnd = px + 14 + font.width("♪ " + MusicPlayerMod.NAME);
        chipEnd = titleEnd;
        if (built != Mode.PICKER && ctl.platform != null) {
            Platform pl = ctl.platform;
            int cw = font.width(pl.title()) + 28, cx = titleEnd + 8;
            Btn chip = btn(Kind.CHIP, cx, py + 5, cw, 16, pl.title(), Icon.NONE, mx -> { pickerOpen = true; rebuildWidgets(); });
            chip.plat = pl;
            chipEnd = cx + cw;
        }

        MusicProvider ap = ctl.active();
        boolean scLinked = ap == ctl.soundcloud && ctl.soundcloud.isLinked();
        if (built == Mode.MAIN && ap != null && (ap.platform().needsLogin || scLinked)) {
            String out = T.get("player.logout");
            int ow = font.width(out) + 16;
            btn(Kind.GHOST, px + pw - 12 - headerButtonsW - 4 - ow, py + 5, ow, 16, out, Icon.NONE, mx -> { page = 0; queueMode = false; addAccountMode = false; ctl.logout(); });
            headerButtonsW += 4 + ow;
        }
    }

    private void buildMain(String oldQuery) {
        addSettingsButton();
        lx = px + 12;

        int sbW = 50;
        MusicProvider ap = ctl.active();
        boolean likes = ap.supportsLikes();
        searchBox = styledField(lx, py + 34, lw - sbW - 4, 18, T.get(ctl.platform == Platform.LOCAL ? "player.search_local" : "player.search"));
        searchBox.setMaxLength(100);
        searchBox.setValue(oldQuery == null ? "" : oldQuery);
        field = searchBox;
        addRenderableWidget(searchBox);
        btn(Kind.PRIMARY, lx + lw - sbW, py + 34, sbW, 18, T.get("player.find"), Icon.NONE, mx -> doSearch());
        int qW = 66;
        btn(Kind.GHOST, lx, py + 56, lw - qW - 4, 18, ap.likedLabel(), likes ? Icon.HEART : Icon.NONE, mx -> { page = 0; queueMode = false; ctl.loadLiked(); });
        btn(Kind.GHOST, lx + lw - qW, py + 56, qW, 18, T.get("player.queue"), Icon.QUEUE, mx -> { page = 0; queueMode = !queueMode; });

        if (ctl.platform == Platform.YANDEX) {
            btn(Kind.WAVE, lx, py + 78, lw, 22, "", Icon.WAVE, mx -> { page = 0; queueMode = false; ctl.toggleWave(); });
        } else if (ctl.platform == Platform.SOUNDCLOUD) {
            buildSoundCloudAccountButtons(lx, lw);
        } else if (ctl.platform == Platform.LOCAL) {
            int half = (lw - 4) / 2;
            btn(Kind.GHOST, lx, py + 78, half, 22, T.get("player.folder"), Icon.NONE, mx -> ctl.openLocalFolder());
            btn(Kind.GHOST, lx + half + 4, py + 78, lw - half - 4, 22, T.get("player.refresh"), Icon.NONE, mx -> ctl.local.refresh());
        }

        builtSig = viewSig();
        int top = py + 106;
        Page pg = ctl.page();
        pageHeaderY = top;
        if (pg != null) {
            btn(Kind.GHOST, lx, top, 54, 18, "‹ " + T.get("btn.back"), Icon.NONE, mx -> ctl.back());
            if (!pg.tracks().isEmpty()) btn(Kind.PRIMARY, lx + lw - 74, top, 74, 18, "▶ " + T.get("browse.play_all"), Icon.NONE, mx -> ctl.playAll(ctl.page()));
            top += 22;
        }
        List<PlayerController.Tab> tabs = visibleTabs();
        if (!tabs.isEmpty()) {
            int cols = 2, tw = (lw - 2) / cols;
            for (int i = 0; i < tabs.size(); i++) {
                PlayerController.Tab tb = tabs.get(i);
                Btn b = btn(Kind.GHOST, lx + (i % cols) * (tw + 2), top + (i / cols) * 18, tw, 16, tabLabel(tb), Icon.NONE, mx -> { ctl.tab = tb; page = 0; if (tb == PlayerController.Tab.STATIONS) ctl.loadStations(); });
                b.on = () -> ctl.tab == tb;
            }
            top += ((tabs.size() + cols - 1) / cols) * 18 + 2;
        }
        listTop = top;
        int listBottom = py + ph - 34;
        rows = Math.max(1, (listBottom - listTop) / ROW_H);
        for (int i = 0; i < rows; i++) {
            final int row = i;
            int ry = listTop + i * ROW_H;
            rowButton(lx, ry, lw - ACT_W * 2 - 4, () -> {
                int idx = page * rows + row;
                if (queueMode) { ctl.playQueued(idx); return; }
                if (waveShown()) { ctl.playWaveIndex(idx); return; }
                List<?> list = items();
                if (idx >= list.size()) return;
                if (list.get(idx) instanceof MusicEntity e) {
                    if (e.kind() == MusicEntity.Kind.STATION) ctl.startStation(e);
                    else ctl.open(e);
                }
                else {
                    Page pg2 = ctl.page();
                    List<Track> source = pg2 != null && pg2.tracks() == list ? pg2.tracks() : ctl.results;
                    ctl.playFrom(source, idx);
                }
            });
            rowButton(lx + lw - ACT_W * 2 - 2, ry, ACT_W, () -> {
                int idx = page * rows + row;
                if (queueMode) ctl.queueMoveUp(idx);
                else if (trackAt(idx) != null) ctl.addToQueue(trackAt(idx));
            });
            rowButton(lx + lw - ACT_W, ry, ACT_W, () -> {
                int idx = page * rows + row;
                if (queueMode) ctl.queueRemove(idx);
                else if (trackAt(idx) != null) ctl.playNext(trackAt(idx));
            });
        }
        Btn prevPage = btn(Kind.GHOST, lx, py + ph - 28, 34, 18, "", Icon.LEFT, mx -> page = Math.max(0, page - 1));
        prevPage.enabled = () -> page > 0;
        Btn nextPage = btn(Kind.GHOST, lx + lw - 34, py + ph - 28, 34, 18, "", Icon.RIGHT, mx -> page = Math.min(maxPage(), page + 1));
        nextPage.enabled = () -> page < maxPage();

        coverPx = pc.showCover ? Math.min(pc.coverPx, Math.max(40, ph - 190)) : 0;
        by = py + ph - 62;
        cy = py + ph - 24;
        lyTop = py + 30 + Math.max(coverPx, 44) + 10;
        lyBottom = by - 12;
        if (pc.showLyrics) {
            lyBottom = by - 32;
            buildLyricsRow(by - 26);
        }

        Btn seek = btn(Kind.BAR, rx, by - 6, rw, 14, "", Icon.NONE, mx -> {
            Track t = ctl.current;
            if (t != null) { draggingSeek = true; seekFrac = clamp01((mx - rx) / rw); }
        });
        seek.enabled = () -> ctl.current != null && !ctl.idle;

        int mw1 = 64, mw2 = 84, mgap = 6, mx0 = rx + (rw - mw1 - mw2 - mgap) / 2;
        btn(Kind.GHOST, mx0, by + 8, mw1, 12, "", Icon.SHUFFLE, mx -> ctl.toggleShuffle());
        btn(Kind.GHOST, mx0 + mw1 + mgap, by + 8, mw2, 12, "", Icon.REPEAT, mx -> ctl.cycleRepeat());

        Btn like = btn(Kind.CIRCLE, rx + rw - 18, py + 32, 18, 18, "", Icon.LIKE, mx -> ctl.toggleLike());
        like.enabled = () -> ctl.canLike(ctl.current);
        like.visible = () -> ctl.canLike(ctl.current);
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
        addWidget(b);
    }

    /** То, что сейчас в списке: треки, а на вкладках поиска и страницах артистов ещё и артисты, альбомы, плейлисты. */
    private List<?> items() {
        if (queueMode) return ctl.queueSnapshot();
        if (waveShown()) return ctl.waveSnapshot();
        Page pg = ctl.page();
        if (pg != null) {
            if (pg.tracks().isEmpty()) return pg.entities();
            if (pg.entities().isEmpty()) return pg.tracks();
            return ctl.tab == PlayerController.Tab.ALBUMS ? pg.entities() : pg.tracks();
        }
        if (ctl.platform == Platform.YANDEX) {
            return switch (ctl.tab) {
                case ARTISTS -> ctl.artists;
                case ALBUMS -> ctl.albums;
                case PLAYLISTS -> ctl.playlists;
                case STATIONS -> ctl.stations;
                default -> ctl.results;
            };
        }
        if (ctl.platform == Platform.SPOTIFY) {
            return switch (ctl.tab) {
                case PLAYLISTS -> ctl.playlists;
                default -> ctl.results;
            };
        }
        return ctl.results;
    }

    private Track hoveredTrack() {
        if (mouseX < lx || mouseX >= lx + lw || mouseY < listTop) return null;
        return trackAt(page * rows + (mouseY - listTop) / ROW_H);
    }

    private Track trackAt(int idx) {
        List<?> list = items();
        return idx >= 0 && idx < list.size() && list.get(idx) instanceof Track t ? t : null;
    }

    private List<PlayerController.Tab> visibleTabs() {
        if (queueMode || waveShown() || (ctl.platform != Platform.YANDEX && ctl.platform != Platform.SPOTIFY)) return List.of();
        Page pg = ctl.page();
        if (pg != null) {
            return !pg.tracks().isEmpty() && !pg.entities().isEmpty()
                    ? List.of(PlayerController.Tab.TRACKS, PlayerController.Tab.ALBUMS) : List.of();
        }
        if (ctl.platform == Platform.SPOTIFY) return List.of(PlayerController.Tab.TRACKS, PlayerController.Tab.PLAYLISTS);
        return List.of(PlayerController.Tab.TRACKS, PlayerController.Tab.ARTISTS, PlayerController.Tab.ALBUMS, PlayerController.Tab.PLAYLISTS,
                PlayerController.Tab.STATIONS);
    }

    private String tabLabel(PlayerController.Tab tb) {
        boolean inPage = ctl.page() != null;
        return switch (tb) {
            case TRACKS -> T.get(inPage ? "browse.popular" : "browse.tracks");
            case ARTISTS -> T.get("browse.artists");
            case ALBUMS -> T.get("browse.albums");
            case PLAYLISTS -> T.get("browse.playlists");
            case STATIONS -> T.get("browse.stations");
        };
    }

    /** Меняется, когда нужно пересобрать кнопки: открылась страница, пропали или появились вкладки. */
    private int viewSig() {
        return System.identityHashCode(ctl.page()) * 31 + visibleTabs().size() * 7 + (queueMode ? 1 : 0) + (waveShown() ? 2 : 0);
    }

    private boolean waveShown() { return ctl.waveActive && ctl.waveView; }

    private void doSearch() {
        if (searchBox == null) return;
        page = 0;
        SessionState.get().query = searchBox.getValue();
        SessionState.markDirty();
        ctl.search(searchBox.getValue());
    }

    private int maxPage() { return Math.max(0, (items().size() - 1) / Math.max(1, rows)); }

    private void setVolumeFromMouse(double mx) { ctl.setVolume((float) clamp01((mx - volBarX) / VOL_BAR_W)); }

    private static boolean hit(int mx, int my, int x, int y, int w, int h) { return w > 0 && mx >= x && mx < x + w && my >= y && my < y + h; }

    private static void setRect(int[] r, int x, int y, int w, int h) { r[0] = x; r[1] = y; r[2] = w; r[3] = h; }

    /** Клик по имени артиста или названию альбома под обложкой открывает его страницу. */
    @Override
    protected boolean onClick(double ex, double ey, int button, boolean doubleClick) {
        mouseX = (int) ex;
        mouseY = (int) ey;
        if (built == Mode.MAIN && ctl.current != null) {
            if (hit(mouseX, mouseY, artistRect[0], artistRect[1], artistRect[2], artistRect[3])) { page = 0; queueMode = false; ctl.openArtistOf(ctl.current); return true; }
            if (hit(mouseX, mouseY, albumRect[0], albumRect[1], albumRect[2], albumRect[3])) { page = 0; queueMode = false; ctl.openAlbumOf(ctl.current); return true; }
        }
        return super.onClick(ex, ey, button, doubleClick);
    }

    private static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }

    @Override
    public void removed() {
        if (searchBox != null) { SessionState.get().query = searchBox.getValue(); SessionState.markDirty(); }
        GuiFit.restore();
        super.removed();
    }

    @Override
    public void tick() {
        if (!helpChecked) {
            helpChecked = true;
            SessionState st = SessionState.get();
            if (!st.helpSeen) {
                st.helpSeen = true;
                SessionState.markDirty();
                Game.open(new HelpScreen(this));
                return;
            }
        }
        int account = accountState();
        if (linkOpen && account != accountBuilt) linkOpen = false;
        boolean codeNow = ctl.yandexCode != null && built == Mode.LOGIN;
        if (modeNow() != built || account != accountBuilt || codeNow != codeBuilt
                || (built == Mode.MAIN && viewSig() != builtSig)) rebuildWidgets();
    }

    @Override
    protected boolean onDrag(double ex, double ey, int button, double dragX, double dragY) {
        mouseX = (int) ex;
        mouseY = (int) ey;
        if (draggingVolume) { setVolumeFromMouse(mouseX); return true; }
        if (draggingSeek) { seekFrac = clamp01((mouseX - rx) / rw); return true; }
        return super.onDrag(ex, ey, button, dragX, dragY);
    }

    @Override
    protected boolean onRelease(double ex, double ey, int button) {
        draggingVolume = false;
        if (draggingSeek) {   // перемотка один раз при отпускании — не дёргаем аудио во время перетаскивания
            draggingSeek = false;
            Track t = ctl.current;
            if (t != null) ctl.seekTo((long) (seekFrac * t.durationMs()));
            return true;
        }
        return super.onRelease(ex, ey, button);
    }

    /**
     * Колесо: над громкостью — громкость, над списком — страницы, над текстом — прокрутка
     * (для несинхронизированного текста).
     */
    @Override
    public boolean mouseScrolled(double mx0, double my0, double sx, double sy) {
        double mx = mouseX, my = mouseY;
        if (built == Mode.MAIN) {
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
    protected boolean onKey(KeyInput event) {
        int key = event.key();
        boolean searching = searchBox != null && searchBox.isFocused();
        boolean typing = searching || (tokenBox != null && tokenBox.isFocused());
        if (typing) {
            if (searching && (key == Keys.ENTER || key == Keys.KP_ENTER)) { doSearch(); return true; }
            return super.onKey(event);
        }
        if (key == Keys.DELETE && ctl.platform == Platform.LOCAL && !queueMode) {
            Track hovered = hoveredTrack();
            if (hovered != null) { ctl.removeLocal(hovered); return true; }
        }
        Hotkeys.Action a = Hotkeys.actionFor(event);
        if (a == Hotkeys.Action.OPEN_PLAYER) { onClose(); return true; }
        if (a != null && (built == Mode.MAIN || !a.isTransport())) { Hotkeys.perform(a, this); return true; }
        return super.onKey(event);
    }

    @Override
    protected void draw(Canvas g, int mx, int my, float delta) {
        mouseX = mx; mouseY = my;
        long frameNs = System.nanoTime();
        dt = lastFrameNs == 0 ? 0.016f : Math.min(0.1f, (frameNs - lastFrameNs) / 1e9f);
        lastFrameNs = frameNs;
        th = PlayerTheme.current();
        int r = th.radius;

        th.window(g, px, py, pw, ph);
        g.fill(px + 10, py + 24, px + pw - 10, py + 25, PlayerTheme.argb(0xFFFFFF, 16));

        String title = "♪ " + MusicPlayerMod.NAME;
        g.text(font, title, px + 14, py + 9, th.accent);
        int leftEnd = chipEnd;
        MusicProvider ap = ctl.active();
        String acc = ap == null || built == Mode.PICKER ? "" : ap.accountName();
        if (!acc.isEmpty()) {
            int accRoom = Math.max(30, px + pw - 12 - headerButtonsW - 8 - (chipEnd + 6) - 10);
            String shownAcc = ellipsize("· " + acc, accRoom);
            g.text(font, shownAcc, chipEnd + 6, py + 9, PlayerTheme.TEXT_DIM);
            leftEnd = chipEnd + 6 + font.width(shownAcc);
        }
        boolean longStatus = !drawShortStatus(g, leftEnd);

        switch (built) {
            case PICKER -> renderPicker(g);
            case LOGIN -> renderLogin(g);
            case MAIN -> renderMain(g);
        }

        boolean mainNow = built == Mode.MAIN && ctl.current != null;
        Hit.place(artistHit, artistRect, mainNow);
        Hit.place(albumHit, albumRect, mainNow);

        drawField(g);
        for (Btn b : btns) drawBtn(g, b);

        if (!transitioning()) drawWidgets(g, mx, my, delta);
        if (longStatus) drawLongStatus(g);

        float shown = Math.min(1f, (System.nanoTime() - builtAt) / 180_000_000f);
        if (!transitioning() && shown < 1f && built != Mode.PICKER)
            PlayerTheme.rrect(g, px, py + 26, pw, ph - 26, r, PlayerTheme.argb(th.panelTop, Math.round(200 * (1f - shown) * (1f - shown))));
    }

    private float smooth(float current, boolean on) {
        return current + ((on ? 1f : 0f) - current) * (1f - (float) Math.exp(-dt * 16f));
    }

    private static int blend(int from, int to, float t) { return 0xFF000000 | PlayerTheme.mix(from, to, t); }

    private static int fade(int argb, float k) {
        return (Math.round((argb >>> 24) * Math.max(0f, Math.min(1f, k))) << 24) | (argb & 0xFFFFFF);
    }

    private void drawField(Canvas g) {
        if (field == null) return;
        int br = Math.min(th.radius, 4);
        PlayerTheme.rrect(g, fx - 1, fy - 1, fw + 2, fh + 2, br + 1, field.isFocused() ? th.accent : th.trackBg);
        PlayerTheme.rrect(g, fx, fy, fw, fh, br, th.fieldBg);
    }

    private String ellipsize(String text, int maxW) {
        if (font.width(text) <= maxW) return text;
        String dots = "…";
        return font.plainSubstrByWidth(text, Math.max(0, maxW - font.width(dots))).stripTrailing() + dots;
    }

    private String statusText() {
        String st = ctl.status;
        if (st.isEmpty()) return st;
        int pct = ctl.loadingPercent();
        if (pct >= 0 && st.equals(T.get("status.loading_track"))) st = st + " " + pct + "%";
        return st;
    }

    /** false — не влез, рисуется над окном. */
    private boolean drawShortStatus(Canvas g, int leftEnd) {
        String st = statusText();
        if (st.isEmpty()) return true;
        int rightEdge = px + pw - 12 - headerButtonsW - 8;
        int room = Math.max(40, rightEdge - leftEnd - 10);
        if (font.width(st) > room) return false;
        g.text(font, st, rightEdge - font.width(st), py + 9, PlayerTheme.WARN);
        return true;
    }

    /** Над окном и поверх всего: под шапкой ошибки перекрывали кнопки. */
    private void drawLongStatus(Canvas g) {
        String st = statusText();
        if (st.isEmpty()) return;
        List<FormattedCharSequence> lines = font.split(Component.literal(st), pw - 40);
        int shownLines = Math.min(3, lines.size());
        int boxH = shownLines * (font.lineHeight + 1) + 6;
        int boxY = Math.max(2, py - boxH - 4);
        PlayerTheme.rrect(g, px + 14, boxY, pw - 28, boxH, 4, PlayerTheme.argb(0x000000, 200));
        for (int i = 0; i < shownLines; i++)
            g.text(font, lines.get(i), px + 20, boxY + 4 + i * (font.lineHeight + 1), PlayerTheme.WARN);
    }

    private void renderPicker(Canvas g) {
        int cx = px + pw / 2;
        center(g, T.get("picker.title"), cx, py + 34, PlayerTheme.TEXT_MAIN);
        center(g, T.get("picker.sub"), cx, py + 46, PlayerTheme.TEXT_DIM);
        center(g, T.get("picker.privacy"), cx, py + ph - 20, PlayerTheme.TEXT_DIM);
    }

    private void renderLogin(Canvas g) {
        int cx = px + pw / 2;
        if (ctl.platform == Platform.SOUNDCLOUD) {
            center(g, T.get("login.sc_title"), cx, py + 34, PlayerTheme.TEXT_MAIN);
            center(g, T.get("login.sc_1"), cx, py + 50, PlayerTheme.TEXT_MID);
            center(g, T.get("login.sc_2"), cx, py + 62, PlayerTheme.TEXT_MID);
            center(g, T.get("login.sc_privacy"), cx, py + 198, PlayerTheme.TEXT_DIM);
            return;
        }
        if (ctl.platform == Platform.SPOTIFY) {
            center(g, T.get("login.sp_title"), cx, py + 30, PlayerTheme.TEXT_MAIN);
            center(g, T.get("login.sp_1"), cx, py + 44, PlayerTheme.TEXT_MID);
            center(g, "Redirect URI: http://127.0.0.1:43821/callback", cx, py + 56, PlayerTheme.TEXT_MAIN);
            center(g, T.get("login.sp_2"), cx, py + 68, PlayerTheme.TEXT_MID);
            center(g, T.get("login.sp_3"), cx, py + 80, PlayerTheme.TEXT_MID);
            center(g, T.get("login.sp_privacy"), cx, py + 198, PlayerTheme.TEXT_DIM);
            return;
        }
        if (ctl.platform != Platform.YANDEX) return;
        if (!addAccountMode && ctl.yandexCode == null) {
            center(g, T.get("login.choose"), cx, py + 36, PlayerTheme.TEXT_MAIN);
            center(g, T.get("login.saved_sub"), cx, py + 46, PlayerTheme.TEXT_DIM);
            int n = Math.min(MAX_ACCOUNT_ROWS, savedAccounts.size());
            int more = savedAccounts.size() - n;
            if (more > 0) center(g, T.get("login.more", more, n), cx, py + 56 + n * 26 + 34, PlayerTheme.TEXT_DIM);
            center(g, T.get("login.forget"), cx, py + ph - 20, PlayerTheme.TEXT_DIM);
            return;
        }
        center(g, T.get("login.ya_title"), cx, py + 34, PlayerTheme.TEXT_MAIN);
        YandexMusicProvider.DeviceCode code = ctl.yandexCode;
        if (code != null) {
            center(g, T.get("login.code_1"), cx, py + 50, PlayerTheme.TEXT_MID);
            center(g, T.get("login.code_2"), cx, py + 62, PlayerTheme.TEXT_MID);
            String shown = code.userCode().toUpperCase(java.util.Locale.ROOT);
            float k = 2.5f;
            g.push(k);
            g.text(font, shown, Math.round((cx - font.width(shown) * k / 2) / k), Math.round((py + 78) / k), th.accent);
            g.pop();
            long left = Math.max(0, (code.expiresAt() - System.currentTimeMillis()) / 1000);
            center(g, T.get("login.code_3"), cx, py + 108, PlayerTheme.TEXT_MID);
            center(g, T.get("login.code_4", left / 60 + ":" + String.format("%02d", left % 60)), cx, py + 122, PlayerTheme.TEXT_DIM);
            return;
        }
        center(g, T.get("login.code_hint"), cx, py + 50, PlayerTheme.TEXT_MID);
        center(g, T.get("login.or_link"), cx, py + 98, PlayerTheme.TEXT_DIM);
        center(g, T.get("login.ya_privacy"), cx, py + ph - 16, PlayerTheme.TEXT_DIM);
    }

    private void renderMain(Canvas g) {
        PlayerTheme.rrect(g, px + 8, py + 26, lw + 8, ph - 34, Math.max(0, th.radius - 2), th.panel2);
        drawList(g);
        center(g, (page + 1) + " / " + (maxPage() + 1), lx + lw / 2, py + ph - 24, PlayerTheme.TEXT_DIM);
        drawVolume(g);

        Track t = ctl.current;
        if (t == null) {
            setRect(artistRect, 0, 0, 0, 0);
            setRect(albumRect, 0, 0, 0, 0);
            int midY = py + 40 + (by - py - 40) / 2 - 8;
            if (ctl.platform == Platform.LOCAL) {
                center(g, T.get("player.drop"), rx + rw / 2, midY, PlayerTheme.TEXT_MID);
                center(g, T.get("player.drop2"), rx + rw / 2, midY + 12, PlayerTheme.TEXT_DIM);
            } else {
                center(g, T.get("player.empty"), rx + rw / 2, midY, PlayerTheme.TEXT_MID);
            }
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

        Canvas c = g;
        int textX = rx;
        if (coverPx > 0) {
            CoverArt.offerBig(t.id(), t.coverUrl());
            Tex art = CoverArt.getBig(t.id());
            int cs = Math.round(coverPx * (0.9f + 0.1f * ae));
            int cx0 = rx + (coverPx - cs) / 2, cy0 = py + 32 + (coverPx - cs) / 2;
            if (wave && ctl.engine().isPlaying()) drawWaveGlow(g, rx, py + 32, coverPx, secs);
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
        String artistText = font.plainSubstrByWidth(nz(t.artist()), availW);
        boolean yandexTrack = t.provider() == null || "yandex".equals(t.provider());
        boolean overArtist = yandexTrack && hit(mouseX, mouseY, textX, ay, font.width(artistText), font.lineHeight);
        g.text(font, artistText, textX + slide, ay, overArtist ? th.accent : midColor);
        setRect(artistRect, yandexTrack && !artistText.isEmpty() ? textX : 0, ay, font.width(artistText), font.lineHeight);
        int nextY = ay + font.lineHeight + 2;
        setRect(albumRect, 0, 0, 0, 0);
        if (coverPx >= 60 && t.album() != null && !t.album().isBlank()) {
            String albumText = font.plainSubstrByWidth(t.album(), availW);
            boolean overAlbum = yandexTrack && hit(mouseX, mouseY, textX, nextY, font.width(albumText), font.lineHeight);
            g.text(font, albumText, textX + slide, nextY, overAlbum ? th.accent : dimColor);
            if (yandexTrack) setRect(albumRect, textX, nextY, font.width(albumText), font.lineHeight);
            nextY += font.lineHeight + 2;
        }
        if (showBadge) {
            String bl = T.get("player.wave");
            int bw = font.width(bl) + 14;
            Shapes.fill(g, textX + slide, nextY + 3, bw, 11, 3, PlayerTheme.argb(wavePaletteAt(secs * 0.35 * WAVE_PALETTE.length), aa));
            g.text(font, bl, textX + slide + 7, nextY + 5, PlayerTheme.argb(0xFFFFFF, aa));
        }

        if (pc.showLyrics && lyBottom - lyTop > 24) renderLyrics(g);
        if (pc.showLyrics) drawShiftLabel(g);

        drawProgress(g, t);
    }

    private void renderLyrics(Canvas g) {
        PlayerTheme.rrect(g, rx - 4, lyTop - 4, rw + 8, lyBottom - lyTop + 8, 4, PlayerTheme.argb(th.panel2, 150));
        int midX = rx + rw / 2;

        if (ctl.idle) { center(g, T.get("player.press_play"), midX, (lyTop + lyBottom) / 2 - 4, PlayerTheme.TEXT_DIM); return; }
        List<LyricLine> src = ctl.lyrics;
        if (src.isEmpty()) {
            center(g, T.get(ctl.lyricsLoading ? "player.lyrics_loading" : "player.lyrics_none"), midX, (lyTop + lyBottom) / 2 - 4, PlayerTheme.TEXT_DIM);
            return;
        }
        ensureWrapped(src);
        long pos = ctl.engine().positionMs();
        boolean synced = src.get(0).timeMs() != LyricLine.UNSYNCED;
        int cur = synced ? LrcParser.currentIndex(src, pos) : -1;
        int lh = font.lineHeight + 2;
        int viewH = lyBottom - lyTop;

        double target = synced ? (cur >= 0 ? tops[cur] + wrapped.get(cur).size() * lh / 2.0 : 0) : manualPx;
        scrollPx += (target - scrollPx) * (1.0 - Math.exp(-dt * 10.0));
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

    private void drawProgress(Canvas g, Track t) {
        long dur = t == null ? 0 : Math.max(1, t.durationMs());
        long pos = t == null || ctl.idle ? 0 : ctl.engine().positionMs();
        if (draggingSeek) pos = (long) (seekFrac * dur);
        boolean hover = t != null && !ctl.idle
                && (draggingSeek || mouseX >= rx && mouseX < rx + rw && mouseY >= by - 6 && mouseY < by + 10);

        seekGlow = smooth(seekGlow, hover);
        int bh = 4 + Math.round(seekGlow * 2f);
        int barY = by + 2 - bh / 2;
        PlayerTheme.rrect(g, rx, barY, rw, bh, bh / 2, th.trackBg);
        int fill = dur == 0 ? 0 : (int) (rw * Math.min(1.0, pos / (double) dur));
        if (fill > 0) PlayerTheme.rrect(g, rx, barY, Math.max(bh, fill), bh, bh / 2, th.accent);
        if (t != null) {
            int kx = Math.max(rx + 4, Math.min(rx + rw - 4, rx + fill));
            PlayerTheme.circle(g, kx, by + 2, 5 + Math.round(seekGlow), th.accent);
            PlayerTheme.circle(g, kx, by + 2, 2 + Math.round(seekGlow), PlayerTheme.TEXT_MAIN);
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

    private void drawVolume(Canvas g) {
        int bh = 4;
        int barY = cy - bh / 2;
        PlayerTheme.rrect(g, volBarX, barY, VOL_BAR_W, bh, 2, th.trackBg);
        int fill = Math.round(VOL_BAR_W * ctl.volume);
        if (fill > 0) PlayerTheme.rrect(g, volBarX, barY, Math.max(bh, fill), bh, 2, th.accent);
        PlayerTheme.circle(g, Math.max(volBarX + 3, Math.min(volBarX + VOL_BAR_W - 3, volBarX + fill)), cy, 4, PlayerTheme.TEXT_MAIN);
        g.text(font, Math.round(ctl.volume * 100) + "%", volBarX + VOL_BAR_W + 6, cy - font.lineHeight / 2 + 1, PlayerTheme.TEXT_DIM);
    }

    private void drawList(Canvas g) {
        List<?> res = items();
        Page pg = ctl.page();
        if (pg != null) {
            String title = font.plainSubstrByWidth(pg.source().isMine() ? T.get("entity.mine") : pg.source().title(), lw - 54 - (pg.tracks().isEmpty() ? 0 : 74) - 12);
            g.text(font, title, lx + 60, pageHeaderY + 5, PlayerTheme.TEXT_MAIN);
        }
        if (res.isEmpty()) {
            boolean local = ctl.platform == Platform.LOCAL;
            center(g, T.get(queueMode ? "player.queue_empty" : ctl.waveLoading || ctl.stationsLoading ? "player.wave_loading" : local ? "player.no_files" : "player.tracks_here"), lx + lw / 2, listTop + 14, PlayerTheme.TEXT_DIM);
            if (queueMode) center(g, T.get("player.queue_hint"), lx + lw / 2, listTop + 28, PlayerTheme.TEXT_DIM);
            else if (local) center(g, T.get("player.drop_short"), lx + lw / 2, listTop + 28, PlayerTheme.TEXT_DIM);
            return;
        }
        page = Math.max(0, Math.min(page, maxPage()));
        Track cur = ctl.current;
        String curId = cur == null ? null : cur.id();
        int textW = lw - 18 - ACT_W * 2;
        for (int i = 0; i < rows; i++) {
            int idx = page * rows + i;
            if (idx >= res.size()) break;
            Object item = res.get(idx);
            Track t = item instanceof Track tr ? tr : null;
            int y = listTop + i * ROW_H;
            boolean isCur = t != null && t.id() != null && t.id().equals(curId);
            boolean hov = mouseX >= lx && mouseX < lx + lw && mouseY >= y && mouseY < y + ROW_H - 2;
            rowGlow[i] = smooth(rowGlow[i], hov && !isCur);
            if (isCur) {
                PlayerTheme.rrect(g, lx, y, lw, ROW_H - 2, 4, th.rowCurrent);
                drawEqualizer(g, lx + 5, y + 4, ROW_H - 10, ctl.engine().isPlaying());
            } else if (rowGlow[i] > 0.02f) {
                PlayerTheme.rrect(g, lx, y, lw, ROW_H - 2, 4, blend(th.panel2, th.hover, rowGlow[i]));
            }
            if (t == null) {
                drawEntityRow(g, (MusicEntity) item, y, hov);
                continue;
            }
            int tx = isCur ? lx + 16 : lx + 8, tw = isCur ? textW - 8 : textW;
            g.text(font, font.plainSubstrByWidth(nz(t.title()), tw), tx, y + 3,
                    isCur ? th.accent : PlayerTheme.TEXT_MAIN);
            g.text(font, font.plainSubstrByWidth(nz(t.artist()), tw), tx, y + 3 + font.lineHeight,
                    PlayerTheme.TEXT_DIM);
            if (!hov && !isCur && ctl.provider(t).isCached(t.id()))
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

    /** Артист, альбом или плейлист: значок-плитка с буквой, название и пояснение. */
    private void drawEntityRow(Canvas g, MusicEntity e, int y, boolean hov) {
        int size = ROW_H - 6;
        boolean artist = e.kind() == MusicEntity.Kind.ARTIST;
        boolean playing = e.kind() == MusicEntity.Kind.STATION && ctl.waveActive && e.id().equals(ctl.waveSeed);
        int box = e.isMine() || playing ? th.accent : PlayerTheme.argb(th.accentRgb, hov ? 160 : 110);
        if (artist) PlayerTheme.circle(g, lx + 4 + size / 2, y + 2 + size / 2, size / 2, box);
        else PlayerTheme.rrect(g, lx + 4, y + 2, size, size, 4, box);
        String letter = e.isMine() ? "★" : e.title().isEmpty() ? "?" : e.title().substring(0, 1).toUpperCase();
        g.text(font, letter, lx + 4 + (size - font.width(letter)) / 2, y + 2 + (size - font.lineHeight) / 2 + 1, PlayerTheme.TEXT_MAIN);
        int tx = lx + size + 10, tw = lw - size - 16;
        String title = e.isMine() ? T.get("entity.mine") : e.title();
        String sub = e.isMine() ? T.get("entity.mine_sub") : e.subtitle();
        g.text(font, font.plainSubstrByWidth(title, tw), tx, y + 3, hov ? th.accent : PlayerTheme.TEXT_MAIN);
        g.text(font, font.plainSubstrByWidth(sub, tw), tx, y + 3 + font.lineHeight, PlayerTheme.TEXT_DIM);
    }

    private void drawBtn(Canvas g, Btn b) {
        if (b.kind == Kind.BAR) return;
        if (!b.visible.getAsBoolean()) return;
        if (b.kind == Kind.WAVE) { drawWaveButton(g, b); return; }
        if (b.kind == Kind.CARD) { drawCard(g, b); return; }
        if (b.kind == Kind.CHIP) { drawChip(g, b); return; }
        boolean enabled = b.enabled.getAsBoolean();
        boolean hov = enabled && b.contains(mouseX, mouseY);
        b.glow = smooth(b.glow, hov);
        int br = Math.min(th.radius + 1, b.h / 2);
        int cxB = b.x + b.w / 2, cyB = b.y + b.h / 2;

        switch (b.kind) {
            case PRIMARY -> {
                int bg = !enabled ? th.trackBg : blend(th.accent, th.accentHover, b.glow);
                Shapes.shadow(g, b.x, b.y, b.w, b.h, br, 1, 0xFF000000 | PlayerTheme.mix(enabled ? th.accentRgb : 0x24242A, 0x000000, 0.45f));
                PlayerTheme.rrect(g, b.x, b.y, b.w, b.h, br, bg);
                center(g, b.label, cxB, b.y + (b.h - font.lineHeight) / 2 + 1, PlayerTheme.TEXT_MAIN);
            }
            case GHOST -> {
                PlayerTheme.rrect(g, b.x, b.y, b.w, b.h, br, blend(th.trackBg, th.hover, b.glow));
                int fg = !enabled ? PlayerTheme.TEXT_DIM : blend(PlayerTheme.TEXT_MID, PlayerTheme.TEXT_MAIN, b.glow);
                if (b.on.getAsBoolean()) fg = th.accent;
                String label = b.label;
                switch (b.icon) {
                    case SHUFFLE -> { label = T.get("player.shuffle"); if (ctl.shuffle) fg = th.accent; }
                    case REPEAT -> { label = T.get("player.repeat", T.get("repeat." + ctl.repeat.name().toLowerCase())); if (ctl.repeat != PlayerController.Repeat.OFF) fg = th.accent; }
                    case QUEUE -> { label = T.get("player.queue") + " " + ctl.queueSnapshot().size(); if (queueMode) fg = th.accent; }
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
                int bg = big ? (!enabled ? th.trackBg : blend(th.accent, th.accentHover, b.glow)) : blend(th.trackBg, th.hover, b.glow);
                if (big) Shapes.shadow(g, cxB - r, cyB - r, r * 2, r * 2, 3, 2, 0xFF000000 | PlayerTheme.mix(th.accentRgb, 0x000000, 0.45f));
                PlayerTheme.circle(g, cxB, cyB, r, bg);
                int fg = big || hov ? PlayerTheme.TEXT_MAIN : (enabled ? PlayerTheme.TEXT_MID : PlayerTheme.TEXT_DIM);
                drawIcon(g, b.icon, cxB, cyB, fg);
            }
            default -> { }
        }
    }

    private void drawChip(Canvas g, Btn b) {
        b.glow = smooth(b.glow, b.contains(mouseX, mouseY));
        PlayerTheme.rrect(g, b.x, b.y, b.w, b.h, Math.min(th.radius + 1, b.h / 2), blend(th.trackBg, th.hover, b.glow));
        PlatformIcons.draw(g, b.plat, b.x + 3, b.y + 2, 12);
        g.text(font, b.label, b.x + 19, b.y + (b.h - font.lineHeight) / 2 + 1, blend(PlayerTheme.TEXT_MID, PlayerTheme.TEXT_MAIN, b.glow));
    }

    private float cardEnter(int order) {
        float ms = (System.nanoTime() - builtAt) / 1e6f - order * 60f;
        float e = Math.max(0f, Math.min(1f, ms / 240f));
        return 1f - (1f - e) * (1f - e) * (1f - e);
    }

    private void drawCard(Canvas g, Btn b) {
        Platform pl = b.plat;
        b.glow = smooth(b.glow, b.contains(mouseX, mouseY));
        float enter = cardEnter(b.order);
        if (enter < 0.06f) return;
        boolean sliding = enter < 1f;
        if (sliding) {
            g.pushMatrix();
            g.translate(0f, (1f - enter) * 10f);
        }

        int y = b.y - Math.round(b.glow * 2f);
        int br = Math.min(th.radius + 3, 10);
        if (pl == ctl.platform) PlayerTheme.rrect(g, b.x - 1, y - 1, b.w + 2, b.h + 2, br + 1, fade(th.accent, enter));
        else if (b.glow > 0.03f) PlayerTheme.rrect(g, b.x - 1, y - 1, b.w + 2, b.h + 2, br + 1, PlayerTheme.argb(th.accentRgb, Math.round(120 * b.glow)));
        PlayerTheme.rrect(g, b.x, y, b.w, b.h, br, fade(blend(th.trackBg, th.hover, b.glow), enter));

        int size = 30, ix = b.x + 11;
        PlatformIcons.draw(g, pl, ix, y + (b.h - size) / 2, size);

        int tx = ix + size + 11, tw = b.w - (tx - b.x) - 20;
        int ty = y + (b.h - font.lineHeight * 2 - 3) / 2;
        g.text(font, font.plainSubstrByWidth(pl.title(), tw), tx, ty, fade(PlayerTheme.TEXT_MAIN, enter));
        g.text(font, ellipsize(cardSubtitle(pl), tw), tx, ty + font.lineHeight + 3, fade(PlayerTheme.TEXT_DIM, enter));

        if (cardReady(pl)) {
            boolean live = ctl.current != null && ctl.provider(ctl.current) == ctl.provider(pl) && ctl.engine().isPlaying();
            int alpha = live ? 170 + (int) (85 * Math.sin(System.nanoTime() / 1e9 * 4)) : 255;
            PlayerTheme.circle(g, b.x + b.w - 12, y + b.h / 2, 3, PlayerTheme.argb(0x4ADE80, alpha));
        }
        if (sliding) g.popMatrix();
    }

    private boolean cardReady(Platform pl) {
        MusicProvider pr = ctl.provider(pl);
        return pr != null && pr.isLoggedIn();
    }

    private String cardSubtitle(Platform pl) {
        MusicProvider pr = ctl.provider(pl);
        if (pr == null) return "";
        if (!pl.needsLogin) return pr.accountName().isBlank() ? T.get("card.no_account") : pr.accountName();
        if (!pr.isLoggedIn()) return T.get("card.need_login");
        return pr.accountName().isBlank() ? T.get("card.logged_in") : pr.accountName();
    }

    private void drawIcon(Canvas g, Icon icon, int cx, int cy, int color) {
        switch (icon) {
            case PLAY -> {
                if (ctl.engine().isPlaying()) PlayerTheme.iconPause(g, cx, cy, 11, color);
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

    private void drawWaveButton(Canvas g, Btn b) {
        boolean hov = b.contains(mouseX, mouseY);
        boolean on = ctl.waveActive;
        double secs = System.nanoTime() / 1_000_000_000.0;
        int r = Math.min(th.radius + 3, b.h / 2);
        int waveRgb = wavePaletteAt(secs * 0.18 * WAVE_PALETTE.length);
        int fillRgb = PlayerTheme.mix(waveRgb, 0x111115, hov ? 0.25f : 0.38f);
        Shapes.shadow(g, b.x, b.y, b.w, b.h, r, 1, 0xFF000000 | PlayerTheme.mix(fillRgb, 0x000000, 0.45f));
        Shapes.fill(g, b.x, b.y, b.w, b.h, r, 0xFF000000 | fillRgb);
        if (on) Shapes.ring(g, b.x, b.y, b.w, b.h, r, PlayerTheme.argb(0xFFFFFF, 60));

        boolean playing = on && ctl.engine().isPlaying();
        int bx = b.x + 9, base = b.y + b.h - 6;
        for (int i = 0; i < 4; i++) {
            double k = playing ? 0.5 + 0.5 * Math.sin(secs * (5.0 + i * 1.7) + i * 1.3) : 0.25;
            int bh = 3 + (int) Math.round(k * (b.h - 12));
            g.fill(bx + i * 3, base - bh, bx + i * 3 + 2, base, 0xFFFFFFFF);
        }

        String label;
        if (ctl.waveLoading) {
            int dots = (int) (secs * 3) % 4;
            label = T.get("player.wave_starting") + ".".repeat(dots);
        } else label = T.get(on && ctl.waveView ? "player.wave_stop" : "player.wave");
        int tx = b.x + b.w / 2 + 6;
        int ty = b.y + (b.h - font.lineHeight) / 2 + 1;
        g.text(font, label, tx - font.width(label) / 2 + 1, ty + 1, 0x66000000);
        g.text(font, label, tx - font.width(label) / 2, ty, 0xFFFFFFFF);
    }

    private void drawWaveGlow(Canvas g, int x, int y, int size, double secs) {
        float pulse = (float) (0.5 + 0.5 * Math.sin(secs * 2.0));
        int rgb = wavePaletteAt(secs * 0.5);
        Shapes.ring(g, x - 2, y - 2, size + 4, size + 4, 3, PlayerTheme.argb(rgb, Math.round(90 + 90 * pulse)));
        Shapes.ring(g, x - 3, y - 3, size + 6, size + 6, 3, PlayerTheme.argb(rgb, Math.round(30 + 40 * pulse)));
    }

    private void drawEqualizer(Canvas g, int x, int y, int h, boolean playing) {
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

    private void center(Canvas g, String s, int cx, int y, int color) {
        String fit = font.width(s) > pw - 24 ? font.plainSubstrByWidth(s, pw - 24) : s;
        g.text(font, fit, cx - font.width(fit) / 2, y, color);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private int shiftLabelX, shiftLabelW, shiftRowY;

    /** Сдвиг текста именно этой песни и свой файл текста для неё. */
    private void buildLyricsRow(int y) {
        BooleanSupplier hasTrack = () -> ctl.current != null;
        shiftRowY = y;
        shiftLabelW = Math.max(font.width(T.get("player.shift", "+0.0")) + 8, 70);
        btn(Kind.GHOST, rx, y, 18, 12, "−", Icon.NONE, mx -> TrackLyrics.addShift(ctl.current, -100)).enabled = hasTrack;
        shiftLabelX = rx + 20;
        btn(Kind.GHOST, shiftLabelX + shiftLabelW + 2, y, 18, 12, "+", Icon.NONE, mx -> TrackLyrics.addShift(ctl.current, 100)).enabled = hasTrack;
        Btn reset = btn(Kind.GHOST, shiftLabelX + shiftLabelW + 22, y, 14, 12, "×", Icon.NONE, mx -> TrackLyrics.resetShift(ctl.current));
        reset.visible = () -> TrackLyrics.shiftMs(ctl.current) != 0;

        String add = T.get("player.lyrics_own"), remove = T.get("player.lyrics_own_remove");
        int w = Math.max(font.width(add), font.width(remove)) + 14;
        Btn own = btn(Kind.GHOST, rx + rw - w, y, w, 12, add, Icon.NONE, mx -> ctl.status = T.get("status.lyrics_drop_hint"));
        own.visible = () -> ctl.current != null && !TrackLyrics.hasCustom(ctl.current);
        Btn drop = btn(Kind.GHOST, rx + rw - w, y, w, 12, remove, Icon.NONE, mx -> ctl.removeCustomLyrics());
        drop.visible = () -> TrackLyrics.hasCustom(ctl.current);
    }

    private void drawShiftLabel(Canvas g) {
        int ms = TrackLyrics.shiftMs(ctl.current);
        String v = (ms > 0 ? "+" : "") + String.format(java.util.Locale.ROOT, "%.1f", ms / 1000f);
        String s = T.get("player.shift", v);
        g.text(font, s, shiftLabelX + (shiftLabelW - font.width(s)) / 2, shiftRowY + 2, ms == 0 ? PlayerTheme.TEXT_DIM : th.accent);
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms) / 1000;
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    public void onFilesDrop(List<Path> files) {
        if (!files.isEmpty() && ctl.current != null && files.stream().allMatch(TrackLyrics::isLyricsFile)) {
            ctl.importLyrics(files.get(0));
            return;
        }
        if (!ctl.addLocalPaths(files)) return;
        pickerOpen = false;
        addAccountMode = false;
        queueMode = false;
        page = 0;
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
    public boolean isPauseScreen() { return false; }   // музыка и игра не должны останавливаться
}
