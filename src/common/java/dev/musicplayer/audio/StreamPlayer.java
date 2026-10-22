package dev.musicplayer.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.SourceDataLine;
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
    private Session session;
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
        if (s != null) s.applyVolume();
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
        try { SourceDataLine l = s.line; if (l != null) { l.stop(); l.flush(); } } catch (Exception ignored) {}
        session = null;
    }

    private final class Session implements Runnable {
        final byte[] raw;
        final long startMs;
        final Runnable end;
        volatile boolean stop;
        volatile boolean alive = true;
        volatile SourceDataLine line;
        volatile boolean softGain;

        Session(byte[] raw, long startMs, Runnable end) { this.raw = raw; this.startMs = startMs; this.end = end; }

        long position() {
            SourceDataLine l = line;
            return startMs + (l == null ? 0 : l.getMicrosecondPosition() / 1000);
        }

        void applyVolume() {
            SourceDataLine l = line;
            if (l != null) softGain = !Mp3Player.applyGain(l, volume);
        }

        @Override public void run() {
            try (Source src = opener.open(raw, startMs)) {
                AudioFormat format = src.format();
                SourceDataLine l = Mp3Player.openLine(format);
                line = l;
                applyVolume();
                l.start();
                pump(src, l, format.getFrameSize());
                if (!stop) l.drain();
                if (!stop && end != null) { lastPositionMs = position(); end.run(); }
            } catch (Throwable e) {
                if (!stop) {
                    dev.musicplayer.MusicPlayerMod.LOGGER.warn("Audio session failed", e);
                    Consumer<Throwable> cb = onError;
                    if (cb != null) cb.accept(e);
                }
            } finally {
                alive = false;
                try { SourceDataLine l = line; if (l != null) { l.stop(); l.close(); } } catch (Exception ignored) {}
            }
        }

        private void pump(Source src, SourceDataLine l, int frameSize) throws Exception {
            byte[] buf = new byte[frameSize * 2048];
            int n;
            while (!stop && (n = src.read(buf)) > 0) {
                while (paused && !stop) {
                    if (l.isRunning()) l.stop();
                    Thread.sleep(30);
                }
                if (stop) break;
                if (!l.isRunning()) l.start();
                AudioFormat lf = l.getFormat();
                dev.musicplayer.viz.Spectrum.feedPcm16(buf, n, lf.getChannels(), lf.getSampleRate(),
                        l.getBufferSize() - l.available() + n);
                if (softGain) scale(buf, n, volume);
                l.write(buf, 0, n);
            }
        }

        private void scale(byte[] b, int n, float gain) {
            for (int i = 0; i + 1 < n; i += 2) {
                short v = (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
                v = (short) (v * gain);
                b[i] = (byte) v;
                b[i + 1] = (byte) (v >> 8);
            }
        }
    }
}
