package dev.musicplayer.lyrics;

import dev.musicplayer.config.PlayerConfig;
import dev.musicplayer.model.LyricLine;
import dev.musicplayer.model.Track;
import dev.musicplayer.provider.MusicProvider;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Порядок источников: текст площадки (или .lrc рядом с файлом), затем LRCLIB с проверкой совпадения, затем обычный текст площадки. */
public final class LyricsService {
    private LyricsService() {}

    public static CompletableFuture<List<LyricLine>> fetch(Track t, MusicProvider source) {
        return source.lyrics(t, true)
                .thenCompose(l -> !l.isEmpty() ? done(l) : online(t))
                .thenCompose(l -> l.isEmpty() || isPlain(l)
                        ? source.lyrics(t, false).thenApply(y -> y.isEmpty() ? l : y)
                        : done(l))
                .exceptionally(e -> List.of());
    }

    private static CompletableFuture<List<LyricLine>> online(Track t) {
        return PlayerConfig.get().onlineLyrics ? LrcLibClient.fetch(t) : done(List.of());
    }

    private static boolean isPlain(List<LyricLine> l) { return l.get(0).timeMs() == LyricLine.UNSYNCED; }

    private static CompletableFuture<List<LyricLine>> done(List<LyricLine> l) { return CompletableFuture.completedFuture(l); }
}
