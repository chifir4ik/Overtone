package dev.musicplayer.lyrics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.musicplayer.Env;
import dev.musicplayer.MusicPlayerMod;
import dev.musicplayer.config.HudConfig;
import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.model.LyricLine;
import dev.musicplayer.model.Track;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Настройки текста для отдельных песен: свой сдвиг и свой файл текста. Всё лежит в
 * config/musicplayer-track-lyrics.json (по записи на песню, с названием и исполнителем, чтобы файл можно было
 * читать и править руками), а сами файлы текста — в config/musicplayer-lyrics/.
 */
public final class TrackLyrics {
    public static final List<String> EXTENSIONS = List.of("lrc", "ttml", "txt");
    private static final int MAX_SHIFT_MS = 10_000;
    private static final long MAX_FILE_BYTES = 2 << 20;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final class Entry {
        String title = "", artist = "", platform = "";
        int shiftMs;
        String file;
    }

    private static Map<String, Entry> entries;

    private TrackLyrics() {}

    private static Path index() { return Env.configDir().resolve("musicplayer-track-lyrics.json"); }
    private static Path folder() { return Env.configDir().resolve("musicplayer-lyrics"); }

    private static String key(Track t) { return (t.provider() == null ? "yandex" : t.provider()) + ":" + t.id(); }

    private static synchronized Map<String, Entry> all() {
        if (entries == null) {
            entries = new LinkedHashMap<>();
            try {
                if (Files.exists(index())) {
                    Map<String, Entry> m = GSON.fromJson(Files.readString(index(), StandardCharsets.UTF_8),
                            new TypeToken<LinkedHashMap<String, Entry>>() {}.getType());
                    if (m != null) entries.putAll(m);
                }
            } catch (Exception e) {
                MusicPlayerMod.LOGGER.warn("Could not read per-track lyrics settings: {}", e.getMessage());
            }
        }
        return entries;
    }

    private static synchronized Entry entry(Track t, boolean create) {
        Entry e = all().get(key(t));
        if (e == null && create) {
            e = new Entry();
            all().put(key(t), e);
        }
        if (e != null && create) {
            e.title = t.title() == null ? "" : t.title();
            e.artist = t.artist() == null ? "" : t.artist();
            e.platform = t.provider() == null ? "yandex" : t.provider();
        }
        return e;
    }

    private static synchronized void save(Track t) {
        Entry e = all().get(key(t));
        if (e != null && e.shiftMs == 0 && e.file == null) all().remove(key(t));
        try {
            Files.createDirectories(index().getParent());
            Path tmp = index().resolveSibling("musicplayer-track-lyrics.json.tmp");
            Files.writeString(tmp, GSON.toJson(all()), StandardCharsets.UTF_8);
            Files.move(tmp, index(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            MusicPlayerMod.LOGGER.warn("Could not save per-track lyrics settings: {}", ex.getMessage());
        }
    }

    public static int shiftMs(Track t) {
        if (t == null || t.id() == null) return 0;
        Entry e = entry(t, false);
        return e == null ? 0 : e.shiftMs;
    }

    /** Общий сдвиг из настроек плюс сдвиг играющей песни. */
    public static int totalShift() {
        return HudConfig.get().lyricsShiftMs + shiftMs(PlayerController.I.current);
    }

    public static void addShift(Track t, int deltaMs) {
        if (t == null || t.id() == null) return;
        Entry e = entry(t, true);
        e.shiftMs = Math.max(-MAX_SHIFT_MS, Math.min(MAX_SHIFT_MS, e.shiftMs + deltaMs));
        save(t);
    }

    public static void resetShift(Track t) {
        Entry e = t == null ? null : entry(t, false);
        if (e == null) return;
        e.shiftMs = 0;
        save(t);
    }

    public static boolean hasCustom(Track t) {
        Entry e = t == null || t.id() == null ? null : entry(t, false);
        return e != null && e.file != null && Files.isRegularFile(folder().resolve(e.file));
    }

    /** Свой текст песни или пустой список, если его нет. */
    public static List<LyricLine> custom(Track t) {
        if (!hasCustom(t)) return List.of();
        Path file = folder().resolve(entry(t, false).file);
        try {
            return parse(file);
        } catch (IOException e) {
            MusicPlayerMod.LOGGER.warn("Could not read custom lyrics {}: {}", file.getFileName(), e.getMessage());
            return List.of();
        }
    }

    /** Копирует файл к себе и запоминает его для этой песни. Бросает исключение, если в файле нет текста. */
    public static List<LyricLine> importFile(Track t, Path source) throws IOException {
        if (Files.size(source) > MAX_FILE_BYTES) throw new IOException("file is too large");
        List<LyricLine> lines = parse(source);
        if (lines.isEmpty()) throw new IOException("no lyrics in " + source.getFileName());

        String ext = extension(source);
        String name = key(t).replaceAll("[^A-Za-z0-9_.-]", "_") + "." + ext;
        Files.createDirectories(folder());
        Entry old = entry(t, false);
        if (old != null && old.file != null && !old.file.equals(name)) Files.deleteIfExists(folder().resolve(old.file));
        Files.copy(source, folder().resolve(name), StandardCopyOption.REPLACE_EXISTING);
        entry(t, true).file = name;
        save(t);
        return lines;
    }

    public static void removeCustom(Track t) {
        Entry e = t == null ? null : entry(t, false);
        if (e == null || e.file == null) return;
        try {
            Files.deleteIfExists(folder().resolve(e.file));
        } catch (IOException ignored) {
        }
        e.file = null;
        save(t);
    }

    public static boolean isLyricsFile(Path p) { return EXTENSIONS.contains(extension(p)); }

    private static List<LyricLine> parse(Path file) throws IOException {
        String text = decode(Files.readAllBytes(file));
        return switch (extension(file)) {
            case "ttml" -> TtmlParser.parse(text);
            case "lrc" -> {
                List<LyricLine> timed = LrcParser.parse(text);
                yield timed.isEmpty() ? LrcParser.plain(text) : timed;
            }
            default -> LrcParser.plain(text);
        };
    }

    private static String extension(Path p) {
        String n = p.getFileName() == null ? "" : p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Тексты часто сохраняют в windows-1251: если это не UTF-8, читаем как её. */
    private static String decode(byte[] raw) {
        try {
            String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
            return s.startsWith("﻿") ? s.substring(1) : s;
        } catch (CharacterCodingException e) {
            return new String(raw, Charset.forName("windows-1251"));
        }
    }
}
