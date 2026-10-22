package dev.musicplayer.lyrics;

import dev.musicplayer.MusicPlayerMod;
import dev.musicplayer.config.PlayerConfig;
import dev.musicplayer.model.LyricLine;
import dev.musicplayer.model.Track;
import dev.musicplayer.provider.MusicProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
    private static final long PREFER_WAIT_MS = 5000;

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "musicplayer-lyrics-timer");
        t.setDaemon(true);
        return t;
    });

    private LyricsService() {}

    public static CompletableFuture<List<LyricLine>> fetch(Track t, MusicProvider source) {
        PlayerConfig c = PlayerConfig.get();
        boolean online = c.onlineLyrics;

        List<CompletableFuture<List<LyricLine>>> synced = new ArrayList<>();
        synced.add(guard(source.lyrics(t, true), SOURCE_TIMEOUT_SECONDS));
        if (online && c.amllLyrics) synced.add(guard(AmllClient.fetch(t), SOURCE_TIMEOUT_SECONDS));
        CompletableFuture<List<LyricLine>> lrclib = online ? guard(LrcLibClient.fetch(t), LRCLIB_TIMEOUT_SECONDS) : done(List.of());
        synced.add(lrclib);
        if (online && c.kugouLyrics) synced.add(guard(KuGouClient.fetch(t), SOURCE_TIMEOUT_SECONDS));
        CompletableFuture<List<LyricLine>> platformPlain = guard(source.lyrics(t, false), SOURCE_TIMEOUT_SECONDS);

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
                    if (l.isEmpty()) MusicPlayerMod.LOGGER.info("No lyrics found: \"{}\" by {}", t.title(), t.artist());
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

    private static boolean isSynced(List<LyricLine> l) { return !l.isEmpty() && l.get(0).timeMs() != LyricLine.UNSYNCED; }

    private static CompletableFuture<List<LyricLine>> done(List<LyricLine> l) { return CompletableFuture.completedFuture(l); }
}
