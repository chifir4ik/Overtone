package dev.musicplayer.config;

import dev.musicplayer.Env;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.musicplayer.util.T;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Настройки HUD: config/musicplayer-hud.json. Отсутствующие в файле поля получают значения по умолчанию. */
public final class HudConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static HudConfig instance;

    public enum Layout { CARD, CLASSIC, BAR, COMPACT, HOTBAR, VANILLA }

    public enum Anchor {
        TOP_LEFT(-1, -1), TOP_CENTER(0, -1), TOP_RIGHT(1, -1),
        MID_LEFT(-1, 0), CENTER(0, 0), MID_RIGHT(1, 0),
        BOTTOM_LEFT(-1, 1), BOTTOM_CENTER(0, 1), BOTTOM_RIGHT(1, 1);
        /** -1 слева, 0 центр, 1 справа */
        public final int h;
        /** -1 сверху, 0 середина, 1 снизу */
        public final int v;
        Anchor(int h, int v) { this.h = h; this.v = v; }

        public static Anchor of(int h, int v) {
            for (Anchor a : values()) if (a.h == h && a.v == v) return a;
            return TOP_LEFT;
        }
    }

    public enum WorldMode { FOLLOW, FIXED }

    /** SCREEN — текст всегда перед взглядом; WORLD — следует за игроком, но не поворачивается вместе с головой. */
    public enum FollowStyle { SCREEN, WORLD }

    /** LINE — слова строки собираются в ряд; SCATTER — каждое слово появляется отдельно, в случайном месте рядом. */
    public enum WorldLayout { LINE, SCATTER }

    public enum VolumeStyle { BAR, COMPACT, VERTICAL }

    public enum VizColor { ACCENT, GRADIENT, RAINBOW, WHITE }
    public enum VizDirection { AUTO, UP, DOWN }
    /** AUTO — звук мода, а для Spotify (играет в своём приложении) — звук системы. */
    public enum VizSource { AUTO, MOD, SYSTEM }
    public enum VizStyle { BARS, BLOCKS, MIRROR, WAVE }

    /** Анимация появления слов песни в мире. */
    public enum WorldAnim { FLY, RISE, DROP, POP, WAVE, TYPE, SPIN, GLITCH }
    public enum WorldColor { CLASSIC, ACCENT, GRADIENT, RAINBOW, WHITE }
    /** Как буквы уходят: падают на блоки, рассыпаются, улетают, сдуваются ветром и т. д. */
    public enum WorldExit { FADE, FALL, SHATTER, FLOAT, WIND, SHRINK, SINK }
    public enum WorldGlow { OFF, SUNG, ALL }
    /** Что анимируется по очереди: слова целиком или отдельные буквы. */
    public enum WorldUnit { WORDS, LETTERS }

    public enum ShowMode {
        ALWAYS(-1), SECONDS_15(15), SECONDS_5(5);
        public final int seconds;
        ShowMode(int s) { seconds = s; }
        public boolean timed() { return seconds > 0; }
    }

    public enum LyricsMode { OFF, SUBTITLE, KARAOKE }

    public enum Accent {
        RASPBERRY(0xFC3F6B), YELLOW(0xFFDB4D), GREEN(0x1DB954), BLUE(0x4FA8FF),
        PURPLE(0xA06BFF), ORANGE(0xFF8A3D), WHITE(0xFFFFFF);
        public final int rgb;
        Accent(int rgb) { this.rgb = rgb; }
    }

    /** Название значения перечисления на языке игры: ключ вида overtone.opt.anchor.top_left. */
    public static String label(Enum<?> e) {
        return T.get("opt." + e.getDeclaringClass().getSimpleName().toLowerCase() + "." + e.name().toLowerCase());
    }

    public static <E extends Enum<E>> E prev(E e) {
        E[] all = e.getDeclaringClass().getEnumConstants();
        return all[(e.ordinal() + all.length - 1) % all.length];
    }

    public static <E extends Enum<E>> E next(E e) {
        E[] all = e.getDeclaringClass().getEnumConstants();
        return all[(e.ordinal() + 1) % all.length];
    }

    public int accentRgb() { return customAccent >= 0 ? customAccent : accent.rgb; }

    public boolean master = true;
    public boolean enabled = true;
    public Layout layout = Layout.CARD;
    public Anchor anchor = Anchor.TOP_LEFT;
    public int offsetX = 6;
    public int offsetY = 6;
    public float scale = 1.0f;
    public int backgroundOpacity = 88;
    public Accent accent = Accent.RASPBERRY;
    /** Свой цвет акцента (RGB). -1 — используется {@link #accent}. */
    public int customAccent = -1;
    public ShowMode showMode = ShowMode.SECONDS_15;
    public boolean showOnSongStart = true;
    public boolean showOnSongEnd = true;
    public boolean showCover = true;
    public boolean showTimestamps = true;
    public boolean showWaveform = true;
    public boolean cardLyrics = true;
    public boolean hideWithGui = false;
    /** Пока зажат Tab (список игроков), виджет, текст и громкость плавно гаснут. */
    public boolean hideOnTab = false;

    public boolean volumeEnabled = true;
    public VolumeStyle volumeStyle = VolumeStyle.BAR;
    public Anchor volumeAnchor = Anchor.TOP_CENTER;
    public int volumeOffsetX = 0;
    public int volumeOffsetY = 14;
    public float volumeScale = 1.0f;
    public int volumeBackground = 88;
    public int volumeSeconds = 2;
    public boolean volumePercent = true;

    /** Слова песни летят в мире, когда их поют. Включено — обычный HUD-текст для треков с таймкодами не рисуется. */
    public boolean worldLyrics = false;
    public WorldMode worldMode = WorldMode.FOLLOW;
    /** SCREEN — текст всегда перед взглядом; WORLD — следует за игроком, но не поворачивается вместе с головой. */
    public FollowStyle worldFollowStyle = FollowStyle.SCREEN;
    /** LINE — слова строки собираются в ряд; SCATTER — каждое слово появляется отдельно, в случайном месте рядом. */
    public WorldLayout worldLayout = WorldLayout.LINE;
    public float worldScale = 1.0f;
    /** Расстояние от игрока до текста, блоков. */
    public float worldDistance = 9.0f;
    /** Высота текста относительно глаз, блоков. */
    public float worldHeight = 0.0f;
    /** Половина горизонтальной зоны, где появляются слова, градусов. Для узких и растянутых экранов её стоит уменьшить. */
    public float worldSpread = 57f;
    public boolean worldSeeThrough = true;
    public boolean worldShadow = true;
    public WorldAnim worldAnim = WorldAnim.RISE;
    public WorldExit worldExit = WorldExit.FALL;
    /** Сколько секунд слова остаются в мире после того, как их спели. */
    public int worldLinger = 4;
    public WorldUnit worldUnit = WorldUnit.LETTERS;
    /** Задержка между буквами, мс. */
    public int worldLetterMs = 35;
    /** Скорость анимации, %. */
    public int worldAnimSpeed = 100;
    /** Сила отскока букв при падении, %. */
    public int worldBounce = 100;
    public WorldColor worldColor = WorldColor.CLASSIC;
    public Accent worldAccent = Accent.RASPBERRY;
    public Accent worldAccent2 = Accent.BLUE;
    public WorldGlow worldGlow = WorldGlow.SUNG;
    public int worldGlowStrength = 60;
    public int worldGlowSize = 100;

    public LyricsMode lyricsMode = LyricsMode.SUBTITLE;
    public Anchor lyricsAnchor = Anchor.BOTTOM_CENTER;
    public int lyricsOffsetX = 0;
    public int lyricsOffsetY = 76;
    public float lyricsScale = 1.0f;
    public int lyricsWidth = 320;
    public int lyricsLines = 5;
    public int lyricsBackground = 40;
    public boolean lyricsAccent = true;
    public boolean lyricsShadow = true;
    /** Ручной сдвиг текста, мс: положительный — строки появляются позже, отрицательный — раньше. */
    public int lyricsShiftMs = 0;
    public boolean lyricsWordKaraoke = true;

    // Визуализатор (в стиле cava)
    public boolean vizEnabled = true;
    public VizSource vizSource = VizSource.AUTO;
    public VizStyle vizStyle = VizStyle.BARS;
    public boolean vizStereo = true;
    public VizDirection vizDirection = VizDirection.AUTO;
    public VizColor vizColor = VizColor.GRADIENT;
    public int vizBars = 32;
    public int vizBarWidth = 3;
    public int vizGap = 1;
    public int vizHeight = 36;
    public int vizSensitivity = 100;
    public boolean vizAutoSens = true;
    public int vizSmoothing = 60;
    public boolean vizPeaks = true;
    public boolean vizHideIdle = true;
    public int vizBackground = 0;
    public Anchor vizAnchor = Anchor.BOTTOM_RIGHT;
    public int vizOffsetX = 6;
    public int vizOffsetY = 6;
    public float vizScale = 1.0f;

    public static HudConfig get() {
        if (instance == null) instance = load();
        return instance;
    }

    public static void resetToDefaults() {
        instance = new HudConfig();
        instance.save();
    }

    /** Значение по умолчанию любого поля: правый клик по настройке возвращает его. */
    public static final HudConfig DEFAULTS = new HudConfig();

    private static Path path() { return Env.configDir().resolve("musicplayer-hud.json"); }

    private static HudConfig load() {
        HudConfig c = null;
        try {
            Path p = path();
            if (Files.exists(p)) c = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), HudConfig.class);
        } catch (Exception ignored) {}
        if (c == null) c = new HudConfig();
        c.sanitize();
        return c;
    }

    private void sanitize() {
        HudConfig d = new HudConfig();
        if (layout == null) layout = d.layout;
        if (anchor == null) anchor = d.anchor;
        if (accent == null) accent = d.accent;
        if (customAccent < 0 || customAccent > 0xFFFFFF) customAccent = -1;
        if (showMode == null) showMode = d.showMode;
        if (lyricsMode == null) lyricsMode = d.lyricsMode;
        if (lyricsAnchor == null) lyricsAnchor = d.lyricsAnchor;
        if (volumeStyle == null) volumeStyle = d.volumeStyle;
        if (volumeAnchor == null) volumeAnchor = d.volumeAnchor;
        if (worldMode == null) worldMode = d.worldMode;
        if (worldFollowStyle == null) worldFollowStyle = d.worldFollowStyle;
        if (worldLayout == null) worldLayout = d.worldLayout;
        if (worldAnim == null) worldAnim = d.worldAnim;
        if (worldExit == null) worldExit = d.worldExit;
        if (worldUnit == null) worldUnit = d.worldUnit;
        if (worldColor == null) worldColor = d.worldColor;
        if (worldAccent == null) worldAccent = d.worldAccent;
        if (worldAccent2 == null) worldAccent2 = d.worldAccent2;
        if (worldGlow == null) worldGlow = d.worldGlow;
        if (vizSource == null) vizSource = d.vizSource;
        if (vizStyle == null) vizStyle = d.vizStyle;
        if (vizDirection == null) vizDirection = d.vizDirection;
        if (vizColor == null) vizColor = d.vizColor;
        if (vizAnchor == null) vizAnchor = d.vizAnchor;
        worldLinger = Math.max(1, Math.min(20, worldLinger));
        worldLetterMs = Math.max(5, Math.min(150, worldLetterMs));
        worldAnimSpeed = Math.max(25, Math.min(300, worldAnimSpeed));
        worldBounce = Math.max(0, Math.min(300, worldBounce));
        worldGlowStrength = Math.max(0, Math.min(100, worldGlowStrength));
        worldGlowSize = Math.max(50, Math.min(250, worldGlowSize));
        vizBars = Math.max(4, Math.min(96, vizBars));
        vizBarWidth = Math.max(1, Math.min(10, vizBarWidth));
        vizGap = Math.max(0, Math.min(6, vizGap));
        vizHeight = Math.max(8, Math.min(160, vizHeight));
        vizSensitivity = Math.max(10, Math.min(400, vizSensitivity));
        vizSmoothing = Math.max(0, Math.min(95, vizSmoothing));
        vizBackground = Math.max(0, Math.min(100, vizBackground));
        vizScale = Math.max(0.5f, Math.min(2.0f, vizScale));
        vizOffsetX = Math.max(-600, Math.min(600, vizOffsetX));
        vizOffsetY = Math.max(-600, Math.min(600, vizOffsetY));
        worldScale = Math.max(0.5f, Math.min(2.5f, worldScale));
        worldDistance = Math.max(3f, Math.min(30f, worldDistance));
        worldHeight = Math.max(-1.5f, Math.min(3f, worldHeight));
        worldSpread = Math.max(15f, Math.min(90f, worldSpread));
        volumeScale = Math.max(0.5f, Math.min(2.0f, volumeScale));
        volumeBackground = Math.max(0, Math.min(100, volumeBackground));
        volumeSeconds = Math.max(1, Math.min(10, volumeSeconds));
        scale = Math.max(0.5f, Math.min(2.0f, scale));
        lyricsScale = Math.max(0.5f, Math.min(2.0f, lyricsScale));
        backgroundOpacity = Math.max(0, Math.min(100, backgroundOpacity));
        lyricsBackground = Math.max(0, Math.min(100, lyricsBackground));
        lyricsWidth = Math.max(120, Math.min(600, lyricsWidth));
        lyricsShiftMs = Math.max(-5000, Math.min(5000, lyricsShiftMs));
        lyricsLines = Math.max(3, Math.min(9, lyricsLines));
        offsetX = Math.max(-600, Math.min(600, offsetX));
        offsetY = Math.max(-600, Math.min(600, offsetY));
    }

    public void save() {
        try {
            Path p = path();
            Files.createDirectories(p.getParent());
            Files.writeString(p, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }
}
