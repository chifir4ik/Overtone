package dev.musicplayer.audio;

import dev.musicplayer.MusicPlayerMod;
import dev.musicplayer.jlayer.Bitstream;
import dev.musicplayer.jlayer.BitstreamException;
import dev.musicplayer.jlayer.Decoder;
import dev.musicplayer.jlayer.DecoderException;
import dev.musicplayer.jlayer.Header;
import dev.musicplayer.jlayer.SampleBuffer;
import dev.musicplayer.util.T;

import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayInputStream;
import java.util.function.Consumer;

/** Проигрыватель MP3 из памяти: jlayer (декодер) + OpenAL/Java Sound (вывод). Пауза, перемотка, громкость. */
public final class Mp3Player implements AudioEngine {
    private byte[] data;
    private Runnable onEnd;
    private volatile Session session;
    private volatile boolean paused;
    private volatile float volume = 0.8f;
    private volatile long lastPositionMs;
    private volatile Consumer<Throwable> onError;

    /** Сюда приходит причина, если звук не удалось открыть или поток оборвался (иначе плеер выглядел бы «на паузе»). */
    public void onError(Consumer<Throwable> c) { this.onError = c; }

    public synchronized void play(byte[] mp3, long startMs, Runnable onEnd) {
        stop();
        this.data = mp3;
        this.onEnd = onEnd;
        this.paused = false;
        start(startMs);
    }

    public synchronized void seek(long ms) {
        if (data == null) return;
        stopSession();
        start(Math.max(0, ms));
    }

    public synchronized void stop() {
        stopSession();
        data = null;
        lastPositionMs = 0;
    }

    public void pause()  { paused = true; }
    public void resume() { paused = false; }

    public void setVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
        Session s = session;
        if (s != null) s.setVolume(volume);
    }

    public boolean isPlaying() {
        Session s = session;
        return s != null && s.alive && !paused;
    }

    public long positionMs() {
        Session s = session;
        return s == null ? lastPositionMs : s.position();
    }

    private void start(long startMs) {
        Session s = new Session(data, startMs, onEnd);
        session = s;
        Thread t = new Thread(s, "musicplayer-audio");
        t.setDaemon(true);
        t.start();
    }

    private void stopSession() {
        Session s = session;
        if (s == null) return;
        lastPositionMs = s.position();
        s.stop = true;
        s.abort();
        session = null;
    }

    private final class Session implements Runnable {
        final byte[] mp3;
        final long baseTarget;
        final Runnable end;
        volatile boolean stop;
        volatile boolean alive = true;
        volatile AudioOut out;
        volatile long baseMs;

        Session(byte[] mp3, long baseTarget, Runnable end) {
            this.mp3 = mp3; this.baseTarget = baseTarget; this.end = end; this.baseMs = baseTarget;
        }

        /** Через 2.5 с проверяем, что звук реально идёт: у открытого, но «мёртвого» выхода позиция стоит на нуле. */
        private void startWatchdog(AudioOut o) {
            Thread wd = new Thread(() -> {
                try { Thread.sleep(2500); } catch (InterruptedException e) { return; }
                if (stop || !alive || paused) return;
                long pos = o.microsecondPosition();
                MusicPlayerMod.LOGGER.info("Audio: after 2.5 s position={} us, running={}", pos, o.isRunning());
                if (pos <= 0) {
                    MusicPlayerMod.LOGGER.warn("Audio: line is open but not playing");
                    Consumer<Throwable> cb = onError;
                    if (cb != null) cb.accept(new IllegalStateException(T.get("err.silent_output")));
                }
            }, "musicplayer-audio-watchdog");
            wd.setDaemon(true);
            wd.start();
        }

        long position() {
            AudioOut o = out;
            return baseMs + (o == null ? 0 : o.microsecondPosition() / 1000);
        }

        void setVolume(float v) {
            AudioOut o = out;
            if (o != null) o.setVolume(v);
        }

        void abort() {
            AudioOut o = out;
            if (o != null) o.abort();
        }

        @Override public void run() {
            Bitstream bs = new Bitstream(new ByteArrayInputStream(mp3));
            Decoder dec = new Decoder();
            try {
                double skipped = 0;
                Header h;
                while (skipped < baseTarget) {
                    try {
                        h = bs.readFrame();
                        if (h == null) break;
                        skipped += h.msPerFrame();
                        bs.closeFrame();
                    } catch (BitstreamException e) { break; }
                }
                baseMs = (long) skipped;

                // Битый или обрезанный кадр (в треках из «Моей волны» бывает мусор в начале/конце файла, отсюда
                int badRun = 0;
                while (!stop) {
                    try { h = bs.readFrame(); }
                    catch (BitstreamException e) {
                        if (++badRun > 40) break;
                        try { bs.closeFrame(); } catch (Throwable ignored) {}
                        continue;
                    }
                    if (h == null) break;
                    SampleBuffer out;
                    try {
                        out = (SampleBuffer) dec.decodeFrame(h, bs);
                        badRun = 0;
                    } catch (DecoderException | IndexOutOfBoundsException | java.nio.BufferUnderflowException e) {
                        if (++badRun > 40) break;
                        try { bs.closeFrame(); } catch (Throwable ignored) {}
                        continue;
                    }

                    AudioOut o = this.out;
                    if (o == null) {
                        AudioFormat f = new AudioFormat(dec.getOutputFrequency(), 16, dec.getOutputChannels(), true, false);
                        o = AudioOuts.open(f);
                        this.out = o;
                        o.setVolume(volume);
                        o.start();
                        MusicPlayerMod.LOGGER.info("Audio: output opened {} Hz, {} ch, {}",
                                f.getSampleRate(), f.getChannels(), o.getClass().getSimpleName());
                        startWatchdog(o);
                    }

                    while (paused && !stop) {
                        if (o.isRunning()) o.pause();
                        Thread.sleep(30);
                    }
                    if (stop) break;
                    if (!o.isRunning()) o.resume();

                    short[] s = out.getBuffer();
                    int n = out.getBufferLength();
                    AudioFormat lf = o.format();
                    dev.musicplayer.viz.Spectrum.feedPcm16(s, n, lf.getChannels(), lf.getSampleRate(),
                            o.queuedBytes() + n * 2);
                    byte[] b = new byte[n * 2];
                    for (int i = 0; i < n; i++) {
                        short v = s[i];
                        b[2 * i] = (byte) v;
                        b[2 * i + 1] = (byte) (v >> 8);
                    }
                    o.write(b, 0, b.length);
                    bs.closeFrame();
                }
                if (!stop) {
                    AudioOut o = this.out;
                    if (o != null) o.drain();
                }
                if (!stop && end != null) { lastPositionMs = position(); end.run(); }
            } catch (Throwable e) {
                if (!stop) {
                    MusicPlayerMod.LOGGER.warn("Audio session failed", e);
                    Consumer<Throwable> cb = onError;
                    if (cb != null) cb.accept(e);
                }
            } finally {
                alive = false;
                AudioOut o = out;
                if (o != null) o.close();
                try { bs.close(); } catch (Exception ignored) {}
            }
        }
    }
}
