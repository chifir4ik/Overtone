package dev.musicplayer.audio;

import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.aac.SampleBuffer;

import javax.sound.sampled.AudioFormat;

/** AAC в MP4, в том числе фрагментированном (так отдаёт HLS SoundCloud). */
public final class AacPlayer extends StreamPlayer {
    public AacPlayer() { super(AacPlayer::open, "musicplayer-audio-aac"); }

    private static Source open(byte[] raw, long startMs) throws Exception {
        Mp4Audio mp4 = Mp4Audio.parse(raw);
        return new AacSource(mp4, startMs);
    }

    private static final class AacSource implements Source {
        private final Mp4Audio mp4;
        private final Decoder decoder;
        private final SampleBuffer pcm = new SampleBuffer();
        private int next;
        private boolean primed;
        private byte[] pending = new byte[0];
        private int pendingAt;
        private AudioFormat format;

        AacSource(Mp4Audio mp4, long startMs) throws Exception {
            this.mp4 = mp4;
            this.decoder = new Decoder(mp4.decoderConfig());
            // Один кадр до нужного места нужен декодеру для перекрытия окон, его звук отбрасывается.
            int target = mp4.indexAt(startMs);
            next = Math.max(0, target - 1);
            primed = next == target;
            decodeNext();
            format = new AudioFormat(pcm.getSampleRate(), 16, pcm.getChannels(), true, false);
        }

        @Override public AudioFormat format() { return format; }

        @Override public int read(byte[] buf) throws Exception {
            while (pendingAt >= pending.length) {
                if (!decodeNext()) return -1;
            }
            int n = Math.min(buf.length, pending.length - pendingAt);
            System.arraycopy(pending, pendingAt, buf, 0, n);
            pendingAt += n;
            return n;
        }

        private boolean decodeNext() throws Exception {
            while (next < mp4.count()) {
                byte[] frame = mp4.frame(next++);
                try {
                    decoder.decodeFrame(frame, pcm);
                } catch (Exception e) {
                    continue;
                }
                byte[] out = pcm.getData();
                if (out.length == 0) continue;
                if (!primed) { primed = true; continue; }
                pending = toLittleEndian(out);
                pendingAt = 0;
                return true;
            }
            pending = new byte[0];
            pendingAt = 0;
            return false;
        }

        /** Декодер всегда отдаёт 16-битные сэмплы старшим байтом вперёд, а линия вывода ждёт младшим. */
        private static byte[] toLittleEndian(byte[] be) {
            byte[] le = new byte[be.length & ~1];
            for (int i = 0; i + 1 < le.length; i += 2) {
                le[i] = be[i + 1];
                le[i + 1] = be[i];
            }
            return le;
        }

        @Override public void close() { }
    }
}
