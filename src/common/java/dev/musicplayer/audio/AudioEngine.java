package dev.musicplayer.audio;

import java.util.function.Consumer;

/** Проигрыватель закодированного трека из памяти. Реализации различаются только форматом. */
public interface AudioEngine {
    void play(byte[] data, long startMs, Runnable onEnd);
    void seek(long ms);
    void stop();
    void pause();
    void resume();
    void setVolume(float v);
    boolean isPlaying();
    long positionMs();
    void onError(Consumer<Throwable> c);
}
