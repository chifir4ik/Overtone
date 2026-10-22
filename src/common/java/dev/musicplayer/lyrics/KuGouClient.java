package dev.musicplayer.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.musicplayer.lyrics.TrackMatch.Level;
import dev.musicplayer.model.LyricLine;
import dev.musicplayer.model.Track;
import dev.musicplayer.net.SafeHttp;
import dev.musicplayer.util.Json;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Тексты KuGou. Официального API нет, запросы повторяют приложение сервиса, поэтому источник запасной и выключен по умолчанию. */
public final class KuGouClient {
    private static final SafeHttp HTTP = new SafeHttp(Set.of("mobileservice.kugou.com", "lyrics.kugou.com"));
    private static final Map<String, String> HEADERS = Map.of("User-Agent", "Overtone (Minecraft mod)");
    private static final double MAX_DIFF = 5;
    private static final int SONGS = 8;

    private KuGouClient() {}

    public static CompletableFuture<List<LyricLine>> fetch(Track t) {
        return LyricsIo.run(() -> lookup(t));
    }

    private static List<LyricLine> lookup(Track t) throws Exception {
        TrackMatch.Query q = TrackMatch.queries(t).get(0);
        String keyword = TrackMatch.cleanTitle(q.title()) + " " + TrackMatch.firstArtist(q.artist());

        JsonObject found = HTTP.getJson("https://mobileservice.kugou.com/api/v3/search/song?format=json&page=1&pagesize="
                + SONGS + "&showtype=1&keyword=" + enc(keyword), HEADERS);
        JsonArray songs = Json.array(Json.obj(found, "data"), "info");

        for (JsonElement e : songs) {
            JsonObject s = e.getAsJsonObject();
            if (!sameSong(s, t)) continue;
            List<LyricLine> lines = lyricsFor(Json.str(s, "hash"), keyword, t);
            if (!lines.isEmpty() && TrackMatch.scriptOk(t.title(), lines)) return lines;
        }
        return List.of();
    }

    private static boolean sameSong(JsonObject s, Track t) {
        double sec = s.has("duration") && s.get("duration").isJsonPrimitive() ? s.get("duration").getAsDouble() : 0;
        double diff = TrackMatch.durationDiff(sec, t);
        for (String singer : Json.str(s, "singername").split("、")) {
            Level level = TrackMatch.level(Json.str(s, "songname"), singer, t);
            if (level != Level.NONE && TrackMatch.durationFits(diff, MAX_DIFF, level)) return true;
        }
        return false;
    }

    private static List<LyricLine> lyricsFor(String hash, String keyword, Track t) throws Exception {
        if (hash.isEmpty()) return List.of();
        JsonObject res = HTTP.getJson("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=" + enc(keyword)
                + "&duration=" + Math.max(0, t.durationMs()) + "&hash=" + enc(hash), HEADERS);
        JsonArray candidates = Json.array(res, "candidates");
        if (candidates.isEmpty()) return List.of();

        JsonObject best = candidates.get(0).getAsJsonObject();
        JsonObject dl = HTTP.getJson("https://lyrics.kugou.com/download?ver=1&client=pc&fmt=lrc&charset=utf8&id="
                + enc(Json.str(best, "id")) + "&accesskey=" + enc(Json.str(best, "accesskey")), HEADERS);
        String content = Json.str(dl, "content");
        if (content.isEmpty()) return List.of();

        String lrc = new String(Base64.getMimeDecoder().decode(content), StandardCharsets.UTF_8).replace("\uFEFF", "");
        List<LyricLine> lines = LrcParser.parse(lrc);
        lines.removeIf(l -> l.text().isBlank());
        return !lines.isEmpty() && lines.get(0).timeMs() == LyricLine.UNSYNCED ? List.of() : lines;
    }

    private static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }
}
