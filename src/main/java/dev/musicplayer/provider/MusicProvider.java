package dev.musicplayer.provider;

import dev.musicplayer.model.Track;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Единый интерфейс провайдера музыки. */
public interface MusicProvider {
    String name();
    boolean isLoggedIn();

    CompletableFuture<Void> login();
    void logout();

    CompletableFuture<List<Track>> search(String query);
    CompletableFuture<List<Track>> likedTracks();

    CompletableFuture<Void> play(Track t);
    void pause();
    void resume();
    void seek(long positionMs);
    long positionMs();
    boolean isPlaying();

    void setVolume(float volume01);
    /** Вызывается, когда трек доиграл до конца сам (не по паузе/переключению). */
    void onFinished(Runnable r);
}
