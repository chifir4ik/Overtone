package dev.musicplayer.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.musicplayer.Env;
import dev.musicplayer.MusicPlayerMod;
import dev.musicplayer.model.LyricLine;
import dev.musicplayer.model.Track;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Найденные тексты лежат на диске: повторный трек не ищется заново, а LRCLIB не режет нас за лишние запросы.
 * «Не нашлось» помним только в памяти и недолго: текст могут выложить позже.
 */
final class LyricsCache {
    private static final long DAY_MS = 24L * 3600_000;
    /** Обычный текст храним меньше: со временем для песни может появиться версия с таймингами. */
    private static final long SYNCED_TTL_MS = 30 * DAY_MS, PLAIN_TTL_MS = 3 * DAY_MS;
    private static final long MISSING_TTL_MS = 3600_000;
    private static final int MAX_FILES = 3000;
    private static final Map<String, Long> MISSING = new ConcurrentHashMap<>();
    private static volatile int writes;

    private LyricsCache() {}

    private static Path dir() { return Env.configDir().resolve("musicplayer-cache").resolve("lyrics"); }

    private static String key(Track t) { return (t.provider() == null ? "yandex" : t.provider()) + ":" + t.id(); }

    private static Path file(Track t) { return dir().resolve(key(t).replaceAll("[^A-Za-z0-9._-]", "_") + ".json"); }

    static boolean missing(Track t) {
        Long at = MISSING.get(key(t));
        return at != null && System.currentTimeMillis() - at < MISSING_TTL_MS;
    }

    static void markMissing(Track t) { MISSING.put(key(t), System.currentTimeMillis()); }

    /** null — в кэше нет или запись устарела. */
    static List<LyricLine> get(Track t) {
        Path f = file(t);
        if (!Files.isRegularFile(f)) return null;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            boolean synced = o.get("synced").getAsBoolean();
            if (System.currentTimeMillis() - o.get("at").getAsLong() > (synced ? SYNCED_TTL_MS : PLAIN_TTL_MS)) return null;
            List<LyricLine> lines = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("lines")) {
                JsonObject l = e.getAsJsonObject();
                long time = synced ? l.get("t").getAsLong() : LyricLine.UNSYNCED;
                List<LyricLine.Word> words = null;
                if (l.has("w")) {
                    words = new ArrayList<>();
                    for (JsonElement we : l.getAsJsonArray("w")) {
                        JsonArray w = we.getAsJsonArray();
                        words.add(new LyricLine.Word(w.get(0).getAsLong(), w.get(1).getAsString()));
                    }
                }
                lines.add(new LyricLine(time, l.get("s").getAsString(), words));
            }
            return lines.isEmpty() ? null : lines;
        } catch (IOException | RuntimeException e) {
            MusicPlayerMod.LOGGER.debug("Lyrics cache entry unreadable: {}", e.getMessage());
            return null;
        }
    }

    static void put(Track t, List<LyricLine> lines) {
        if (lines.isEmpty()) return;
        MISSING.remove(key(t));
        boolean synced = lines.get(0).timeMs() != LyricLine.UNSYNCED;
        JsonObject o = new JsonObject();
        o.addProperty("at", System.currentTimeMillis());
        o.addProperty("synced", synced);
        JsonArray arr = new JsonArray();
        for (LyricLine l : lines) {
            JsonObject j = new JsonObject();
            if (synced) j.addProperty("t", l.timeMs());
            j.addProperty("s", l.text());
            if (l.hasWordTimings()) {
                JsonArray w = new JsonArray();
                for (LyricLine.Word word : l.words()) {
                    JsonArray p = new JsonArray();
                    p.add(word.timeMs());
                    p.add(word.text());
                    w.add(p);
                }
                j.add("w", w);
            }
            arr.add(j);
        }
        o.add("lines", arr);
        try {
            Files.createDirectories(dir());
            Path f = file(t), tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            MusicPlayerMod.LOGGER.debug("Lyrics cache write failed: {}", e.getMessage());
            return;
        }
        if (++writes % 50 == 0) trim();
    }

    private static void trim() {
        File[] files = dir().toFile().listFiles((d, n) -> n.endsWith(".json"));
        if (files == null || files.length <= MAX_FILES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - MAX_FILES; i++) files[i].delete();
    }
}
