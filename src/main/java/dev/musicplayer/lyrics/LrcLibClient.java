package dev.musicplayer.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.musicplayer.model.LyricLine;
import dev.musicplayer.model.Track;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Клиент LRCLIB (https://lrclib.net). Запасной источник: основной — тексты самой Яндекс Музыки.
 * <p>
 * Каждый найденный вариант проверяется на совпадение названия, исполнителя, длительности и алфавита,
 * иначе поиск по неточному запросу подсовывает тексты других песен (в том числе английские).
 * Лучше показать пустой текст, чем чужой.
 */
public final class LrcLibClient {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();

    /** Допустимая разница длительностей, с: у синхронизированного текста строже, т.к. от версии зависят тайминги. */
    private static final double MAX_DIFF_SYNCED = 5, MAX_DIFF_PLAIN = 15;

    /** Минимальная доля кириллицы в тексте, если название трека написано кириллицей. */
    private static final double MIN_CYRILLIC_SHARE = 0.2;

    private LrcLibClient() {}

    /** Не бросает исключений; пустой список — ничего подходящего не нашлось. */
    public static CompletableFuture<List<LyricLine>> fetch(Track t) {
        String firstArtist = firstArtist(t.artist());
        String clean = cleanTitle(t.title());
        CompletableFuture<List<LyricLine>> f = exact(t, t.title(), t.artist(), t.album());
        f = then(f, () -> exact(t, clean, firstArtist, null));
        f = then(f, () -> search(t, "track_name=" + enc(t.title()) + "&artist_name=" + enc(t.artist())));
        f = then(f, () -> search(t, "track_name=" + enc(clean) + "&artist_name=" + enc(firstArtist)));
        f = then(f, () -> search(t, "q=" + enc(clean + " " + firstArtist)));
        return f.exceptionally(e -> List.of());
    }

    private static CompletableFuture<List<LyricLine>> then(CompletableFuture<List<LyricLine>> prev,
                                                           Supplier<CompletableFuture<List<LyricLine>>> next) {
        return prev.thenCompose(l -> l.isEmpty() ? next.get() : CompletableFuture.completedFuture(l));
    }

    private static CompletableFuture<List<LyricLine>> exact(Track t, String title, String artist, String album) {
        String url = "https://lrclib.net/api/get"
                + "?track_name=" + enc(title)
                + "&artist_name=" + enc(artist)
                + (album != null ? "&album_name=" + enc(album) : "")
                + (t.durationMs() > 0 ? "&duration=" + Math.round(t.durationMs() / 1000.0) : "");
        return HTTP.sendAsync(req(url), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(r -> {
                    if (r.statusCode() != 200) return List.<LyricLine>of();
                    Candidate c = candidate(JsonParser.parseString(r.body()).getAsJsonObject(), t);
                    return c == null ? List.<LyricLine>of() : c.lines;
                })
                .exceptionally(e -> List.of());
    }

    /** Из результатов поиска берём лучший из прошедших проверку: синхронизированный важнее, затем ближе по длительности. */
    private static CompletableFuture<List<LyricLine>> search(Track t, String query) {
        return HTTP.sendAsync(req("https://lrclib.net/api/search?" + query),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(r -> {
                    if (r.statusCode() != 200) return List.<LyricLine>of();
                    JsonArray arr = JsonParser.parseString(r.body()).getAsJsonArray();
                    Candidate best = null;
                    for (JsonElement e : arr) {
                        Candidate c = candidate(e.getAsJsonObject(), t);
                        if (c != null && (best == null || c.betterThan(best))) best = c;
                    }
                    return best == null ? List.<LyricLine>of() : best.lines;
                })
                .exceptionally(e -> List.of());
    }

    private record Candidate(List<LyricLine> lines, boolean synced, double diff) {
        boolean betterThan(Candidate o) {
            if (synced != o.synced) return synced;
            return diff < o.diff;
        }
    }

    /** null — вариант не подходит этому треку. */
    private static Candidate candidate(JsonObject o, Track t) {
        if (!sameTitle(str(o, "trackName"), t.title()) || !sameArtist(str(o, "artistName"), t.artist())) return null;

        List<LyricLine> lines = hasText(o, "syncedLyrics") ? LrcParser.parse(o.get("syncedLyrics").getAsString()) : List.of();
        boolean synced = !lines.isEmpty();
        if (!synced) lines = hasText(o, "plainLyrics") ? LrcParser.plain(o.get("plainLyrics").getAsString()) : List.of();
        if (lines.isEmpty()) return null;

        double diff = 0;
        if (t.durationMs() > 0 && o.has("duration") && !o.get("duration").isJsonNull())
            diff = Math.abs(o.get("duration").getAsDouble() - t.durationMs() / 1000.0);
        if (diff > (synced ? MAX_DIFF_SYNCED : MAX_DIFF_PLAIN)) return null;

        if (!scriptMatches(t.title(), lines)) return null;
        return new Candidate(lines, synced, diff);
    }

    /** Название на кириллице — значит и текст должен быть в основном на кириллице, а не на английском. */
    private static boolean scriptMatches(String title, List<LyricLine> lines) {
        if (!hasCyrillic(title)) return true;
        int cyr = 0, letters = 0;
        for (LyricLine l : lines) {
            for (char c : l.text().toCharArray()) {
                if (Character.UnicodeScript.of(c) == Character.UnicodeScript.CYRILLIC) { cyr++; letters++; }
                else if (Character.isLetter(c)) letters++;
            }
        }
        return letters == 0 || cyr >= letters * MIN_CYRILLIC_SHARE;
    }

    private static boolean hasCyrillic(String s) {
        if (s == null) return false;
        for (char c : s.toCharArray()) if (Character.UnicodeScript.of(c) == Character.UnicodeScript.CYRILLIC) return true;
        return false;
    }

    private static boolean sameTitle(String found, String wanted) {
        return similar(norm(cleanTitle(found)), norm(cleanTitle(wanted))) || similar(norm(found), norm(wanted));
    }

    /** Совпадает хотя бы один из исполнителей трека (в списке «A, B & C»). */
    private static boolean sameArtist(String found, String wanted) {
        String f = norm(found);
        if (f.isEmpty()) return false;
        for (String name : (wanted == null ? "" : wanted).split(",|&| feat\\.| ft\\.| x ")) {
            String n = norm(name);
            if (!n.isEmpty() && similar(f, n)) return true;
        }
        return similar(f, norm(wanted));
    }

    /** Равны, либо одна строка входит в другую и не короче её половины («Звезда» ≠ «Звезда по имени Солнце»). */
    private static boolean similar(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        String shorter = a.length() <= b.length() ? a : b, longer = shorter == a ? b : a;
        return longer.contains(shorter) && shorter.length() * 2 >= longer.length();
    }

    private static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static String firstArtist(String artist) {
        if (artist == null) return "";
        String a = artist.split(",|&| feat\\.| ft\\.| x ")[0].trim();
        return a.isEmpty() ? artist : a;
    }

    /** Убирает хвосты вроде «(Remix)», «[Live]», «- Radio Edit», «feat. …». */
    private static String cleanTitle(String title) {
        if (title == null) return "";
        String c = title.replaceAll("\\s*[\\(\\[].*?[\\)\\]]", "")
                .replaceAll("(?i)\\s+(feat\\.|ft\\.|featuring)\\s.*$", "")
                .replaceAll("\\s+-\\s+.*$", "")
                .trim();
        return c.isEmpty() ? title : c;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static boolean hasText(JsonObject o, String k) { return !str(o, k).isBlank(); }

    private static HttpRequest req(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Overtone/0.1 (fabric mod)")
                .timeout(Duration.ofSeconds(10)).GET().build();
    }

    private static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }
}
