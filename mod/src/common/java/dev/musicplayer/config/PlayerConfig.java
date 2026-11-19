package dev.musicplayer.config;

import dev.musicplayer.Env;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

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
    /** Свой цвет кнопки «Моя волна»; выключено — переливающаяся палитра по умолчанию. */
    public boolean waveCustom = false;
    public HudConfig.Accent waveAccent = HudConfig.Accent.RASPBERRY;
    public HudConfig.Accent accent = HudConfig.Accent.RASPBERRY;
    /** Свой цвет акцента окна плеера (RGB). -1 — используется {@link #accent}. */
    public int customAccent = -1;
    /** 0..3 — сколько акцента подмешано в цвет фона (0 / 5 / 10 / 15 %). */
    public int tint = 1;
    /** Непрозрачность фона окон плеера, настроек и обучения, %. */
    public int windowOpacity = 100;
    /** Затемнение игры за окном плеера, %. */
    public int dim = 45;
    public int cornerRadius = 4;
    public enum WindowAnim { ZOOM, SLIDE, POP, UNFOLD, OFF }
    public WindowAnim windowAnim = WindowAnim.ZOOM;
    /** Снег в окнах: по сезону (1 декабря, 15 января), всегда или выключен. */
    public enum SnowMode { SEASON, ALWAYS, OFF }
    public SnowMode snowMode = SnowMode.SEASON;
    /** Можно ли возиться со снегом мышью: смахивать, скатывать в комья, лепить снеговиков. */
    public boolean snowPlay = true;
    /** Размер окна, % от базового (560 x 330); на маленьких экранах окно всё равно не выходит за край. */
    public int windowScale = 100;
    /** Сторона обложки в окне, px. */
    public int coverPx = 80;
    public boolean showCover = true;
    public boolean showLyrics = true;
    public boolean accentText = true;

    /** Искать тексты в LRCLIB (название, исполнитель и длительность трека уходят на lrclib.net). */
    public boolean onlineLyrics = true;

    /** Пословные тексты из AMLL TTML DB (раз в несколько дней скачивается индекс с GitHub). */
    public boolean amllLyrics = true;

    /** KuGou: неофициальный API китайского сервиса, поэтому по умолчанию выключено. */
    public boolean kugouLyrics = false;

    public boolean discordPresence = true;
    /** Значок в табе у других игроков с Overtone и возможность позвать тебя в «наушник». */
    public boolean shareVisible = true;
    /** Подсказка у прицела «зажми ... — поделиться наушником». */
    public boolean shareHint = true;

    /** Шрифт текста песен: пусто — обычный шрифт игры (его меняют ресурспаками), иначе id из каталога шрифтов. */
    public String lyricFont = "";
    public float lyricFontSize = 10f;
    public float lyricFontOversample = 4f;
    public float lyricFontShiftX = 0f;
    public float lyricFontShiftY = 0f;

    public boolean prefetch = true;
    public int prefetchCount = 4;
    public boolean diskCache = true;

    /**
     * Громкость музыки = ползунок мода × «Основная» × «Музыка» из настроек звука Minecraft.
     * По умолчанию выключено: иначе ползунки ванильной музыки приглушают плеер, а он должен играть сам по себе.
     */
    public boolean useGameVolume = false;

    /** Имя устройства вывода Java Sound. Пусто — системное по умолчанию (оно не всегда совпадает с тем, что выбрано в Minecraft). */
    public String audioDevice = "";
    public boolean duckWhenInactive = false;

    /**
     * УСТАРЕЛО: клавиши раньше хранились здесь. Теперь источник правды — KeyMapping (options.txt);
     * поле читается один раз для переноса (Hotkeys.migrateLegacy) и очищается.
     */
    public int keysVersion;

    /** Версия схемы настроек: 0 — файл от старых версий (там «Учитывать громкость игры» было включено по умолчанию). */
    public int settingsVersion;
    public Map<String, Integer> keys = new LinkedHashMap<>();

    public int accentRgb() {
        if (syncAccent) return HudConfig.get().accentRgb();
        return customAccent >= 0 ? customAccent : accent.rgb;
    }

    public static PlayerConfig get() {
        if (instance == null) instance = load();
        return instance;
    }

    public static void resetToDefaults() {
        instance = new PlayerConfig();
        instance.save();
    }

    public static final PlayerConfig DEFAULTS = new PlayerConfig();

    private static Path path() { return Env.configDir().resolve("musicplayer-player.json"); }

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
        if (waveAccent == null) waveAccent = d.waveAccent;
        if (customAccent < 0 || customAccent > 0xFFFFFF) customAccent = -1;
        if (audioDevice == null) audioDevice = "";
        if (windowAnim == null) windowAnim = d.windowAnim;
        if (snowMode == null) snowMode = SnowMode.SEASON;
        if (settingsVersion < 1) {            // одноразовый перенос: старое значение по умолчанию (true) больше не навязываем
            useGameVolume = false;
            settingsVersion = 1;
        }
        if (keys == null) keys = new LinkedHashMap<>();
        windowScale = Math.max(70, Math.min(150, windowScale));
        coverPx = Math.max(40, Math.min(140, coverPx));
        tint = Math.max(0, Math.min(3, tint));
        dim = Math.max(0, Math.min(100, dim));
        windowOpacity = Math.max(20, Math.min(100, windowOpacity));
        prefetchCount = Math.max(1, Math.min(8, prefetchCount));
        cornerRadius = Math.max(0, Math.min(16, cornerRadius));
        if (lyricFont == null) lyricFont = "";
        lyricFontSize = Math.max(4f, Math.min(32f, lyricFontSize));
        lyricFontOversample = Math.max(1f, Math.min(8f, lyricFontOversample));
        lyricFontShiftX = Math.max(-8f, Math.min(8f, lyricFontShiftX));
        lyricFontShiftY = Math.max(-8f, Math.min(8f, lyricFontShiftY));
    }

    public void save() {
        try {
            Path p = path();
            Files.createDirectories(p.getParent());
            Files.writeString(p, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    /** Идёт ли сейчас снег: режим «по сезону» включает его с 1 декабря по 15 января. */
    public boolean snowOn() {
        if (snowMode == SnowMode.OFF) return false;
        if (snowMode == SnowMode.ALWAYS) return true;
        java.time.LocalDate d = java.time.LocalDate.now();
        return d.getMonthValue() == 12 || (d.getMonthValue() == 1 && d.getDayOfMonth() <= 15);
    }
}
