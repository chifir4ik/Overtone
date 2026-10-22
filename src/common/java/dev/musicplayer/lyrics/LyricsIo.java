package dev.musicplayer.lyrics;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.musicplayer.model.LyricLine;

/** Фоновый пул для источников текстов с блокирующим HTTP. Любая ошибка превращается в «ничего не найдено». */
final class LyricsIo {
    private static final ExecutorService POOL = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "musicplayer-lyrics");
        t.setDaemon(true);
        return t;
    });

    private LyricsIo() {}

    static CompletableFuture<List<LyricLine>> run(Callable<List<LyricLine>> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                return List.<LyricLine>of();
            }
        }, POOL);
    }
}
