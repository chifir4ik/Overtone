package dev.musicplayer.share;

import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.model.Platform;
import dev.musicplayer.model.Track;
import dev.musicplayer.provider.MusicProvider;
import dev.musicplayer.provider.SoundCloudProvider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Находит, чем у слушателя сыграть трек раздающего. Если та же площадка доступна — тот же трек; иначе ищем его
 * по названию там, где слушатель может играть (свой Яндекс или SoundCloud, которому аккаунт не нужен).
 */
final class ShareResolver {
    private ShareResolver() {}

    static Track shown(ShareProto.State s) {
        return new Track(s.id(), s.title(), s.artist(), s.album().isEmpty() ? null : s.album(), s.durationMs(),
                s.cover().isEmpty() ? null : s.cover(), s.platform());
    }

    static CompletableFuture<Track> resolve(ShareProto.State s) {
        PlayerController ctl = PlayerController.I;
        Platform p = Platform.byIdOrNull(s.platform());
        if (p != null && p != Platform.LOCAL && !s.id().isEmpty()) {
            MusicProvider same = ctl.provider(p);
            if (same != null && same.isLoggedIn()) return CompletableFuture.completedFuture(shown(s));
        }
        List<MusicProvider> where = new ArrayList<>();
        if (ctl.yandex.isLoggedIn()) where.add(ctl.yandex);
        where.add(ctl.soundcloud);
        return search(s, where, 0);
    }

    private static CompletableFuture<Track> search(ShareProto.State s, List<MusicProvider> where, int i) {
        if (i >= where.size()) return CompletableFuture.failedFuture(new IOException("not found"));
        String query = (firstArtist(s.artist()) + " " + s.title()).trim();
        return where.get(i).search(query).handle((list, err) -> err == null ? best(s, list) : null)
                .thenCompose(t -> t != null ? CompletableFuture.completedFuture(merge(s, t)) : search(s, where, i + 1));
    }

    /** Играем найденное, но показываем то, что у раздающего: название и обложку оригинала. */
    private static Track merge(ShareProto.State s, Track found) {
        String cover = s.cover().isEmpty() ? found.coverUrl() : s.cover();
        return new Track(found.id(), s.title(), s.artist(), s.album().isEmpty() ? found.album() : s.album(),
                found.durationMs(), cover, found.provider());
    }

    private static Track best(ShareProto.State s, List<Track> found) {
        String title = norm(s.title()), artist = norm(firstArtist(s.artist()));
        Track best = null;
        int bestScore = 2;   // одного совпадения названия мало: у каверов оно то же
        for (Track t : found) {
            String tt = norm(t.title()), ta = norm(t.artist());
            int score = 0;
            if (tt.equals(title)) score += 3;
            else if (!title.isEmpty() && (tt.contains(title) || title.contains(tt))) score += 1;
            else continue;
            if (!artist.isEmpty() && (ta.contains(artist) || tt.contains(artist))) score += 2;
            if (s.durationMs() > 0 && t.durationMs() > 0) {
                long diff = Math.abs(s.durationMs() - t.durationMs());
                if (diff <= 5000) score += 2;
                else if (diff > 30_000) score -= 3;
            }
            if (SoundCloudProvider.isPreviewOnly(t.id())) score -= 2;
            if (score > bestScore) { best = t; bestScore = score; }
        }
        return best;
    }

    private static String firstArtist(String artist) {
        int cut = artist.indexOf(',');
        return (cut < 0 ? artist : artist.substring(0, cut)).trim();
    }

    /** Без скобок, «feat.» и знаков: «Song (Remastered 2011)» и «song» — одно и то же. */
    static String norm(String s) {
        if (s == null) return "";
        String x = s.toLowerCase(Locale.ROOT)
                .replaceAll("[(\\[].*?[)\\]]", " ")
                .replaceAll("\\b(feat|ft)\\..*", " ")
                .replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}]+", " ");
        return x.trim();
    }
}
