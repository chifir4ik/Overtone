package dev.musicplayer.provider;

import dev.musicplayer.util.T;
import dev.musicplayer.model.LyricLine;
import dev.musicplayer.model.Platform;
import dev.musicplayer.model.Track;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Одна музыкальная площадка. Обязательны поиск, личный список и воспроизведение, остальное — по возможностям. */
public interface MusicProvider {
    Platform platform();

    /** Площадка готова к работе: для площадок без аккаунта всегда true. */
    boolean isLoggedIn();

    /** Имя аккаунта или короткая строка состояния (например, прогресс индексации). */
    String accountName();

    void logout();

    CompletableFuture<List<Track>> search(String query);

    /** Любимые треки или вся медиатека — что подпись {@link #likedLabel()} обещает. */
    CompletableFuture<List<Track>> likedTracks();

    default String likedLabel() { return T.get("liked.favorites"); }

    CompletableFuture<Void> play(Track t);
    /** Полностью останавливает звук и отменяет загрузку (переключение на другую площадку). */
    void stop();
    void pause();
    void resume();
    void seek(long positionMs);
    long positionMs();
    boolean isPlaying();

    void setVolume(float volume01);
    /** Вызывается, когда трек доиграл до конца сам (не по паузе/переключению). */
    void onFinished(Runnable r);
    void onAudioError(Consumer<String> c);

    default CompletableFuture<List<LyricLine>> lyrics(Track t, boolean synced) {
        return CompletableFuture.completedFuture(List.of());
    }

    default boolean supportsLikes() { return false; }
    default boolean isLiked(String trackId) { return false; }
    default CompletableFuture<Void> setLiked(Track t, boolean like) { return CompletableFuture.completedFuture(null); }

    default boolean isCached(String trackId) { return false; }
    default int loadPercent(String trackId) { return -1; }
    default void setPrefetch(String current, List<Track> upcoming, Set<String> keep) { }
}
