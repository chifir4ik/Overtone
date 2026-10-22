package dev.musicplayer.audio;

import javax.sound.sampled.AudioFormat;
import java.util.function.Consumer;

/** Общая часть проигрывателей, которые декодируют трек в PCM сами: поток, пауза, перемотка, громкость. */
public class StreamPlayer implements AudioEngine {
    public interface Source extends AutoCloseable {
        AudioFormat format();
        /** Заполняет буфер PCM 16 бит little-endian. -1 — трек закончился. */
        int read(byte[] buf) throws Exception;
        @Override void close();
    }

    public interface Opener {
        Source open(byte[] data, long startMs) throws Exception;
    }

    private final Opener opener;
    private final String threadName;
    private byte[] data;
    private Runnable onEnd;
    private volatile Session session;
    private volatile boolean paused;
    private volatile float volume = 0.8f;
    private volatile long lastPositionMs;
    private volatile Consumer<Throwable> onError;

    protected StreamPlayer(Opener opener, String threadName) {
        this.opener = opener;
        this.threadName = threadName;
    }

    @Override public void onError(Consumer<Throwable> c) { this.onError = c; }

    @Override public synchronized void play(byte[] encoded, long startMs, Runnable onEnd) {
        stop();
        this.data = encoded;
        this.onEnd = onEnd;
        this.paused = false;
        start(startMs);
    }

    @Override public synchronized void seek(long ms) {
        if (data == null) return;
        stopSession();
        start(Math.max(0, ms));
    }

    @Override public synchronized void stop() {
        stopSession();
        data = null;
        lastPositionMs = 0;
    }

    @Override public void pause()  { paused = true; }
    @Override public void resume() { paused = false; }

    @Override public void setVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
        Session s = session;
        if (s != null) s.setVolume(volume);
    }

    @Override public boolean isPlaying() {
        Session s = session;
        return s != null && s.alive && !paused;
    }

    @Override public long positionMs() {
        Session s = session;
        return s == null ? lastPositionMs : s.position();
    }

    private void start(long startMs) {
        Session s = new Session(data, startMs, onEnd);
        session = s;
        Thread t = new Thread(s, threadName);
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
        final byte[] raw;
        final long startMs;
        final Runnable end;
        volatile boolean stop;
        volatile boolean alive = true;
        volatile AudioOut out;

        Session(byte[] raw, long startMs, Runnable end) { this.raw = raw; this.startMs = startMs; this.end = end; }

        long position() {
            AudioOut o = out;
            return startMs + (o == null ? 0 : o.microsecondPosition() / 1000);
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
            try (Source src = opener.open(raw, startMs)) {
                AudioFormat format = src.format();
                AudioOut o = AudioOuts.open(format);
                out = o;
                o.setVolume(volume);
                o.start();
                pump(src, o, format.getFrameSize());
                if (!stop) o.drain();
                if (!stop && end != null) { lastPositionMs = position(); end.run(); }
            } catch (Throwable e) {
                if (!stop) {
                    dev.musicplayer.MusicPlayerMod.LOGGER.warn("Audio session failed", e);
                    Consumer<Throwable> cb = onError;
                    if (cb != null) cb.accept(e);
                }
            } finally {
                alive = false;
                AudioOut o = out;
                if (o != null) o.close();
            }
        }

        private void pump(Source src, AudioOut o, int frameSize) throws Exception {
            byte[] buf = new byte[frameSize * 2048];
            int n;
            while (!stop && (n = src.read(buf)) > 0) {
                while (paused && !stop) {
                    if (o.isRunning()) o.pause();
                    Thread.sleep(30);
                }
                if (stop) break;
                if (!o.isRunning()) o.resume();
                AudioFormat lf = o.format();
                dev.musicplayer.viz.Spectrum.feedPcm16(buf, n, lf.getChannels(), lf.getSampleRate(),
                        o.queuedBytes() + n);
                o.write(buf, 0, n);
            }
        }
    }
}
