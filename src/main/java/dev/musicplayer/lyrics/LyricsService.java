package dev.musicplayer.lyrics;

import dev.musicplayer.model.LyricLine;
import dev.musicplayer.model.Track;
import dev.musicplayer.provider.YandexMusicProvider;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Порядок источников: синхронизированный текст Яндекс Музыки → LRCLIB (с проверкой совпадения) →
 * несинхронизированный текст Яндекса. Тексты Яндекса относятся именно к этому треку, поэтому им верим первыми.
 */
public final class LyricsService {
    private LyricsService() {}

    public static CompletableFuture<List<LyricLine>> fetch(Track t, YandexMusicProvider yandex) {
        return yandex.lyrics(t, true)
                .thenCompose(l -> !l.isEmpty() ? done(l) : LrcLibClient.fetch(t))
                .thenCompose(l -> l.isEmpty() || isPlain(l)
                        ? yandex.lyrics(t, false).thenApply(y -> y.isEmpty() ? l : y)
                        : done(l))
                .exceptionally(e -> List.of());
    }

    private static boolean isPlain(List<LyricLine> l) { return l.get(0).timeMs() == LyricLine.UNSYNCED; }

    private static CompletableFuture<List<LyricLine>> done(List<LyricLine> l) { return CompletableFuture.completedFuture(l); }
}
