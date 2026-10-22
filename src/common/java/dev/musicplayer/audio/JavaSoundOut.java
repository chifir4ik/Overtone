package dev.musicplayer.audio;

import dev.musicplayer.MusicPlayerMod;
import dev.musicplayer.util.T;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import java.util.ArrayList;
import java.util.List;

/**
 * Запасной вывод через Java Sound. На Linux JDK видит только ALSA-узлы и обходит PipeWire,
 * поэтому этот путь используется лишь если OpenAL недоступен.
 */
final class JavaSoundOut implements AudioOut {
    private final AudioFormat format;
    private final SourceDataLine line;
    private volatile float volume = 0.8f;
    private volatile boolean softGain;

    private JavaSoundOut(AudioFormat format, SourceDataLine line) {
        this.format = format;
        this.line = line;
        applyVolume();
        MusicPlayerMod.LOGGER.info("Audio: Java Sound line opened {} Hz, {} ch, buffer {} bytes, {}, gainControl={}",
                format.getSampleRate(), format.getChannels(), line.getBufferSize(), line.getClass().getName(),
                !softGain);
    }

    static AudioOut open(AudioFormat f, String wanted) throws LineUnavailableException {
        int buf = Math.max(8192, (int) (f.getFrameSize() * f.getSampleRate() / 4));
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, f, buf);
        Throwable first = null;

        if (wanted != null && !wanted.isBlank()) {
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                if (!mi.getName().equals(wanted)) continue;
                SourceDataLine l = tryOpen(mi, info, f, buf);
                if (l != null) {
                    MusicPlayerMod.LOGGER.info("Audio: used Java Sound mixer '{}'", mi.getName());
                    return new JavaSoundOut(f, l);
                }
            }
            MusicPlayerMod.LOGGER.warn("Audio: Java Sound device '{}' is unavailable, using the system default", wanted);
        }
        try {
            SourceDataLine l = (SourceDataLine) AudioSystem.getLine(info);
            l.open(f, buf);
            return new JavaSoundOut(f, l);
        } catch (Throwable e) {
            first = e;
        }

        StringBuilder names = new StringBuilder();
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            names.append(names.length() == 0 ? "" : "; ").append(mi.getName());
            SourceDataLine l = tryOpen(mi, info, f, buf);
            if (l != null) {
                MusicPlayerMod.LOGGER.info("Audio: used Java Sound mixer '{}'", mi.getName());
                return new JavaSoundOut(f, l);
            }
        }
        throw new LineUnavailableException(T.get("err.no_audio_output", first,
                names.length() == 0 ? T.get("err.none_found") : names));
    }

    private static SourceDataLine tryOpen(Mixer.Info mi, DataLine.Info info, AudioFormat f, int buf) {
        try {
            Mixer m = AudioSystem.getMixer(mi);
            if (!m.isLineSupported(info)) return null;
            SourceDataLine l = (SourceDataLine) m.getLine(info);
            l.open(f, buf);
            return l;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Устройства, на которые можно вывести обычный стерео-PCM. */
    static List<String> devices() {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, new AudioFormat(44100f, 16, 2, true, false));
        List<String> out = new ArrayList<>();
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            try {
                if (AudioSystem.getMixer(mi).isLineSupported(info) && !out.contains(mi.getName())) out.add(mi.getName());
            } catch (Throwable ignored) {}
        }
        return out;
    }

    @Override public AudioFormat format() { return format; }

    @Override public void start() { line.start(); }

    @Override public void pause() { line.stop(); }

    @Override public void resume() { line.start(); }

    @Override public boolean isRunning() { return line.isRunning(); }

    @Override public void write(byte[] pcm, int off, int len) {
        if (softGain) scale(pcm, off, len, volume);
        line.write(pcm, off, len);
    }

    @Override public void drain() { line.drain(); }

    @Override public void abort() {
        try { line.stop(); line.flush(); } catch (Exception ignored) {}
    }

    @Override public long microsecondPosition() { return line.getMicrosecondPosition(); }

    @Override public int queuedBytes() { return Math.max(0, line.getBufferSize() - line.available()); }

    @Override public void setVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
        applyVolume();
    }

    @Override public void close() {
        try { line.stop(); line.flush(); } catch (Exception ignored) {}
        try { line.close(); } catch (Exception ignored) {}
    }

    private void applyVolume() {
        try {
            if (!line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                softGain = true;
                return;
            }
            FloatControl c = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
            float db = (float) (20.0 * Math.log10(Math.max(volume, 0.0001f)));
            c.setValue(Math.max(c.getMinimum(), Math.min(c.getMaximum(), db)));
            softGain = false;
        } catch (Exception e) {
            softGain = true;
        }
    }

    private static void scale(byte[] b, int off, int len, float gain) {
        for (int i = off; i + 1 < off + len; i += 2) {
            short v = (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
            v = (short) (v * gain);
            b[i] = (byte) v;
            b[i + 1] = (byte) (v >> 8);
        }
    }
}
