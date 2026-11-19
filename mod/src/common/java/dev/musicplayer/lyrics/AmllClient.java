package dev.musicplayer.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.musicplayer.Env;
import dev.musicplayer.MusicPlayerMod;
import dev.musicplayer.lyrics.TrackMatch.Level;
import dev.musicplayer.model.LyricLine;
import dev.musicplayer.model.Track;
import dev.musicplayer.net.SafeHttp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * База пословных текстов AMLL TTML DB (github.com/amll-dev/amll-ttml-db). Поиска по сети у неё нет:
 * раз в несколько дней качается список песен (около 2 МБ), совпадение ищем сами.
 */
public final class AmllClient {
    private static final String BASE = "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/refs/heads/main/";
    private static final SafeHttp HTTP = new SafeHttp(Set.of("raw.githubusercontent.com"));
    private static final Map<String, String> HEADERS = Map.of("User-Agent", "Overtone (Minecraft mod)");
    private static final Pattern FILE = Pattern.compile("[\\w.-]+\\.ttml");
    private static final long INDEX_TTL_MS = Duration.ofDays(3).toMillis();
    private static final long RETRY_AFTER_FAIL_MS = Duration.ofMinutes(10).toMillis();
    private static final int MAX_VERSIONS = 3;
    private static final int WAIT_SECONDS = 4;

    private record Entry(List<String> titles, List<String> artists, String file) {}

    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "musicplayer-amll-index");
        t.setDaemon(true);
        return t;
    });

    private static volatile List<Entry> index;
    private static volatile long indexedAt, failedAt;
    private static CompletableFuture<List<Entry>> loading;

    private AmllClient() {}

    public static CompletableFuture<List<LyricLine>> fetch(Track t) {
        return LyricsIo.run(() -> lookup(t));
    }

    private static List<LyricLine> lookup(Track t) throws Exception {
        List<Entry> all;
        try {
            all = indexNow().get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return List.of();   // индекс докачается в фоне, пригодится следующим трекам
        }

        List<String> wanted = new ArrayList<>();
        for (TrackMatch.Query q : TrackMatch.queries(t)) wanted.add(TrackMatch.norm(TrackMatch.cleanTitle(q.title())));
        List<Entry> hits = new ArrayList<>();
        for (Entry e : all) if (matches(e, t, wanted)) hits.add(e);

        // версий у песни может быть несколько, свежие файлы лежат в конце индекса
        for (int i = hits.size() - 1, tried = 0; i >= 0 && tried < MAX_VERSIONS; i--, tried++) {
            String file = hits.get(i).file();
            if (!FILE.matcher(file).matches()) continue;
            try {
                List<LyricLine> lines = TtmlParser.parse(HTTP.get(BASE + "raw-lyrics/" + file, HEADERS));
                if (!lines.isEmpty() && fitsTrack(lines, t)) return lines;
            } catch (IOException e) {
                // файл мог исчезнуть из репозитория, пробуем соседнюю версию
            }
        }
        return List.of();
    }

    /** Текст не заканчивается позже трека и обычно доходит хотя бы до его середины. */
    private static boolean fitsTrack(List<LyricLine> lines, Track t) {
        if (t.durationMs() <= 0) return true;
        long last = lines.get(lines.size() - 1).timeMs();
        return last <= t.durationMs() + 3000 && last >= t.durationMs() / 2;
    }

    private static boolean matches(Entry e, Track t, List<String> wanted) {
        for (String title : e.titles()) {
            String have = TrackMatch.norm(TrackMatch.cleanTitle(title));
            if (have.isEmpty() || wanted.stream().noneMatch(w -> !w.isEmpty() && (have.contains(w) || w.contains(have)))) continue;
            for (String artist : e.artists()) if (TrackMatch.level(title, artist, t) == Level.FULL) return true;
        }
        return false;
    }

    /** Отдаёт индекс сразу, если он есть (пусть и устаревший), обновление идёт в фоне. */
    private static synchronized CompletableFuture<List<Entry>> indexNow() {
        List<Entry> current = index;
        boolean stale = current == null || System.currentTimeMillis() - indexedAt > INDEX_TTL_MS;
        if (stale && loading == null) {
            CompletableFuture<List<Entry>> f = CompletableFuture.supplyAsync(() -> {
                try {
                    return loadIndex();
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            }, LOADER);
            loading = f;
            f.whenComplete((r, e) -> {
                synchronized (AmllClient.class) { loading = null; }
            });
        }
        return current != null ? CompletableFuture.completedFuture(current) : loading;
    }

    private static List<Entry> loadIndex() throws IOException {
        Path cache = Env.configDir().resolve("musicplayer-cache").resolve("amll-index.jsonl");
        boolean fresh = Files.exists(cache) && System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis() < INDEX_TTL_MS;
        if (fresh) return publish(Files.readString(cache, StandardCharsets.UTF_8), false);

        if (System.currentTimeMillis() - failedAt < RETRY_AFTER_FAIL_MS) {
            if (index != null) return index;
            throw new IOException("AMLL index is unavailable");
        }
        try {
            String raw = HTTP.get(BASE + "metadata/raw-lyrics-index.jsonl", HEADERS);
            Files.createDirectories(cache.getParent());
            Path tmp = cache.resolveSibling(cache.getFileName() + ".tmp");
            Files.writeString(tmp, raw, StandardCharsets.UTF_8);
            Files.move(tmp, cache, StandardCopyOption.REPLACE_EXISTING);
            return publish(raw, false);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            failedAt = System.currentTimeMillis();
            MusicPlayerMod.LOGGER.warn("AMLL TTML DB index is unavailable: {}", e.getMessage());
            if (index != null) return index;
            if (!Files.exists(cache)) throw new IOException("AMLL index is unavailable", e);
            return publish(Files.readString(cache, StandardCharsets.UTF_8), true);
        }
    }

    /** Устаревший индекс из кэша берём без сети, но через десять минут пробуем обновить его снова. */
    private static List<Entry> publish(String raw, boolean outdated) {
        List<Entry> parsed = parseIndex(raw);
        index = parsed;
        indexedAt = outdated ? System.currentTimeMillis() - INDEX_TTL_MS + RETRY_AFTER_FAIL_MS : System.currentTimeMillis();
        return parsed;
    }

    private static List<Entry> parseIndex(String raw) {
        List<Entry> out = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            if (line.isBlank()) continue;
            try {
                JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                List<String> titles = new ArrayList<>(), artists = new ArrayList<>();
                for (JsonElement m : o.getAsJsonArray("metadata")) {
                    JsonArray pair = m.getAsJsonArray();
                    String key = pair.get(0).getAsString();
                    List<String> target = key.equals("musicName") ? titles : key.equals("artists") ? artists : null;
                    if (target != null) for (JsonElement v : pair.get(1).getAsJsonArray()) target.add(v.getAsString());
                }
                if (!titles.isEmpty() && !artists.isEmpty()) out.add(new Entry(titles, artists, o.get("rawLyricFile").getAsString()));
            } catch (RuntimeException ignored) {
                // битая строка не должна ронять весь индекс
            }
        }
        return out;
    }
}
