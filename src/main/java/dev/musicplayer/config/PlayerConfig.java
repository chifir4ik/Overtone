package dev.musicplayer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Настройки окна плеера и горячих клавиш: config/musicplayer-player.json. */
public final class PlayerConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static PlayerConfig instance;

    public boolean syncAccent = true;
    public HudConfig.Accent accent = HudConfig.Accent.RASPBERRY;
    /** 0..3 — сколько акцента подмешано в цвет фона (0 / 5 / 10 / 15 %). */
    public int tint = 1;
    /** Непрозрачность фона окон плеера, настроек и обучения, %. */
    public int windowOpacity = 100;
    /** Затемнение игры за окном плеера, %. */
    public int dim = 45;
    public int cornerRadius = 4;
    /** Размер окна, % от базового (560 x 330); на маленьких экранах окно всё равно не выходит за край. */
    public int windowScale = 100;
    /** Сторона обложки в окне, px. */
    public int coverPx = 80;
    public boolean showCover = true;
    public boolean showLyrics = true;
    public boolean accentText = true;

    /** Искать тексты в LRCLIB (название, исполнитель и длительность трека уходят на lrclib.net). */
    public boolean onlineLyrics = true;

    public boolean prefetch = true;
    public int prefetchCount = 4;
    public boolean diskCache = true;

    /** Громкость музыки = ползунок мода × «Основная» × «Музыка» из настроек звука Minecraft. */
    public boolean useGameVolume = true;
    public boolean duckWhenInactive = false;

    /**
     * УСТАРЕЛО: клавиши раньше хранились здесь. Теперь источник правды — KeyMapping (options.txt);
     * поле читается один раз для переноса (Hotkeys.migrateLegacy) и очищается.
     */
    public int keysVersion;
    public Map<String, Integer> keys = new LinkedHashMap<>();

    public int accentRgb() { return (syncAccent ? HudConfig.get().accent : accent).rgb; }

    public static PlayerConfig get() {
        if (instance == null) instance = load();
        return instance;
    }

    public static void resetToDefaults() {
        instance = new PlayerConfig();
        instance.save();
    }

    public static final PlayerConfig DEFAULTS = new PlayerConfig();

    private static Path path() { return FabricLoader.getInstance().getConfigDir().resolve("musicplayer-player.json"); }

    private static PlayerConfig load() {
        PlayerConfig c = null;
        try {
            Path p = path();
            if (Files.exists(p)) c = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), PlayerConfig.class);
        } catch (Exception ignored) {}
        if (c == null) c = new PlayerConfig();
        c.sanitize();
        return c;
    }

    private void sanitize() {
        PlayerConfig d = new PlayerConfig();
        if (accent == null) accent = d.accent;
        if (keys == null) keys = new LinkedHashMap<>();
        windowScale = Math.max(70, Math.min(150, windowScale));
        coverPx = Math.max(40, Math.min(140, coverPx));
        tint = Math.max(0, Math.min(3, tint));
        dim = Math.max(0, Math.min(100, dim));
        windowOpacity = Math.max(20, Math.min(100, windowOpacity));
        prefetchCount = Math.max(1, Math.min(8, prefetchCount));
        cornerRadius = Math.max(0, Math.min(8, cornerRadius));
    }

    public void save() {
        try {
            Path p = path();
            Files.createDirectories(p.getParent());
            Files.writeString(p, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }
}
