package dev.musicplayer.audio;

import javax.sound.sampled.AudioFormat;

/**
 * PCM-выход плеера: OpenAL (как у Minecraft) или Java Sound как запасной вариант.
 * Все методы, кроме {@link #abort()}, {@link #setVolume(float)}, {@link #microsecondPosition()},
 * {@link #queuedBytes()} и {@link #isRunning()}, вызываются из аудиопотока.
 */
public interface AudioOut extends AutoCloseable {
    AudioFormat format();

    void start();

    void pause();

    void resume();

    boolean isRunning();

    /** Блокируется, пока в очереди вывода не освободится место. */
    void write(byte[] pcm, int off, int len) throws Exception;

    /** Дожидается проигрывания всего, что уже поставлено в очередь. */
    void drain() throws Exception;

    /** Быстро останавливает звук из любого потока; закрытие делает аудиопоток. */
    void abort();

    /** Позиция с начала текущего сеанса, мкс. Можно вызывать из любого потока. */
    long microsecondPosition();

    /** Сколько байт PCM ещё не проиграно (для визуализатора). */
    int queuedBytes();

    void setVolume(float volume);

    @Override
    void close();
}
