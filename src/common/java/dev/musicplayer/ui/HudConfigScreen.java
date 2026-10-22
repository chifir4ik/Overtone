package dev.musicplayer.ui;

import dev.musicplayer.compat.Game;
import dev.musicplayer.compat.Keys;
import dev.musicplayer.compat.KeyInput;
import dev.musicplayer.compat.ModScreen;
import dev.musicplayer.MusicPlayerMod;
import dev.musicplayer.compat.Canvas;
import dev.musicplayer.config.HudConfig;
import dev.musicplayer.config.PlayerConfig;
import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.hud.LyricsRenderer;
import dev.musicplayer.hud.VisualizerRenderer;
import dev.musicplayer.hud.VolumeRenderer;
import dev.musicplayer.hud.WidgetRenderer;
import dev.musicplayer.hud.WorldLyricsRenderer;
import dev.musicplayer.input.Hotkeys;
import dev.musicplayer.util.T;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import dev.musicplayer.audio.AudioOuts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Настройки мода. Всё нарисовано вручную в стиле окна плеера: слева вкладки, справа прокручиваемый список.
 * Левая кнопка мыши — вперёд, правая — назад (у ползунков — сброс к значению по умолчанию).
 */
public class HudConfigScreen extends ModScreen {
    /** Пока экран открыт, обычный HUD не рисуется — вместо него предпросмотр. */
    public static volatile boolean OPEN;

    private enum Tab { WIDGET, LYRICS, WORLD, VOLUME, VISUALIZER, PLAYER, KEYS, GENERAL }

    private static final int SIDE_W = 92, PAD = 8, BAR_W = 5, HEADER_H = 28, FOOTER_H = 30;
    private static final HudConfig D = HudConfig.DEFAULTS;
    private static final PlayerConfig PD = PlayerConfig.DEFAULTS;

    private final Screen parent;
    private Tab tab = Tab.WIDGET;
    private final List<Row> rows = new ArrayList<>();
    private final List<Row> shown = new ArrayList<>();
    private int first;
    private int maxFirst;
    private Row dragRow;
    private boolean dragBar;
    private Hotkeys.Action listening;
    private long lastDemo;

    private int px, py, pw, ph, cx, cw, bodyTop, bodyBottom;
    private int mouseX, mouseY;
    private PlayerTheme th;
    private final int[] guideBtn = new int[4], doneBtn = new int[4];

    // Невидимые ванильные кнопки под нарисованными элементами: клик принимают они (как в окне плеера).
    private Button doneHit, guideHit;
    private final Button[] tabHits = new Button[Tab.values().length];
    private final Button[] rowHits = new Button[24];

    public HudConfigScreen(Screen parent) {
        super(Component.literal(MusicPlayerMod.NAME));
        this.parent = parent;
    }

    public static HudConfigScreen playerTab(Screen parent) {
        HudConfigScreen s = new HudConfigScreen(parent);
        s.tab = Tab.PLAYER;
        return s;
    }

    private static HudConfig cfg() { return HudConfig.get(); }
    private static PlayerConfig pcfg() { return PlayerConfig.get(); }

    @Override
    protected void init() {
        OPEN = true;
        rebuild();
        buildHits();
    }

    private Button hit(Button b) { addWidget(b); return b; }

    /** Первый клик во время ожидания клавиши только отменяет ожидание. */
    private boolean calm() {
        if (listening != null) { listening = null; return false; }
        return true;
    }

