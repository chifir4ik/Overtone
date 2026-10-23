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
import java.util.ArrayList;
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
    private static final SafeHttp HTTP = new SafeHttp(Set.of("lrclib.net"), Duration.ofSeconds(6));
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

    /** Пустой список — LRCLIB ответил, что подходящего нет. Ошибка — не ответил или трек уже пролистали. */
    public static CompletableFuture<List<LyricLine>> fetch(Track t) {
        return LyricsIo.attempt(() -> lookup(t));
    }

    /**
     * LRCLIB режет частые запросы (503), поэтому их немного: поиск по названию с исполнителем и по одному названию.
     * Раньше их было до двадцати на трек, и при быстром листании он отключал нас совсем. Точный /api/get не зовём:
     * не найдя песню у себя, он идёт искать её по внешним сервисам и висит по 10-20 секунд.
     */
    private static List<LyricLine> lookup(Track t) throws IOException, InterruptedException {
        List<Query> queries = TrackMatch.queries(t);
        String clean = TrackMatch.cleanTitle(queries.get(0).title());
        IOException failed = null;

        Set<String> searches = new LinkedHashSet<>();
        for (Query q : queries) searches.add("q=" + enc(TrackMatch.cleanTitle(q.title()) + " " + TrackMatch.firstArtist(q.artist())));
        // исполнитель в базе мог быть записан иначе: ищем по одному названию, а совпадение решает сверка
        searches.add("track_name=" + enc(clean));

        List<LyricLine> plainFallback = List.of();
        long deadline = System.currentTimeMillis() + BUDGET_MS;
        for (String q : searches) {
            // LRCLIB висит: остальные запросы тоже повиснут, а плеер всё это время показывает «ищу текст»
            if (failed != null && System.currentTimeMillis() > deadline) break;
            List<LyricLine> l;
            try {
                l = search(t, q);
            } catch (Unreachable e) {
                // один запрос упал — следующий может пройти; сдаёмся только если пролистали или LRCLIB на паузе
                if (e.fatal) throw e;
                failed = e;
                continue;
            }
            if (l.isEmpty()) continue;
            if (l.get(0).timeMs() != LyricLine.UNSYNCED) return l;
            if (plainFallback.isEmpty()) plainFallback = l;
        }
        if (plainFallback.isEmpty() && failed != null) throw failed;
        return plainFallback;
    }

    private static final class Unreachable extends IOException {
        final boolean fatal;

        Unreachable(String message, boolean fatal) {
            super(message);
            this.fatal = fatal;
        }
    }

    /** LRCLIB под нагрузкой отвечает 503 через раз, и второй-третий запрос обычно проходит. */
    private static final long[] RETRY_MS = {600, 1200};
    /** Повторяем только быстрый отказ: если сервер молчит до таймаута, повтор лишь удвоит ожидание. */
    private static final long QUICK_FAIL_MS = 2500;
    private static final long BUDGET_MS = 12_000;

    /** null — такого нет (404). Сбой сети, пауза или пролистанный трек — исключение. */
    private static String get(String url, Track t) throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            if (!LyricsService.wanted(t)) throw new Unreachable("track skipped", true);
            if (paused()) throw new Unreachable("LRCLIB paused", true);
            long sent = System.currentTimeMillis();
            try {
                SafeHttp.Reply r = HTTP.send("GET", url, HEADERS);
                if (r.status() == 429 || r.status() >= 500) throw new IOException("HTTP " + r.status());
                failsInRow = 0;
                return r.status() == 200 ? r.body() : null;
            } catch (IOException e) {
                if (attempt < RETRY_MS.length && System.currentTimeMillis() - sent < QUICK_FAIL_MS) {
                    Thread.sleep(RETRY_MS[attempt]);
                    continue;
                }
                if (++failsInRow >= FAILS_BEFORE_PAUSE) {
                    failsInRow = 0;
                    pausedUntil = System.currentTimeMillis() + PAUSE_MS;
                    MusicPlayerMod.LOGGER.warn("LRCLIB unavailable ({}), lookups paused for {} s", e.getMessage(), PAUSE_MS / 1000);
                }
                throw new Unreachable(e.getMessage(), false);
            }
        }
    }

    /** Из результатов поиска берём лучший из прошедших проверку: синхронизированный важнее, затем ближе по длительности. */
    private static List<LyricLine> search(Track t, String query) throws IOException, InterruptedException {
        String body = get("https://lrclib.net/api/search?" + query, t);
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

        double seconds = o.has("duration") && !o.get("duration").isJsonNull() ? o.get("duration").getAsDouble() : 0;
        double diff = TrackMatch.durationDiff(seconds, t);

        List<LyricLine> synced = hasText(o, "syncedLyrics") ? LrcParser.parse(o.get("syncedLyrics").getAsString()) : List.of();
        int tempo = TrackMatch.tempo(t.title());
        if (tempo != 0 && TrackMatch.tempo(str(o, "trackName")) == 0) return stretched(o, t, tempo, seconds, synced);
        if (!synced.isEmpty() && TrackMatch.durationFits(diff, MAX_DIFF_SYNCED, level) && TrackMatch.scriptOk(t.title(), synced))
            return new Candidate(synced, true, diff);

        // тайминги другой версии не подходят, но обычный текст у той же песни всё равно лучше, чем ничего
        List<LyricLine> plain = hasText(o, "plainLyrics") ? LrcParser.plain(o.get("plainLyrics").getAsString()) : List.of();
        if (!plain.isEmpty() && TrackMatch.durationFits(diff, MAX_DIFF_PLAIN, level) && TrackMatch.scriptOk(t.title(), plain))
            return new Candidate(plain, false, diff);
        return null;
    }

    /**
     * Для slowed / sped up текста своей версии почти никогда нет, но замедляют всю песню равномерно:
     * тайминги оригинала растягиваются по отношению длительностей и ложатся на замедленный трек.
     */
    private static Candidate stretched(JsonObject o, Track t, int tempo, double seconds, List<LyricLine> synced) {
        if (seconds <= 0 || t.durationMs() <= 0) return null;
        double ratio = t.durationMs() / (seconds * 1000);
        // заодно отсекает чужую песню с тем же названием: длина должна измениться в ту сторону, что и темп
        if (tempo < 0 ? ratio < 1.04 || ratio > 1.6 : ratio < 0.6 || ratio > 0.96) return null;
        double typical = tempo < 0 ? 1.2 : 0.8;
        if (!synced.isEmpty() && TrackMatch.scriptOk(t.title(), synced)) {
            List<LyricLine> out = new ArrayList<>(synced.size());
            for (LyricLine l : synced) {
                List<LyricLine.Word> words = null;
                if (l.hasWordTimings()) {
                    words = new ArrayList<>(l.words().size());
                    for (LyricLine.Word w : l.words()) words.add(new LyricLine.Word(Math.round(w.timeMs() * ratio), w.text()));
                }
                out.add(new LyricLine(l.timeMs() == LyricLine.UNSYNCED ? l.timeMs() : Math.round(l.timeMs() * ratio), l.text(), words));
            }
            return new Candidate(out, true, Math.abs(ratio - typical));
        }
        List<LyricLine> plain = hasText(o, "plainLyrics") ? LrcParser.plain(o.get("plainLyrics").getAsString()) : List.of();
        return plain.isEmpty() || !TrackMatch.scriptOk(t.title(), plain) ? null : new Candidate(plain, false, Math.abs(ratio - typical));
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static boolean hasText(JsonObject o, String k) { return !str(o, k).isBlank(); }

    private static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }
}
