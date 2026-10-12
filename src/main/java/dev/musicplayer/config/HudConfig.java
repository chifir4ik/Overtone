package dev.musicplayer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Настройки HUD: config/musicplayer-hud.json. Отсутствующие в файле поля получают значения по умолчанию. */
public final class HudConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static HudConfig instance;

    public enum Layout {
        CARD("Карточка"), CLASSIC("Классика"), BAR("С прогресс-баром"),
        COMPACT("Компактный"), HOTBAR("Над хотбаром"), VANILLA("Как подсказка");
        public final String label;
        Layout(String l) { label = l; }
    }

    public enum Anchor {
        TOP_LEFT("Сверху слева", -1, -1), TOP_CENTER("Сверху по центру", 0, -1), TOP_RIGHT("Сверху справа", 1, -1),
        MID_LEFT("Слева по центру", -1, 0), MID_RIGHT("Справа по центру", 1, 0),
        BOTTOM_LEFT("Снизу слева", -1, 1), BOTTOM_CENTER("Снизу по центру", 0, 1), BOTTOM_RIGHT("Снизу справа", 1, 1),
        CENTER("По центру", 0, 0);
        public final String label;
        /** -1 слева, 0 центр, 1 справа */
        public final int h;
        /** -1 сверху, 0 середина, 1 снизу */
        public final int v;
        Anchor(String l, int h, int v) { label = l; this.h = h; this.v = v; }

        public static Anchor of(int h, int v) {
            for (Anchor a : values()) if (a.h == h && a.v == v) return a;
            return TOP_LEFT;
        }
    }

    public enum WorldMode {
        FOLLOW("Рядом с игроком"), FIXED("На месте строки");
        public final String label;
        WorldMode(String l) { label = l; }
    }

    public enum FollowStyle {
        SCREEN("Висит перед взглядом"), WORLD("Не поворачивается");
        public final String label;
        FollowStyle(String l) { label = l; }
    }

    public enum WorldLayout {
        LINE("В строку"), SCATTER("Вокруг игрока");
        public final String label;
        WorldLayout(String l) { label = l; }
    }

    public enum VolumeStyle {
        BAR("Полоса"), COMPACT("Компактный"), VERTICAL("Вертикальный");
        public final String label;
        VolumeStyle(String l) { label = l; }
    }

    public enum ShowMode {
        ALWAYS("Всегда", -1), SECONDS_15("15 секунд", 15), SECONDS_5("5 секунд", 5);
        public final String label;
        public final int seconds;
        ShowMode(String l, int s) { label = l; seconds = s; }
        public boolean timed() { return seconds > 0; }
    }

    public enum LyricsMode {
        OFF("Выкл"), SUBTITLE("Субтитры"), KARAOKE("Караоке");
        public final String label;
        LyricsMode(String l) { label = l; }
    }

    public enum Accent {
        RASPBERRY("Малиновый", 0xFC3F6B), YELLOW("Жёлтый", 0xFFDB4D), GREEN("Зелёный", 0x1DB954),
        BLUE("Голубой", 0x4FA8FF), PURPLE("Фиолетовый", 0xA06BFF), ORANGE("Оранжевый", 0xFF8A3D),
        WHITE("Белый", 0xFFFFFF);
        public final String label;
        public final int rgb;
        Accent(String l, int rgb) { label = l; this.rgb = rgb; }
    }

    public static <E extends Enum<E>> E next(E e) {
        E[] all = e.getDeclaringClass().getEnumConstants();
        return all[(e.ordinal() + 1) % all.length];
    }

    public boolean master = true;
    public boolean enabled = true;
    public Layout layout = Layout.CARD;
    public Anchor anchor = Anchor.TOP_LEFT;
    public int offsetX = 6;
    public int offsetY = 6;
    public float scale = 1.0f;
    public int backgroundOpacity = 88;
    public Accent accent = Accent.RASPBERRY;
    public ShowMode showMode = ShowMode.ALWAYS;
    public boolean showOnSongStart = true;
    public boolean showOnSongEnd = true;
    public boolean showCover = true;
    public boolean showTimestamps = true;
    public boolean showWaveform = true;
    public boolean cardLyrics = true;
    public boolean hideWithGui = false;

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
    public float worldDistance = 4.0f;
    /** Высота текста относительно глаз, блоков. */
    public float worldHeight = 0.0f;
    public boolean worldSeeThrough = true;
    public boolean worldShadow = true;

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

    public static HudConfig get() {
        if (instance == null) instance = load();
        return instance;
    }

    public static void resetToDefaults() {
        instance = new HudConfig();
        instance.save();
    }

    private static Path path() { return FabricLoader.getInstance().getConfigDir().resolve("musicplayer-hud.json"); }

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
        if (showMode == null) showMode = d.showMode;
        if (lyricsMode == null) lyricsMode = d.lyricsMode;
        if (lyricsAnchor == null) lyricsAnchor = d.lyricsAnchor;
        if (volumeStyle == null) volumeStyle = d.volumeStyle;
        if (volumeAnchor == null) volumeAnchor = d.volumeAnchor;
        if (worldMode == null) worldMode = d.worldMode;
        if (worldFollowStyle == null) worldFollowStyle = d.worldFollowStyle;
        if (worldLayout == null) worldLayout = d.worldLayout;
        worldScale = Math.max(0.5f, Math.min(2.5f, worldScale));
        worldDistance = Math.max(2f, Math.min(20f, worldDistance));
        worldHeight = Math.max(-1.5f, Math.min(3f, worldHeight));
        volumeScale = Math.max(0.5f, Math.min(2.0f, volumeScale));
        volumeBackground = Math.max(0, Math.min(100, volumeBackground));
        volumeSeconds = Math.max(1, Math.min(10, volumeSeconds));
        scale = Math.max(0.5f, Math.min(2.0f, scale));
        lyricsScale = Math.max(0.5f, Math.min(2.0f, lyricsScale));
        backgroundOpacity = Math.max(0, Math.min(100, backgroundOpacity));
        lyricsBackground = Math.max(0, Math.min(100, lyricsBackground));
        lyricsWidth = Math.max(120, Math.min(600, lyricsWidth));
        lyricsShiftMs = Math.max(-5000, Math.min(5000, lyricsShiftMs));
        lyricsLines = lyricsLines < 3 ? 3 : Math.min(9, lyricsLines);
    }

    public void save() {
        try {
            Path p = path();
            Files.createDirectories(p.getParent());
            Files.writeString(p, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }
}
