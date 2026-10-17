package dev.musicplayer.audio;

import dev.musicplayer.util.T;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayInputStream;
import java.util.function.Consumer;

/** Проигрыватель MP3 из памяти: jlayer (декодер) + javax.sound (вывод). Пауза, перемотка, громкость. */
public final class Mp3Player implements AudioEngine {
    private byte[] data;
    private Runnable onEnd;
    private Session session;
    private volatile boolean paused;
    private volatile float volume = 0.8f;
    private volatile long lastPositionMs;
    private volatile Consumer<Throwable> onError;

    /** Сюда приходит причина, если звук не удалось открыть или поток оборвался (иначе плеер выглядел бы «на паузе»). */
    public void onError(Consumer<Throwable> c) { this.onError = c; }

    /**
     * Открывает аудиовыход: сначала системный по умолчанию, при неудаче перебирает все микшеры (на macOS часть устройств и форматов не открывается).
     */
    static SourceDataLine openLine(AudioFormat f) throws LineUnavailableException {
        int buf = Math.max(8192, (int) (f.getFrameSize() * f.getSampleRate() / 4));
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, f, buf);
        Throwable first = null;
        try {
            SourceDataLine l = (SourceDataLine) AudioSystem.getLine(info);
            l.open(f, buf);
            return l;
        } catch (Throwable e) { first = e; }

        StringBuilder names = new StringBuilder();
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            names.append(names.length() == 0 ? "" : "; ").append(mi.getName());
            try {
                Mixer m = AudioSystem.getMixer(mi);
                if (!m.isLineSupported(info)) continue;
                SourceDataLine l = (SourceDataLine) m.getLine(info);
                l.open(f, buf);
                dev.musicplayer.MusicPlayerMod.LOGGER.info("Audio: used mixer '{}'", mi.getName());
                return l;
            } catch (Throwable ignored) {}
        }
        throw new LineUnavailableException(T.get("err.no_audio_output", first, names.length() == 0 ? T.get("err.none_found") : names));
    }

    /** Громкость аппаратным регулятором линии. false — регулятора нет, сэмплы нужно масштабировать самим. */
    static boolean applyGain(SourceDataLine l, float volume) {
        try {
            if (!l.isControlSupported(FloatControl.Type.MASTER_GAIN)) return false;
            FloatControl c = (FloatControl) l.getControl(FloatControl.Type.MASTER_GAIN);
            float db = (float) (20.0 * Math.log10(Math.max(volume, 0.0001f)));
            c.setValue(Math.max(c.getMinimum(), Math.min(c.getMaximum(), db)));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

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
        if (s != null) s.applyVolume();
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
        try { SourceDataLine l = s.line; if (l != null) { l.stop(); l.flush(); } } catch (Exception ignored) {}
        session = null;
    }

    private final class Session implements Runnable {
        final byte[] mp3;
        final long baseTarget;
        final Runnable end;
        volatile boolean stop;
        volatile boolean alive = true;
        volatile SourceDataLine line;
        volatile long baseMs;
        volatile boolean softGain;     // у линии нет регулятора громкости — масштабируем сэмплы сами

        Session(byte[] mp3, long baseTarget, Runnable end) {
            this.mp3 = mp3; this.baseTarget = baseTarget; this.end = end; this.baseMs = baseTarget;
        }

        /** Через 2.5 с проверяем, что звук реально идёт: у открытого, но «мёртвого» выхода позиция стоит на нуле. */
        private void startWatchdog(SourceDataLine l) {
            Thread wd = new Thread(() -> {
                try { Thread.sleep(2500); } catch (InterruptedException e) { return; }
                if (stop || !alive || paused) return;
                long pos = l.getMicrosecondPosition();
                dev.musicplayer.MusicPlayerMod.LOGGER.info("Audio: after 2.5 s position={} us, active={}, running={}",
                        pos, l.isActive(), l.isRunning());
                if (pos <= 0) {
                    dev.musicplayer.MusicPlayerMod.LOGGER.warn("Audio: line is open but not playing");
                    Consumer<Throwable> cb = onError;
                    if (cb != null) cb.accept(new IllegalStateException(
                            T.get("err.silent_output")));
                }
            }, "musicplayer-audio-watchdog");
            wd.setDaemon(true);
            wd.start();
        }

        long position() {
            SourceDataLine l = line;
            return baseMs + (l == null ? 0 : l.getMicrosecondPosition() / 1000);
        }

        void applyVolume() {
            try {
                SourceDataLine l = line;
                if (l == null) return;
                if (l.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                    softGain = false;
                    FloatControl c = (FloatControl) l.getControl(FloatControl.Type.MASTER_GAIN);
                    float db = (float) (20.0 * Math.log10(Math.max(volume, 0.0001f)));
                    c.setValue(Math.max(c.getMinimum(), Math.min(c.getMaximum(), db)));
                } else {
                    softGain = true;
                }
            } catch (Exception ignored) {}
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
                        skipped += h.ms_per_frame();
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
                    if (line == null) {
                        AudioFormat f = new AudioFormat(dec.getOutputFrequency(), 16, dec.getOutputChannels(), true, false);
                        SourceDataLine l = openLine(f);
                        line = l;
                        applyVolume();
                        l.start();
                        dev.musicplayer.MusicPlayerMod.LOGGER.info("Audio: line opened {} Hz, {} ch, buffer {} bytes, {}, gainControl={}",
                                f.getSampleRate(), f.getChannels(), l.getBufferSize(), l.getClass().getName(), !softGain);
                        startWatchdog(l);
                    }
                    while (paused && !stop) {
                        if (line.isRunning()) line.stop();
                        Thread.sleep(30);
                    }
                    if (stop) break;
                    if (!line.isRunning()) line.start();

                    short[] s = out.getBuffer();
                    int n = out.getBufferLength();
                    byte[] b = new byte[n * 2];
                    float gain = softGain ? volume : 1f;
                    for (int i = 0; i < n; i++) {
                        short v = gain == 1f ? s[i] : (short) (s[i] * gain);
                        b[2 * i] = (byte) v;
                        b[2 * i + 1] = (byte) (v >> 8);
                    }
                    line.write(b, 0, b.length);
                    bs.closeFrame();
                }
                if (!stop && line != null) line.drain();
                if (!stop && end != null) { lastPositionMs = position(); end.run(); }
            } catch (Throwable e) {
                if (!stop) {
                    dev.musicplayer.MusicPlayerMod.LOGGER.warn("Audio session failed", e);
                    Consumer<Throwable> cb = onError;
                    if (cb != null) cb.accept(e);
                }
            } finally {
                alive = false;
                try { if (line != null) { line.stop(); line.close(); } } catch (Exception ignored) {}
                try { bs.close(); } catch (Exception ignored) {}
            }
        }
    }
}
