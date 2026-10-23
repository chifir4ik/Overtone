package dev.musicplayer.lyrics;

import dev.musicplayer.MusicPlayerMod;
import dev.musicplayer.config.PlayerConfig;
import dev.musicplayer.model.LyricLine;
import dev.musicplayer.model.Track;
import dev.musicplayer.provider.MusicProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Источники опрашиваются параллельно: цепочкой один зависший (VPN, блокировки) съедал общий таймаут, и до LRCLIB
 * дело не доходило. Приоритет: площадка, AMLL, LRCLIB, KuGou; без таймкодов — площадка, LRCLIB.
 */
public final class LyricsService {
    private static final long SOURCE_TIMEOUT_SECONDS = 20;
    // LRCLIB перебирает несколько запросов подряд
    private static final long LRCLIB_TIMEOUT_SECONDS = 28;
    /** Без потолка зависший запрос оставляет плеер в вечном «поиске текста». */
    private static final long TOTAL_TIMEOUT_SECONDS = 32;
    /** Сколько ждём текст площадки (у него бывают тайминги слов), когда LRCLIB уже нашёл свой. */
    private static final long PREFER_WAIT_MS = 2000;

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "musicplayer-lyrics-timer");
        t.setDaemon(true);
        return t;
    });

    /** Играющий и следующий трек: поиск для пролистанного бросаем, чтобы не тратить запросы. */
    private static volatile Track wanted, upcoming;
    /** Если текст уже ищется (например, заранее для следующего трека), второй поиск не запускаем. */
    private static final Map<Track, CompletableFuture<List<LyricLine>>> RUNNING = new ConcurrentHashMap<>();

    private LyricsService() {}

    static boolean wanted(Track t) { return t.equals(wanted) || t.equals(upcoming); }

    public static CompletableFuture<List<LyricLine>> fetch(Track t, MusicProvider source) {
        List<LyricLine> own = TrackLyrics.custom(t);
        if (!own.isEmpty()) return done(own);
        wanted = t;
        return lookup(t, source, false);
    }

    /** Ищем текст следующего трека, пока играет этот: к его началу текст уже лежит в кэше. */
    public static void prefetch(Track t, MusicProvider source) {
        if (t == null || t.equals(upcoming) || TrackLyrics.hasCustom(t)) return;
        upcoming = t;
        lookup(t, source, true);
    }

    private static CompletableFuture<List<LyricLine>> lookup(Track t, MusicProvider source, boolean ahead) {
        CompletableFuture<List<LyricLine>> mine = new CompletableFuture<>();
        CompletableFuture<List<LyricLine>> running = RUNNING.putIfAbsent(t, mine);
        if (running != null) return running;
        LyricsIo.run(() -> {
            List<LyricLine> cached = LyricsCache.get(t);
            return cached != null ? cached : List.of();
        }).thenCompose(cached -> {
            if (!cached.isEmpty()) return done(cached);
            // недавно уже искали и точно ничего нет: повторные попытки не должны снова дёргать сеть
            if (LyricsCache.missing(t)) return done(List.<LyricLine>of());
            return search(t, source, ahead);
        }).whenComplete((l, e) -> {
            RUNNING.remove(t, mine);
            if (e != null) mine.completeExceptionally(e);
            else mine.complete(l);
        });
        return mine;
    }

    private static CompletableFuture<List<LyricLine>> search(Track t, MusicProvider source, boolean ahead) {
        PlayerConfig c = PlayerConfig.get();
        boolean online = c.onlineLyrics;

        List<CompletableFuture<List<LyricLine>>> synced = new ArrayList<>();
        Map<CompletableFuture<List<LyricLine>>, String> names = new java.util.IdentityHashMap<>();
        CompletableFuture<List<LyricLine>> platformSynced = guard(source.lyrics(t, true), SOURCE_TIMEOUT_SECONDS);
        synced.add(platformSynced);
        names.put(platformSynced, "platform");
        if (online && c.amllLyrics) {
            CompletableFuture<List<LyricLine>> amll = guard(AmllClient.fetch(t), SOURCE_TIMEOUT_SECONDS);
            synced.add(amll);
            names.put(amll, "AMLL");
        }
        CompletableFuture<List<LyricLine>> lrclibRaw = online ? LrcLibClient.fetch(t) : done(List.of());
        CompletableFuture<List<LyricLine>> lrclib = guard(lrclibRaw, LRCLIB_TIMEOUT_SECONDS);
        synced.add(lrclib);
        names.put(lrclib, "LRCLIB");
        if (online && c.kugouLyrics) {
            CompletableFuture<List<LyricLine>> kugou = guard(KuGouClient.fetch(t), SOURCE_TIMEOUT_SECONDS);
            synced.add(kugou);
            names.put(kugou, "KuGou");
        }
        CompletableFuture<List<LyricLine>> platformPlain = guard(source.lyrics(t, false), SOURCE_TIMEOUT_SECONDS);
        names.put(platformPlain, "platform");

        List<CompletableFuture<List<LyricLine>>> plain = List.of(platformPlain, lrclib);
        CompletableFuture<List<LyricLine>> result = new CompletableFuture<>();
        long start = System.currentTimeMillis();
        Runnable decide = () -> {
            if (result.isDone()) return;
            List<LyricLine> best = pick(synced, plain, System.currentTimeMillis() - start >= PREFER_WAIT_MS);
            if (best != null) result.complete(best);
        };
        for (CompletableFuture<List<LyricLine>> f : synced) f.whenComplete((l, e) -> decide.run());
        platformPlain.whenComplete((l, e) -> decide.run());
        TIMER.schedule(decide, PREFER_WAIT_MS + 50, TimeUnit.MILLISECONDS);

        return result
                .completeOnTimeout(null, TOTAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .thenApply(l -> l != null ? l : anything(synced, plain))
                .exceptionally(e -> List.of())
                .thenApply(l -> {
                    String when = (System.currentTimeMillis() - start) + " ms" + (ahead ? ", ahead of time" : "");
                    if (!l.isEmpty()) {
                        LyricsCache.put(t, l);
                        String from = "?";
                        for (var e : names.entrySet()) if (e.getKey().getNow(null) == l) from = e.getValue();
                        MusicPlayerMod.LOGGER.info("Lyrics for \"{}\" by {}: {}, {} lines{} ({})", t.title(), t.artist(), from,
                                l.size(), l.get(0).timeMs() == LyricLine.UNSYNCED ? " without timings" : "", when);
                        return l;
                    }
                    // по причине видно, нет ли песни в базе или LRCLIB просто не ответил
                    String why = !online ? "online lyrics off" : !lrclibRaw.isDone() ? "LRCLIB did not answer"
                            : lrclibRaw.isCompletedExceptionally() ? "LRCLIB failed: " + failure(lrclibRaw) : "not in LRCLIB";
                    MusicPlayerMod.LOGGER.info("No lyrics found: \"{}\" by {} — {} ({})", t.title(), t.artist(), why, when);
                    // «нет» запоминаем, только если LRCLIB честно ответил, а не упал и не был прерван
                    if (online && lrclibRaw.isDone() && !lrclibRaw.isCompletedExceptionally()) LyricsCache.markMissing(t);
                    return l;
                });
    }

    /** null — ещё ждём более приоритетные источники. */
    private static List<LyricLine> pick(List<CompletableFuture<List<LyricLine>>> synced,
                                        List<CompletableFuture<List<LyricLine>>> plain, boolean impatient) {
        boolean higherPending = false;
        for (CompletableFuture<List<LyricLine>> f : synced) {
            if (!f.isDone()) { higherPending = true; continue; }
            List<LyricLine> l = f.getNow(List.of());
            if (isSynced(l) && (!higherPending || impatient)) return l;
        }
        if (higherPending) return null;
        for (CompletableFuture<List<LyricLine>> f : plain) {
            if (!f.isDone()) return null;
            List<LyricLine> l = f.getNow(List.of());
            if (!l.isEmpty()) return l;
        }
        return List.of();
    }

    private static List<LyricLine> anything(List<CompletableFuture<List<LyricLine>>> synced,
                                            List<CompletableFuture<List<LyricLine>>> plain) {
        for (CompletableFuture<List<LyricLine>> f : synced) {
            List<LyricLine> l = f.getNow(List.of());
            if (isSynced(l)) return l;
        }
        for (CompletableFuture<List<LyricLine>> f : plain) {
            List<LyricLine> l = f.getNow(List.of());
            if (!l.isEmpty()) return l;
        }
        return List.of();
    }

    private static CompletableFuture<List<LyricLine>> guard(CompletableFuture<List<LyricLine>> f, long seconds) {
        return f.exceptionally(e -> List.of())
                .thenApply(l -> l == null ? List.<LyricLine>of() : l)
                .completeOnTimeout(List.of(), seconds, TimeUnit.SECONDS);
    }

    private static String failure(CompletableFuture<?> f) {
        try {
            f.getNow(null);
            return "?";
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            while (c.getCause() != null && c.getMessage() == null) c = c.getCause();
            return c.getMessage() != null ? c.getMessage() : c.getClass().getSimpleName();
        }
    }

    private static boolean isSynced(List<LyricLine> l) { return !l.isEmpty() && l.get(0).timeMs() != LyricLine.UNSYNCED; }

    private static CompletableFuture<List<LyricLine>> done(List<LyricLine> l) { return CompletableFuture.completedFuture(l); }
}
