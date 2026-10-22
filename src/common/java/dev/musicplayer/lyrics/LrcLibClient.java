package dev.musicplayer.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import dev.musicplayer.MusicPlayerMod;
import dev.musicplayer.lyrics.TrackMatch.Level;
import dev.musicplayer.lyrics.TrackMatch.Query;
import dev.musicplayer.model.LyricLine;
import dev.musicplayer.model.Track;
import dev.musicplayer.net.SafeHttp;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Клиент LRCLIB (https://lrclib.net). Каждый найденный вариант сверяется с треком (см. {@link TrackMatch}),
 * иначе неточный поиск подсовывает чужие тексты. Пауза включается только после нескольких сбоев подряд:
 * раньше один обрыв через VPN выключал источник на 5 минут.
 */
public final class LrcLibClient {
    private static final SafeHttp HTTP = new SafeHttp(Set.of("lrclib.net"), Duration.ofSeconds(9));
    private static final Map<String, String> HEADERS = Map.of(
            "User-Agent", "Overtone (Minecraft mod)",
            "Accept", "application/json");

    /** Допустимая разница длительностей, с: у синхронизированного текста строже, т.к. от версии зависят тайминги. */
    private static final double MAX_DIFF_SYNCED = 5, MAX_DIFF_PLAIN = 15;

    private static final int FAILS_BEFORE_PAUSE = 3;
    private static final long PAUSE_MS = 45_000;
    private static volatile int failsInRow;
    private static volatile long pausedUntil;

    private LrcLibClient() {}

    private static boolean paused() { return System.currentTimeMillis() < pausedUntil; }

    /** Не бросает исключений; пустой список — ничего подходящего не нашлось. */
    public static CompletableFuture<List<LyricLine>> fetch(Track t) {
        return LyricsIo.run(() -> lookup(t));
    }

    private static List<LyricLine> lookup(Track t) throws InterruptedException {
        List<Query> queries = TrackMatch.queries(t);
        Query main = queries.get(0);
        String clean = TrackMatch.cleanTitle(main.title());
        String artist = TrackMatch.firstArtist(main.artist());

        List<LyricLine> l = exact(t, main.title(), main.artist(), t.album());
        if (l.isEmpty()) l = exact(t, clean, artist, null);
        if (!l.isEmpty()) return l;

        Set<String> searches = new LinkedHashSet<>();
        for (Query q : queries) {
            String title = TrackMatch.cleanTitle(q.title()), by = TrackMatch.firstArtist(q.artist());
            searches.add("track_name=" + enc(q.title()) + "&artist_name=" + enc(q.artist()));
            searches.add("track_name=" + enc(title) + "&artist_name=" + enc(by));
            searches.add("q=" + enc(title + " " + by));
        }
        // исполнитель в базе мог быть записан иначе: ищем по одному названию, а совпадение решает сверка
        searches.add("track_name=" + enc(clean));

        List<LyricLine> plainFallback = List.of();
        for (String q : searches) {
            l = search(t, q);
            if (l.isEmpty()) continue;
            if (l.get(0).timeMs() != LyricLine.UNSYNCED) return l;
            if (plainFallback.isEmpty()) plainFallback = l;
        }
        return plainFallback;
    }

    /** null — не нашлось или сеть не ответила. */
    private static String get(String url) throws InterruptedException {
        if (paused()) return null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                SafeHttp.Reply r = HTTP.send("GET", url, HEADERS);
                if (r.status() == 429 || r.status() >= 500) throw new IOException("HTTP " + r.status());
                failsInRow = 0;
                return r.status() == 200 ? r.body() : null;
            } catch (IOException e) {
                if (attempt == 0) { Thread.sleep(700); continue; }
                if (++failsInRow >= FAILS_BEFORE_PAUSE) {
                    failsInRow = 0;
                    pausedUntil = System.currentTimeMillis() + PAUSE_MS;
                    MusicPlayerMod.LOGGER.warn("LRCLIB unavailable ({}), lookups paused for {} s", e.getMessage(), PAUSE_MS / 1000);
                }
                return null;
            }
        }
        return null;
    }

    private static List<LyricLine> exact(Track t, String title, String artist, String album) throws InterruptedException {
        String url = "https://lrclib.net/api/get"
                + "?track_name=" + enc(title)
                + "&artist_name=" + enc(artist)
                + (album != null ? "&album_name=" + enc(album) : "")
                + (t.durationMs() > 0 ? "&duration=" + Math.round(t.durationMs() / 1000.0) : "");
        String body = get(url);
        if (body == null) return List.of();
        try {
            Candidate c = candidate(JsonParser.parseString(body).getAsJsonObject(), t);
            return c == null ? List.of() : c.lines;
        } catch (JsonParseException | IllegalStateException e) {
            return List.of();
        }
    }

    /** Из результатов поиска берём лучший из прошедших проверку: синхронизированный важнее, затем ближе по длительности. */
    private static List<LyricLine> search(Track t, String query) throws InterruptedException {
        String body = get("https://lrclib.net/api/search?" + query);
        if (body == null) return List.of();
        try {
            JsonArray arr = JsonParser.parseString(body).getAsJsonArray();
            Candidate best = null;
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                Candidate c = candidate(e.getAsJsonObject(), t);
                if (c != null && (best == null || c.betterThan(best))) best = c;
            }
            return best == null ? List.of() : best.lines;
        } catch (JsonParseException | IllegalStateException e) {
            return List.of();
        }
    }

    private record Candidate(List<LyricLine> lines, boolean synced, double diff) {
        boolean betterThan(Candidate o) {
            if (synced != o.synced) return synced;
            return Double.compare(Double.isNaN(diff) ? 0 : diff, Double.isNaN(o.diff) ? 0 : o.diff) < 0;
        }
    }

    /** null — вариант не подходит этому треку. */
    private static Candidate candidate(JsonObject o, Track t) {
        Level level = TrackMatch.level(str(o, "trackName"), str(o, "artistName"), t);
        if (level == Level.NONE) return null;

        double diff = TrackMatch.durationDiff(o.has("duration") && !o.get("duration").isJsonNull() ? o.get("duration").getAsDouble() : 0, t);

        List<LyricLine> synced = hasText(o, "syncedLyrics") ? LrcParser.parse(o.get("syncedLyrics").getAsString()) : List.of();
        if (!synced.isEmpty() && TrackMatch.durationFits(diff, MAX_DIFF_SYNCED, level) && TrackMatch.scriptOk(t.title(), synced))
            return new Candidate(synced, true, diff);

        // тайминги другой версии не подходят, но обычный текст у той же песни всё равно лучше, чем ничего
        List<LyricLine> plain = hasText(o, "plainLyrics") ? LrcParser.plain(o.get("plainLyrics").getAsString()) : List.of();
        if (!plain.isEmpty() && TrackMatch.durationFits(diff, MAX_DIFF_PLAIN, level) && TrackMatch.scriptOk(t.title(), plain))
            return new Candidate(plain, false, diff);
        return null;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static boolean hasText(JsonObject o, String k) { return !str(o, k).isBlank(); }

    private static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }
}
