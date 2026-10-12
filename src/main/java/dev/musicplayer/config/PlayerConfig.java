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

    public enum Size {
        SMALL("Малый", 480, 280, 140), MEDIUM("Средний", 540, 320, 160), LARGE("Большой", 620, 360, 180);
        public final String label;
        public final int w, h, listW;
        Size(String l, int w, int h, int listW) { label = l; this.w = w; this.h = h; this.listW = listW; }
    }

    public enum CoverSize {
        SMALL("Малая", 56), MEDIUM("Средняя", 80), LARGE("Большая", 110);
        public final String label;
        public final int px;
        CoverSize(String l, int px) { label = l; this.px = px; }
    }

    public boolean syncAccent = true;
    public HudConfig.Accent accent = HudConfig.Accent.RASPBERRY;
    /** 0..3 — сколько акцента подмешано в цвет фона (0 / 5 / 10 / 15 %). */
    public int tint = 1;
    /** Затемнение игры за окном плеера, %. */
    public int dim = 45;
    public int cornerRadius = 4;
    public Size size = Size.MEDIUM;
    public CoverSize coverSize = CoverSize.MEDIUM;
    public boolean showCover = true;
    public boolean showLyrics = true;
    public boolean accentText = true;

    public boolean prefetch = true;
    public int prefetchCount = 4;
    public boolean diskCache = true;

    /** Громкость музыки = ползунок мода × «Основная» × «Музыка» из настроек звука Minecraft. */
    public boolean useGameVolume = false;
    public boolean duckWhenInactive = false;

    /**
     * УСТАРЕЛО: клавиши раньше хранились здесь. Теперь источник правды — KeyMapping (options.txt);
     * поле читается один раз для переноса (Hotkeys.migrateLegacy) и очищается.
     */
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
        if (size == null) size = d.size;
        if (coverSize == null) coverSize = d.coverSize;
        if (keys == null) keys = new LinkedHashMap<>();
        tint = Math.max(0, Math.min(3, tint));
        dim = Math.max(0, Math.min(100, dim));
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