    private void buildHits() {
        doneHit = hit(Hit.make(() -> { if (calm()) onClose(); }));
        guideHit = hit(Hit.make(() -> { if (calm()) Game.open(new HelpScreen(this)); }));
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            Tab t = tabs[i];
            tabHits[i] = hit(Hit.make(() -> { if (calm() && tab != t) { tab = t; rebuild(); } }));
        }
        for (int i = 0; i < rowHits.length; i++) {
            int k = i;
            rowHits[i] = hit(Hit.make(() -> {
                if (!calm() || k >= shown.size()) return;
                Row r = shown.get(k);
                if (r.press(0)) dragRow = r;
            }));
        }
    }

    private void syncHits() {
        Hit.place(doneHit, doneBtn, true);
        Hit.place(guideHit, guideBtn, true);
        for (int i = 0; i < tabHits.length; i++)
            Hit.place(tabHits[i], px + 6, py + HEADER_H + 4 + i * 22, SIDE_W - 6, 20, true);
        for (int i = 0; i < rowHits.length; i++) {
            if (i < shown.size()) {
                Row r = shown.get(i);
                Hit.place(rowHits[i], r.x, r.y, r.w, r.h, true);
            } else {
                Hit.place(rowHits[i], 0, 0, 0, 0, false);
            }
        }
    }

    private void rebuild() {
        pw = Math.min(width - 16, 430);
        ph = Math.min(height - 16, 330);
        py = (height - ph) / 2;
        px = (width - pw) / 2;   // всегда по центру
        cx = px + SIDE_W + PAD;
        cw = pw - SIDE_W - PAD * 2 - BAR_W - 3;
        bodyTop = py + HEADER_H;
        bodyBottom = py + ph - FOOTER_H;
        rows.clear();
        shown.clear();
        first = 0;
        dragRow = null;
        listening = null;
        build();
        updateScrollLimit();
    }

    private void updateScrollLimit() {
        int room = bodyBottom - bodyTop - 4, sum = 0, f = rows.size();
        while (f > 0 && sum + rows.get(f - 1).h <= room) { sum += rows.get(f - 1).h; f--; }
        maxFirst = f;
        first = Math.max(0, Math.min(first, maxFirst));
    }

    private void build() {
        HudConfig c = cfg();
        PlayerConfig p = pcfg();
        switch (tab) {
            case WIDGET -> {
                rows.add(toggle("widget_enabled", () -> c.enabled, v -> c.enabled = v));
                rows.add(choice("layout", () -> c.layout, v -> c.layout = v));
                rows.add(choice("show_mode", () -> c.showMode, v -> c.showMode = v));
                rows.add(toggle("show_on_start", () -> c.showOnSongStart, v -> c.showOnSongStart = v));
                rows.add(toggle("show_on_end", () -> c.showOnSongEnd, v -> c.showOnSongEnd = v));
                rows.add(header("look"));
                rows.add(swatches("accent", () -> c.accent, v -> { c.accent = v; c.customAccent = -1; },
                        () -> c.customAccent, v -> c.customAccent = v));
                rows.add(slider("scale", 0.5, 2.0, 0.05, D.scale, () -> c.scale, v -> c.scale = (float) v, HudConfigScreen::pct));
                rows.add(slider("background", 0, 100, 5, D.backgroundOpacity, () -> c.backgroundOpacity, v -> c.backgroundOpacity = (int) v, HudConfigScreen::percent));
                rows.add(toggle("cover", () -> c.showCover, v -> c.showCover = v));
                rows.add(toggle("time", () -> c.showTimestamps, v -> c.showTimestamps = v));
                rows.add(toggle("equalizer", () -> c.showWaveform, v -> c.showWaveform = v));
                rows.add(header("position"));
                rows.add(anchors("position", () -> c.anchor, v -> c.anchor = v));
                rows.add(slider("offset_x", -100, 400, 1, D.offsetX, () -> c.offsetX, v -> c.offsetX = (int) v, HudConfigScreen::px));
                rows.add(slider("offset_y", -100, 400, 1, D.offsetY, () -> c.offsetY, v -> c.offsetY = (int) v, HudConfigScreen::px));
                rows.add(dragButton());
            }
            case LYRICS -> {
                rows.add(choice("lyrics_mode", () -> c.lyricsMode, v -> c.lyricsMode = v));
                rows.add(toggle("card_lyrics", () -> c.cardLyrics, v -> c.cardLyrics = v));
                rows.add(toggle("word_karaoke", () -> c.lyricsWordKaraoke, v -> c.lyricsWordKaraoke = v));
                rows.add(slider("shift", -3000, 3000, 50, D.lyricsShiftMs, () -> c.lyricsShiftMs, v -> c.lyricsShiftMs = (int) v, HudConfigScreen::ms));
                rows.add(header("look"));
                rows.add(slider("scale", 0.5, 2.0, 0.05, D.lyricsScale, () -> c.lyricsScale, v -> c.lyricsScale = (float) v, HudConfigScreen::pct));
                rows.add(slider("width", 200, 600, 10, D.lyricsWidth, () -> c.lyricsWidth, v -> c.lyricsWidth = (int) v, HudConfigScreen::px));
                rows.add(slider("lines", 3, 9, 1, D.lyricsLines, () -> c.lyricsLines, v -> c.lyricsLines = (int) v, HudConfigScreen::num));
                rows.add(slider("background", 0, 100, 5, D.lyricsBackground, () -> c.lyricsBackground, v -> c.lyricsBackground = (int) v, HudConfigScreen::percent));
                rows.add(toggle("lyrics_accent", () -> c.lyricsAccent, v -> c.lyricsAccent = v));
                rows.add(toggle("text_shadow", () -> c.lyricsShadow, v -> c.lyricsShadow = v));
                rows.add(header("position"));
                rows.add(anchors("position", () -> c.lyricsAnchor, v -> c.lyricsAnchor = v));
                rows.add(slider("offset_x", -100, 400, 1, D.lyricsOffsetX, () -> c.lyricsOffsetX, v -> c.lyricsOffsetX = (int) v, HudConfigScreen::px));
                rows.add(slider("offset_y", -100, 400, 1, D.lyricsOffsetY, () -> c.lyricsOffsetY, v -> c.lyricsOffsetY = (int) v, HudConfigScreen::px));
                rows.add(dragButton());
            }
            case WORLD -> {
                rows.add(toggle("world_enabled", () -> c.worldLyrics, v -> c.worldLyrics = v));
                rows.add(choice("world_mode", () -> c.worldMode, v -> c.worldMode = v));
                rows.add(choice("follow", () -> c.worldFollowStyle, v -> c.worldFollowStyle = v));
                rows.add(choice("word_layout", () -> c.worldLayout, v -> c.worldLayout = v));
                rows.add(header("look"));
                rows.add(slider("size", 0.5, 2.5, 0.1, D.worldScale, () -> c.worldScale, v -> c.worldScale = (float) v, HudConfigScreen::pct));
                rows.add(slider("distance", 3, 30, 0.5, D.worldDistance, () -> c.worldDistance, v -> c.worldDistance = (float) v, HudConfigScreen::blocks));
                rows.add(slider("height", -1.5, 3, 0.1, D.worldHeight, () -> c.worldHeight, v -> c.worldHeight = (float) v, HudConfigScreen::blocksSigned));
                rows.add(slider("world_spread", 15, 90, 1, D.worldSpread, () -> c.worldSpread, v -> c.worldSpread = (float) v, HudConfigScreen::degrees));
                rows.add(toggle("see_through", () -> c.worldSeeThrough, v -> c.worldSeeThrough = v));
                rows.add(toggle("text_shadow", () -> c.worldShadow, v -> c.worldShadow = v));
                rows.add(button("demo", WorldLyricsRenderer::demo, false));
                rows.add(header("animation"));
                rows.add(choice("world_anim", () -> c.worldAnim, v -> c.worldAnim = v));
                rows.add(choice("world_unit", () -> c.worldUnit, v -> c.worldUnit = v));
                rows.add(choice("world_exit", () -> c.worldExit, v -> c.worldExit = v));
                rows.add(slider("world_linger", 1, 20, 1, D.worldLinger, () -> c.worldLinger, v -> c.worldLinger = (int) v, HudConfigScreen::seconds));
                rows.add(slider("world_letter_ms", 5, 150, 5, D.worldLetterMs, () -> c.worldLetterMs, v -> c.worldLetterMs = (int) v, HudConfigScreen::ms));
                rows.add(slider("world_speed", 25, 300, 5, D.worldAnimSpeed, () -> c.worldAnimSpeed, v -> c.worldAnimSpeed = (int) v, HudConfigScreen::percent));
                rows.add(slider("world_bounce", 0, 300, 10, D.worldBounce, () -> c.worldBounce, v -> c.worldBounce = (int) v, HudConfigScreen::percent));
                rows.add(header("colors"));
                rows.add(choice("world_color", () -> c.worldColor, v -> c.worldColor = v));
                rows.add(plainSwatches("world_accent", () -> c.worldAccent, v -> c.worldAccent = v));
                rows.add(plainSwatches("world_accent2", () -> c.worldAccent2, v -> c.worldAccent2 = v));
                rows.add(choice("world_glow", () -> c.worldGlow, v -> c.worldGlow = v));
                rows.add(slider("world_glow_strength", 0, 100, 5, D.worldGlowStrength, () -> c.worldGlowStrength, v -> c.worldGlowStrength = (int) v, HudConfigScreen::percent));
                rows.add(slider("world_glow_size", 50, 250, 10, D.worldGlowSize, () -> c.worldGlowSize, v -> c.worldGlowSize = (int) v, HudConfigScreen::percent));
                rows.add(button("demo", WorldLyricsRenderer::demo, false));
                rows.add(note(T.get("note.world")));
            }
            case VOLUME -> {
                rows.add(toggle("volume_enabled", () -> c.volumeEnabled, v -> c.volumeEnabled = v));
                rows.add(choice("volume_style", () -> c.volumeStyle, v -> c.volumeStyle = v));
                rows.add(slider("seconds", 1, 10, 1, D.volumeSeconds, () -> c.volumeSeconds, v -> c.volumeSeconds = (int) v, HudConfigScreen::seconds));
                rows.add(toggle("percent", () -> c.volumePercent, v -> c.volumePercent = v));
                rows.add(header("look"));
                rows.add(slider("scale", 0.5, 2.0, 0.05, D.volumeScale, () -> c.volumeScale, v -> c.volumeScale = (float) v, HudConfigScreen::pct));
                rows.add(slider("background", 0, 100, 5, D.volumeBackground, () -> c.volumeBackground, v -> c.volumeBackground = (int) v, HudConfigScreen::percent));
                rows.add(header("position"));
                rows.add(anchors("position", () -> c.volumeAnchor, v -> c.volumeAnchor = v));
                rows.add(slider("offset_x", -100, 400, 1, D.volumeOffsetX, () -> c.volumeOffsetX, v -> c.volumeOffsetX = (int) v, HudConfigScreen::px));
                rows.add(slider("offset_y", -100, 400, 1, D.volumeOffsetY, () -> c.volumeOffsetY, v -> c.volumeOffsetY = (int) v, HudConfigScreen::px));
                rows.add(dragButton());
                rows.add(note(T.get("note.volume")));
            }
            case VISUALIZER -> {
                rows.add(toggle("viz_enabled", () -> c.vizEnabled, v -> c.vizEnabled = v));
                rows.add(choice("viz_source", () -> c.vizSource, v -> c.vizSource = v));
                rows.add(choice("viz_style", () -> c.vizStyle, v -> c.vizStyle = v));
                rows.add(toggle("viz_stereo", () -> c.vizStereo, v -> c.vizStereo = v));
                rows.add(choice("viz_direction", () -> c.vizDirection, v -> c.vizDirection = v));
                rows.add(choice("viz_color", () -> c.vizColor, v -> c.vizColor = v));
                rows.add(swatches("accent", () -> c.accent, v -> { c.accent = v; c.customAccent = -1; },
                        () -> c.customAccent, v -> c.customAccent = v));
                rows.add(toggle("viz_peaks", () -> c.vizPeaks, v -> c.vizPeaks = v));
                rows.add(toggle("viz_hide_idle", () -> c.vizHideIdle, v -> c.vizHideIdle = v));
                rows.add(header("viz_sound"));
                rows.add(toggle("viz_autosens", () -> c.vizAutoSens, v -> c.vizAutoSens = v));
                rows.add(slider("viz_sensitivity", 10, 400, 10, D.vizSensitivity, () -> c.vizSensitivity, v -> c.vizSensitivity = (int) v, HudConfigScreen::percent));
                rows.add(slider("viz_smoothing", 0, 95, 5, D.vizSmoothing, () -> c.vizSmoothing, v -> c.vizSmoothing = (int) v, HudConfigScreen::percent));
                rows.add(header("look"));
                rows.add(slider("viz_bars", 4, 96, 1, D.vizBars, () -> c.vizBars, v -> c.vizBars = (int) v, HudConfigScreen::num));
                rows.add(slider("viz_bar_width", 1, 10, 1, D.vizBarWidth, () -> c.vizBarWidth, v -> c.vizBarWidth = (int) v, HudConfigScreen::px));
                rows.add(slider("viz_gap", 0, 6, 1, D.vizGap, () -> c.vizGap, v -> c.vizGap = (int) v, HudConfigScreen::px));
                rows.add(slider("viz_height", 8, 160, 2, D.vizHeight, () -> c.vizHeight, v -> c.vizHeight = (int) v, HudConfigScreen::px));
                rows.add(slider("scale", 0.5, 2.0, 0.05, D.vizScale, () -> c.vizScale, v -> c.vizScale = (float) v, HudConfigScreen::pct));
                rows.add(slider("background", 0, 100, 5, D.vizBackground, () -> c.vizBackground, v -> c.vizBackground = (int) v, HudConfigScreen::percent));
                rows.add(header("position"));
                rows.add(anchors("position", () -> c.vizAnchor, v -> c.vizAnchor = v));
                rows.add(slider("offset_x", -100, 400, 1, D.vizOffsetX, () -> c.vizOffsetX, v -> c.vizOffsetX = (int) v, HudConfigScreen::px));
                rows.add(slider("offset_y", -100, 400, 1, D.vizOffsetY, () -> c.vizOffsetY, v -> c.vizOffsetY = (int) v, HudConfigScreen::px));
                rows.add(dragButton());
                rows.add(note(T.get("note.visualizer")));
            }
            case PLAYER -> {
                rows.add(new PreviewRow());
                rows.add(toggle("sync_accent", () -> p.syncAccent, v -> p.syncAccent = v));
                rows.add(swatches("accent", () -> p.syncAccent ? c.accent : p.accent, v -> { p.syncAccent = false; p.accent = v; p.customAccent = -1; },
                        () -> p.syncAccent ? c.customAccent : p.customAccent, v -> { p.syncAccent = false; p.customAccent = v; }));
                rows.add(slider("window_size", 70, 150, 5, PD.windowScale, () -> p.windowScale, v -> p.windowScale = (int) v, HudConfigScreen::percent));
                rows.add(slider("corner", 0, 8, 1, PD.cornerRadius, () -> p.cornerRadius, v -> p.cornerRadius = (int) v, HudConfigScreen::num));
                rows.add(slider("tint", 0, 3, 1, PD.tint, () -> p.tint, v -> p.tint = (int) v, v -> Math.round(v * 5) + "%"));
                rows.add(slider("opacity", 20, 100, 5, PD.windowOpacity, () -> p.windowOpacity, v -> p.windowOpacity = (int) v, HudConfigScreen::percent));
                rows.add(slider("dim", 0, 100, 5, PD.dim, () -> p.dim, v -> p.dim = (int) v, HudConfigScreen::percent));
                rows.add(toggle("cover", () -> p.showCover, v -> p.showCover = v));
                rows.add(slider("cover_size", 40, 140, 4, PD.coverPx, () -> p.coverPx, v -> p.coverPx = (int) v, HudConfigScreen::px));
                rows.add(toggle("lyrics_in_player", () -> p.showLyrics, v -> p.showLyrics = v));
                rows.add(toggle("accent_title", () -> p.accentText, v -> p.accentText = v));
                rows.add(button("reset_player", () -> { PlayerConfig.resetToDefaults(); rebuild(); }, true));
            }
            case KEYS -> {
                for (Hotkeys.Action a : Hotkeys.Action.values()) rows.add(new KeyRow(a));
                rows.add(button("reset_keys", () -> { Hotkeys.resetAll(); listening = null; }, true));
                rows.add(note(T.get("note.keys")));
            }
            case GENERAL -> {
                rows.add(toggle("master", () -> c.master, v -> c.master = v));
                rows.add(toggle("hide_with_gui", () -> c.hideWithGui, v -> c.hideWithGui = v));
                rows.add(toggle("hide_on_tab", () -> c.hideOnTab, v -> c.hideOnTab = v));
                rows.add(header("sound"));
                rows.add(toggle("game_volume", () -> p.useGameVolume, v -> p.useGameVolume = v));
                rows.add(new ButtonRow("audio_device", () -> {
                    String d = p.audioDevice == null ? "" : p.audioDevice;
                    return T.get("set.audio_device", d.isEmpty() ? T.get("audio_device.auto") : d);
                }, () -> {
                    List<String> all = new ArrayList<>();
                    all.add("");
                    all.addAll(AudioOuts.devices());
                    int i = all.indexOf(p.audioDevice == null ? "" : p.audioDevice);
                    String next = all.get((i + 1) % all.size());
                    if (!next.equals(p.audioDevice == null ? "" : p.audioDevice)) {
                        p.audioDevice = next;
                        PlayerController.I.restartForAudioDevice();
                    }
                }, false));
                rows.add(toggle("duck", () -> p.duckWhenInactive, v -> p.duckWhenInactive = v));
                rows.add(header("loading"));
                rows.add(toggle("prefetch", () -> p.prefetch, v -> { p.prefetch = v; PlayerController.I.refreshPrefetch(); }));
                rows.add(slider("prefetch_count", 1, 8, 1, PD.prefetchCount, () -> p.prefetchCount,
                        v -> { p.prefetchCount = (int) v; PlayerController.I.refreshPrefetch(); }, HudConfigScreen::num));
                rows.add(toggle("disk_cache", () -> p.diskCache, v -> p.diskCache = v));
                rows.add(new ButtonRow("clear_cache", () -> T.get("btn.clear_cache", PlayerController.I.yandex.cacheMegabytes()),
                        () -> PlayerController.I.yandex.clearCache(), false));
                rows.add(toggle("online_lyrics", () -> p.onlineLyrics, v -> p.onlineLyrics = v));
                rows.add(toggle("amll_lyrics", () -> p.amllLyrics, v -> p.amllLyrics = v));
                rows.add(toggle("kugou_lyrics", () -> p.kugouLyrics, v -> p.kugouLyrics = v));
                rows.add(button("open_folder", () -> PlayerController.I.openLocalFolder(), false));
                rows.add(toggle("discord", () -> p.discordPresence, v -> p.discordPresence = v));
                rows.add(header("other"));
                rows.add(button("guide", () -> Game.open(new HelpScreen(this)), false));
                rows.add(button("reset_hud", () -> { HudConfig.resetToDefaults(); rebuild(); }, true));
                rows.add(note(T.get("about", MusicPlayerMod.NAME, MusicPlayerMod.AUTHORS)));
            }
        }
    }

    private static String pct(double v) { return Math.round(v * 100) + "%"; }
    private static String percent(double v) { return Math.round(v) + "%"; }
    private static String px(double v) { return Math.round(v) + " px"; }
    private static String num(double v) { return String.valueOf(Math.round(v)); }
    private static String seconds(double v) { return Math.round(v) + " " + T.get("unit.s"); }
    private static String ms(double v) { return (v > 0 ? "+" : "") + Math.round(v) + " " + T.get("unit.ms"); }
    private static String blocks(double v) { return String.format(Locale.ROOT, "%.1f", v); }
    private static String blocksSigned(double v) { return String.format(Locale.ROOT, "%+.1f", v); }


    private abstract class Row {
        final String id;
        final int h;
        int x, y, w;

        Row(String id, int h) { this.id = id; this.h = h; }

        abstract void draw(boolean hover);

        /** true — строка хочет получать перетаскивание. */
        boolean press(int button) { return false; }

        void drag() { }

        String hint() { return id == null ? null : T.get("hint." + id); }

        void label(String text, int maxW) {
            g().text(font, font.plainSubstrByWidth(text, maxW), x, y + (h - font.lineHeight) / 2 + 1, PlayerTheme.TEXT_MAIN);
        }

        boolean inside(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    private Canvas graphics;

    private Canvas g() { return graphics; }

    private void touched() {
        if (tab != Tab.WORLD) return;
        long now = System.currentTimeMillis();
        if (now - lastDemo < 700) return;
        lastDemo = now;
        WorldLyricsRenderer.demo();
    }

    private Row toggle(String id, BooleanSupplier get, Consumer<Boolean> set) {
        return new Row(id, 24) {
            @Override void draw(boolean hover) {
                label(T.get("set." + id), w - 46);
                boolean on = get.getAsBoolean();
                int sx = x + w - 34, sy = y + (h - 14) / 2;
                PlayerTheme.rrect(g(), sx, sy, 34, 14, 7, on ? th.accent : (hover ? th.hover : th.trackBg));
                PlayerTheme.circle(g(), on ? sx + 27 : sx + 7, sy + 7, 5, PlayerTheme.TEXT_MAIN);
            }
            @Override boolean press(int button) { set.accept(!get.getAsBoolean()); touched(); return false; }
        };
    }

    private Row slider(String id, double min, double max, double step, double def,
                       DoubleSupplier get, DoubleConsumer set, DoubleFunction<String> fmt) {
        return new Row(id, 34) {
            @Override void draw(boolean hover) {
                double v = get.getAsDouble();
                String value = fmt.apply(v);
                int vw = font.width(value);
                g().text(font, font.plainSubstrByWidth(T.get("set." + id), w - vw - 10), x, y + 3, PlayerTheme.TEXT_MAIN);
                g().text(font, value, x + w - vw, y + 3, th.accent);
                int ty = y + 21;
                boolean hot = hover || dragRow == this;
                PlayerTheme.rrect(g(), x, ty - 2, w, 4, 2, th.trackBg);
                double t = Math.max(0, Math.min(1, (v - min) / (max - min)));
                int fill = (int) Math.round(w * t);
                if (fill > 0) PlayerTheme.rrect(g(), x, ty - 2, Math.max(4, fill), 4, 2, th.accent);
                int dx = x + (int) Math.round(w * (def - min) / (max - min));
                g().fill(dx, ty - 4, dx + 1, ty + 4, PlayerTheme.argb(0xFFFFFF, 70));
                PlayerTheme.circle(g(), Math.max(x + 4, Math.min(x + w - 4, x + fill)), ty, hot ? 6 : 5, th.accent);
                PlayerTheme.circle(g(), Math.max(x + 4, Math.min(x + w - 4, x + fill)), ty, hot ? 3 : 2, PlayerTheme.TEXT_MAIN);
            }
            @Override boolean press(int button) {
                if (button == 1) { set.accept(def); touched(); return false; }   // двойной клик — значение по умолчанию
                drag();
                return true;
            }
            @Override void drag() {
                double t = Math.max(0, Math.min(1, (mouseX - x) / (double) w));
                double v = min + Math.round((t * (max - min)) / step) * step;
                set.accept(Math.max(min, Math.min(max, v)));
                touched();
            }
        };
    }

    private <E extends Enum<E>> Row choice(String id, Supplier<E> get, Consumer<E> set) {
        return new Row(id, 24) {
            @Override void draw(boolean hover) {
                int pillW = Math.min(150, w / 2 + 10);
                label(T.get("set." + id), w - pillW - 8);
                int sx = x + w - pillW, sy = y + 2;
                PlayerTheme.rrect(g(), sx, sy, pillW, 20, Math.min(th.radius + 1, 10), hover ? th.hover : th.trackBg);
                String v = font.plainSubstrByWidth(HudConfig.label(get.get()), pillW - 26);
                g().text(font, v, sx + (pillW - font.width(v)) / 2, sy + 6, PlayerTheme.TEXT_MAIN);
                int ay = sy + 10;
                PlayerTheme.tri(g(), sx + 6, ay, 4, 7, false, hover ? th.accent : PlayerTheme.TEXT_DIM);
                PlayerTheme.tri(g(), sx + pillW - 10, ay, 4, 7, true, hover ? th.accent : PlayerTheme.TEXT_DIM);
            }
            @Override boolean press(int button) {
                // Номер кнопки мыши в этой версии игры ненадёжен: левая половина «таблетки» — назад, остальное — вперёд.
                int pillW = Math.min(150, w / 2 + 10), sx = x + w - pillW;
                if (mouseX >= sx && mouseX < sx + pillW / 2) set.accept(HudConfig.prev(get.get()));
                else set.accept(HudConfig.next(get.get()));
                touched();
                return false;
            }
        };
    }

    /** Готовые цвета и в конце серый кружок: по нему открывается палитра для своего цвета (custom = -1, пока он не выбран). */
    private Row swatches(String id, Supplier<HudConfig.Accent> get, Consumer<HudConfig.Accent> set,
                         IntSupplier custom, IntConsumer setCustom) {
        return new Row(id, 28) {
            private static final int R = 6, GAP = 5;
            private final int count = HudConfig.Accent.values().length + 1;
            private int left() { return x + w - count * (R * 2 + GAP) + GAP; }
            @Override void draw(boolean hover) {
                label(T.get("set." + id), left() - x - 8);
                HudConfig.Accent cur = get.get();
                int own = custom.getAsInt();
                int sx = left(), cym = y + h / 2;
                for (HudConfig.Accent a : HudConfig.Accent.values()) {
                    if (own < 0 && a == cur) PlayerTheme.circle(g(), sx + R, cym, R + 2, PlayerTheme.TEXT_MAIN);
                    PlayerTheme.circle(g(), sx + R, cym, R, 0xFF000000 | a.rgb);
                    sx += R * 2 + GAP;
                }
                if (own >= 0) PlayerTheme.circle(g(), sx + R, cym, R + 2, PlayerTheme.TEXT_MAIN);
                PlayerTheme.circle(g(), sx + R, cym, R, own >= 0 ? 0xFF000000 | own : 0xFF8A8A92);
            }
            @Override boolean press(int button) {
                int i = (mouseX - left()) / (R * 2 + GAP);
                HudConfig.Accent[] all = HudConfig.Accent.values();
                if (mouseX < left() || i < 0) return false;
                if (i < all.length) { set.accept(all[i]); return false; }
                int start = custom.getAsInt() >= 0 ? custom.getAsInt() : get.get().rgb;
                Game.open(new ColorPickerScreen(HudConfigScreen.this, start, setCustom));
                return false;
            }
        };
    }

    /** Только готовые цвета, без кружка для своего цвета. */
    private Row plainSwatches(String id, Supplier<HudConfig.Accent> get, Consumer<HudConfig.Accent> set) {
        return new Row(id, 28) {
            private static final int R = 6, GAP = 5;
            private final int count = HudConfig.Accent.values().length;
            private int left() { return x + w - count * (R * 2 + GAP) + GAP; }
            @Override void draw(boolean hover) {
                label(T.get("set." + id), left() - x - 8);
                HudConfig.Accent cur = get.get();
                int sx = left(), cym = y + h / 2;
                for (HudConfig.Accent a : HudConfig.Accent.values()) {
                    if (a == cur) PlayerTheme.circle(g(), sx + R, cym, R + 2, PlayerTheme.TEXT_MAIN);
                    PlayerTheme.circle(g(), sx + R, cym, R, 0xFF000000 | a.rgb);
                    sx += R * 2 + GAP;
                }
            }
            @Override boolean press(int button) {
                int i = (mouseX - left()) / (R * 2 + GAP);
                HudConfig.Accent[] all = HudConfig.Accent.values();
                if (mouseX < left() || i < 0 || i >= all.length) return false;
                set.accept(all[i]);
                return false;
            }
        };
    }

    private static String degrees(double v) { return Math.round(v) + "°"; }

    private Row anchors(String id, Supplier<HudConfig.Anchor> get, Consumer<HudConfig.Anchor> set) {
        return new Row(id, 50) {
            private static final int CELL = 14, GAP = 2;
            private int left() { return x + w - (CELL * 3 + GAP * 2); }
            @Override void draw(boolean hover) {
                label(T.get("set." + id) + ": " + HudConfig.label(get.get()), left() - x - 8);
                HudConfig.Anchor cur = get.get();
                for (HudConfig.Anchor a : HudConfig.Anchor.values()) {
                    int ax = left() + (a.h + 1) * (CELL + GAP), ay = y + 3 + (a.v + 1) * (CELL + GAP);
                    boolean over = mouseX >= ax && mouseX < ax + CELL && mouseY >= ay && mouseY < ay + CELL;
                    PlayerTheme.rrect(g(), ax, ay, CELL, CELL, 3, a == cur ? th.accent : over ? th.hover : th.trackBg);
                }
            }
            @Override boolean press(int button) {
                for (HudConfig.Anchor a : HudConfig.Anchor.values()) {
                    int ax = left() + (a.h + 1) * (CELL + GAP), ay = y + 3 + (a.v + 1) * (CELL + GAP);
                    if (mouseX >= ax && mouseX < ax + CELL && mouseY >= ay && mouseY < ay + CELL) { set.accept(a); break; }
                }
                return false;
            }
        };
    }

    private Row header(String key) {
        return new Row(null, 22) {
            @Override void draw(boolean hover) {
                String text = T.get("section." + key).toUpperCase(Locale.ROOT);
                g().text(font, text, x, y + 11, th.accent);
                int lx = x + font.width(text) + 6;
                g().fill(lx, y + 15, x + w, y + 16, PlayerTheme.argb(0xFFFFFF, 22));
            }
        };
    }

    private Row note(String text) {
        List<FormattedCharSequence> lines = font.split(Component.literal(text), cw);
        return new Row(null, lines.size() * (font.lineHeight + 2) + 10) {
            @Override void draw(boolean hover) {
                for (int i = 0; i < lines.size(); i++)
                    g().text(font, lines.get(i), x, y + 6 + i * (font.lineHeight + 2), PlayerTheme.TEXT_DIM);
            }
        };
    }

    private Row button(String id, Runnable action, boolean confirm) {
        return new ButtonRow(id, () -> T.get("btn." + id), action, confirm);
    }

    private Row dragButton() {
        return new ButtonRow("drag", () -> T.get("btn.drag"), () -> Game.open(new HudEditScreen(this)), false);
    }

    private final class ButtonRow extends Row {
        private final Supplier<String> label;
        private final Runnable action;
        private final boolean confirm;
        private boolean armed;
        private long armedAt;

        ButtonRow(String id, Supplier<String> label, Runnable action, boolean confirm) {
            super(id, 26);
            this.label = label; this.action = action; this.confirm = confirm;
        }

        @Override void draw(boolean hover) {
            if (armed && System.currentTimeMillis() - armedAt > 3000) armed = false;
            int by = y + 2;
            int bg = armed ? th.accent : hover ? th.hover : th.trackBg;
            PlayerTheme.rrect(g(), x, by, w, 22, Math.min(th.radius + 1, 10), bg);
            String text = font.plainSubstrByWidth(armed ? T.get("btn.confirm") : label.get(), w - 12);
            g().text(font, text, x + (w - font.width(text)) / 2, by + 7, PlayerTheme.TEXT_MAIN);
        }

        @Override boolean press(int button) {
            if (confirm && !armed) { armed = true; armedAt = System.currentTimeMillis(); return false; }
            armed = false;
            action.run();
            return false;
        }
    }

    private final class KeyRow extends Row {
        private final Hotkeys.Action action;

        KeyRow(Hotkeys.Action action) { super("key", 24); this.action = action; }

        @Override String hint() { return T.get("hint.key"); }

        @Override void draw(boolean hover) {
            int pillW = 124;
            label(action.label(), w - pillW - 8);
            int sx = x + w - pillW, sy = y + 2;
            boolean on = listening == action;
            if (on) PlayerTheme.rrect(g(), sx - 1, sy - 1, pillW + 2, 22, Math.min(th.radius + 2, 11), th.accent);
            PlayerTheme.rrect(g(), sx, sy, pillW, 20, Math.min(th.radius + 1, 10), on ? th.fieldBg : hover ? th.hover : th.trackBg);
            String v = font.plainSubstrByWidth(on ? T.get("keys.press") : Hotkeys.keyName(action), pillW - 10);
            int color = on ? th.accent : Hotkeys.isDefault(action) ? PlayerTheme.TEXT_MAIN : th.accent;
            g().text(font, v, sx + (pillW - font.width(v)) / 2, sy + 6, color);
        }

        @Override boolean press(int button) {
            listening = action;
            return false;
        }
    }

    private final class PreviewRow extends Row {
        PreviewRow() { super("preview", 72); }

        @Override void draw(boolean hover) { PlayerTheme.miniPreview(g(), font, x, y + 4, w, 62); }
    }


    private boolean in(int[] r, double mx, double my) { return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]; }

    @Override
    protected boolean onClick(double ex, double ey, int rawButton, boolean doubleClick) {
        mouseX = (int) ex;
        mouseY = (int) ey;
        // Номер кнопки из события не используем (в этой версии левая кнопка приходит как 1).
        // «Альтернативное» действие (сброс ползунка) — двойной клик.
        int button = doubleClick ? 1 : 0;
        if (listening != null) { listening = null; return true; }

        if (in(doneBtn, mouseX, mouseY)) { onClose(); return true; }
        if (in(guideBtn, mouseX, mouseY)) { Game.open(new HelpScreen(this)); return true; }
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            int ty = py + HEADER_H + 4 + i * 22;
            if (mouseX >= px + 6 && mouseX < px + SIDE_W && mouseY >= ty && mouseY < ty + 20) {
                if (tab != tabs[i]) { tab = tabs[i]; rebuild(); }
                return true;
            }
        }
        if (maxFirst > 0 && mouseX >= cx + cw + 2 && mouseX < cx + cw + 3 + BAR_W + 4 && mouseY >= bodyTop && mouseY < bodyBottom) {
            dragBar = true;
            scrollToMouse();
            return true;
        }
        for (Row r : shown) {
            if (!r.inside(mouseX, mouseY)) continue;
            if (r.press(button)) dragRow = r;
            return true;
        }
        return super.onClick(ex, ey, rawButton, doubleClick);
    }

    @Override
    protected boolean onDrag(double ex, double ey, int button, double dragX, double dragY) {
        mouseX = (int) ex;
        mouseY = (int) ey;
        if (dragBar) { scrollToMouse(); return true; }
        if (dragRow != null) { dragRow.drag(); return true; }
        return super.onDrag(ex, ey, button, dragX, dragY);
    }

    @Override
    protected boolean onRelease(double ex, double ey, int button) {
        mouseX = (int) ex;
        mouseY = (int) ey;
        boolean was = dragRow != null || dragBar;
        if (dragRow != null) { lastDemo = 0; touched(); }
        dragRow = null;
        dragBar = false;
        return was || super.onRelease(ex, ey, button);
    }

    private void scrollToMouse() {
        double t = (mouseY - bodyTop) / (double) (bodyBottom - bodyTop);
        first = Math.max(0, Math.min(maxFirst, (int) Math.round(t * maxFirst)));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (mx >= px + SIDE_W && mx < px + pw) {
            first = Math.max(0, Math.min(maxFirst, first + (sy < 0 ? 1 : -1)));
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    protected boolean onKey(KeyInput event) {
        int k = event.key();
        if (listening != null) {
            Hotkeys.Action a = listening;
            listening = null;
            if (k != Keys.ESCAPE) Hotkeys.setKey(a, (k == Keys.DELETE || k == Keys.BACKSPACE) ? -1 : k);   // Esc — отмена, Delete/Backspace — снять
            return true;
        }
        // Enter/Space не должны «нажимать» невидимую кнопку, оставшуюся в фокусе после клика.
        if (k == Keys.SPACE || k == Keys.ENTER || k == Keys.KP_ENTER) return false;
        return super.onKey(event);
    }


    @Override
    protected void draw(Canvas g, int mx, int my, float delta) {
        mouseX = mx; mouseY = my;
        graphics = g;
        th = PlayerTheme.current();
        drawWidgets(g, mx, my, delta);

        drawPreview(g);
        drawPanel(g);
        syncHits();
    }

    private void drawPreview(Canvas g) {
        if (tab == Tab.WORLD) return;   // текст летает в самом мире за этим экраном
        if (tab == Tab.VOLUME) { VolumeRenderer.render(g, true); return; }
        if (tab == Tab.VISUALIZER) { VisualizerRenderer.render(g, true); return; }
        if (tab == Tab.WIDGET || tab == Tab.LYRICS) {
            Canvas canvas = g;
            WidgetRenderer.render(canvas, true);
            LyricsRenderer.render(canvas, true);
        }
    }

    private void drawPanel(Canvas g) {
        th.window(g, px, py, pw, ph);
        drawHeader(g);
        PlayerTheme.rrect(g, px + 4, py + HEADER_H, SIDE_W - 6, ph - HEADER_H - 4, Math.max(0, th.radius - 1), th.panel2);
        drawTabs(g);
        drawRows(g);
        drawFooter(g);
    }

    private void drawHeader(Canvas g) {
        g.text(font, "♪ " + MusicPlayerMod.NAME, px + 12, py + 10, th.accent);
        g.text(font, font.plainSubstrByWidth(T.get("settings"), pw / 4), px + 14 + font.width("♪ " + MusicPlayerMod.NAME) + 6, py + 10, PlayerTheme.TEXT_DIM);

        String guide = T.get("btn.guide_short");
        int gw = font.width(guide) + 16;
        pill(g, guideBtn, px + pw - 10 - gw, py + 5, gw, guide);
        g.fill(px + 10, py + HEADER_H - 2, px + pw - 10, py + HEADER_H - 1, PlayerTheme.argb(0xFFFFFF, 16));
    }

    private void pill(Canvas g, int[] rect, int x, int y, int w, String text) {
        rect[0] = x; rect[1] = y; rect[2] = w; rect[3] = 16;
        boolean hover = in(rect, mouseX, mouseY);
        PlayerTheme.rrect(g, x, y, w, 16, 8, hover ? th.hover : th.trackBg);
        g.text(font, text, x + 8, y + 4, hover ? PlayerTheme.TEXT_MAIN : PlayerTheme.TEXT_MID);
    }

    private void drawTabs(Canvas g) {
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            int ty = py + HEADER_H + 4 + i * 22, tx = px + 6, tw = SIDE_W - 8;
            boolean active = tabs[i] == tab;
            boolean hover = mouseX >= tx && mouseX < tx + tw && mouseY >= ty && mouseY < ty + 20;
            if (active) PlayerTheme.rrect(g, tx, ty, tw, 20, 4, th.rowCurrent);
            else if (hover) PlayerTheme.rrect(g, tx, ty, tw, 20, 4, th.hover);
            if (active) g.fill(tx, ty + 3, tx + 2, ty + 17, th.accent);
            String name = font.plainSubstrByWidth(T.get("tab." + tabs[i].name().toLowerCase(Locale.ROOT)), tw - 14);
            g.text(font, name, tx + 8, ty + 6, active ? th.accent : hover ? PlayerTheme.TEXT_MAIN : PlayerTheme.TEXT_MID);
        }
    }

    private void drawRows(Canvas g) {
        shown.clear();
        int y = bodyTop + 2;
        for (int i = first; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (y + r.h > bodyBottom) break;
            r.x = cx; r.y = y; r.w = cw;
            shown.add(r);
            r.draw(r.inside(mouseX, mouseY) && dragRow == null || dragRow == r);
            y += r.h;
        }
        if (maxFirst <= 0) return;
        int bx = cx + cw + 3, top = bodyTop + 2, trackH = bodyBottom - bodyTop - 4;
        PlayerTheme.rrect(g, bx, top, BAR_W, trackH, 2, th.fieldBg);
        int thumbH = Math.max(16, trackH * shown.size() / Math.max(1, rows.size()));
        int thumbY = top + (trackH - thumbH) * first / maxFirst;
        PlayerTheme.rrect(g, bx, thumbY, BAR_W, thumbH, 2, dragBar ? th.accentHover : th.accent);
    }

    private void drawFooter(Canvas g) {
        int fy = py + ph - FOOTER_H;
        g.fill(px + SIDE_W, fy, px + pw - 10, fy + 1, PlayerTheme.argb(0xFFFFFF, 16));

        int dw = font.width(T.get("btn.done")) + 24;
        doneBtn[0] = px + pw - 10 - dw; doneBtn[1] = fy + 5; doneBtn[2] = dw; doneBtn[3] = 20;
        boolean hover = in(doneBtn, mouseX, mouseY);
        PlayerTheme.rrect(g, doneBtn[0], doneBtn[1], dw, 20, Math.min(th.radius + 1, 10), hover ? th.accentHover : th.accent);
        g.text(font, T.get("btn.done"), doneBtn[0] + 12, doneBtn[1] + 6, PlayerTheme.TEXT_MAIN);

        String text = T.get("hint.default");
        for (Row r : shown) {
            if (r.inside(mouseX, mouseY) && r.hint() != null) { text = r.hint(); break; }
        }
        int hintX = px + SIDE_W + PAD, hintW = doneBtn[0] - hintX - 8;
        List<FormattedCharSequence> lines = font.split(Component.literal(text), hintW);
        for (int i = 0; i < Math.min(2, lines.size()); i++)
            g.text(font, lines.get(i), hintX, fy + 5 + i * (font.lineHeight + 1), PlayerTheme.TEXT_DIM);
    }

    @Override
    public void removed() {
        OPEN = false;   // при переходе в редактор его init() включит флаг снова
        cfg().save();
        pcfg().save();
        super.removed();
    }

    @Override
    public void onClose() {
        Game.open(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
