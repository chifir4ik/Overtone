package dev.musicplayer.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/** WAV / AIFF / AU средствами javax.sound: то, что умеет сама Java, без сторонних декодеров. */
public final class PcmPlayer extends StreamPlayer {
    public PcmPlayer() { super(PcmPlayer::open, "musicplayer-audio-pcm"); }

    private static Source open(byte[] raw, long startMs) throws Exception {
        AudioInputStream source = AudioSystem.getAudioInputStream(new ByteArrayInputStream(raw));
        AudioFormat base = source.getFormat();
        AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, base.getSampleRate(), 16,
                base.getChannels(), base.getChannels() * 2, base.getSampleRate(), false);
        AudioInputStream in = base.matches(pcm) ? source : AudioSystem.getAudioInputStream(pcm, source);

        long left = (long) (startMs * pcm.getFrameRate() / 1000.0) * pcm.getFrameSize();
        while (left > 0) {
            long n = in.skip(left);
            if (n <= 0) break;
            left -= n;
        }

        return new Source() {
            @Override public AudioFormat format() { return pcm; }
            @Override public int read(byte[] buf) throws IOException { return in.read(buf); }
            @Override public void close() {
                try { in.close(); source.close(); } catch (IOException ignored) {}
            }
        };
    }
}
