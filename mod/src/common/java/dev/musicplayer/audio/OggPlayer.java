package dev.musicplayer.audio;

import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/** stb_vorbis уже есть в игре вместе с LWJGL, отдельная библиотека не нужна. */
public final class OggPlayer extends StreamPlayer {
    private static final int CHUNK = 4096;

    public OggPlayer() { super(OggPlayer::open, "musicplayer-audio-ogg"); }

    private static Source open(byte[] raw, long startMs) throws Exception {
        ByteBuffer mem = MemoryUtil.memAlloc(raw.length);
        mem.put(raw).flip();
        long handle;
        int channels, rate;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            handle = STBVorbis.stb_vorbis_open_memory(mem, err, null);
            if (handle == MemoryUtil.NULL) {
                MemoryUtil.memFree(mem);
                throw new IOException("Ogg Vorbis: cannot open (error " + err.get(0) + ")");
            }
            STBVorbisInfo info = STBVorbisInfo.malloc(stack);
            STBVorbis.stb_vorbis_get_info(handle, info);
            channels = info.channels();
            rate = info.sample_rate();
        }
        int out = Math.min(2, Math.max(1, channels));
        if (startMs > 0) STBVorbis.stb_vorbis_seek(handle, (int) Math.min(Integer.MAX_VALUE, startMs * rate / 1000));
        ShortBuffer pcm = MemoryUtil.memAllocShort(CHUNK * out);
        AudioFormat format = new AudioFormat(rate, 16, out, true, false);
        long h = handle;
        return new Source() {
            private boolean closed;

            @Override public AudioFormat format() { return format; }

            @Override public int read(byte[] buf) {
                int frames = Math.min(CHUNK, buf.length / (2 * out));
                pcm.clear();
                pcm.limit(frames * out);
                int got = STBVorbis.stb_vorbis_get_samples_short_interleaved(h, out, pcm);
                if (got <= 0) return -1;
                int n = got * out;
                for (int i = 0; i < n; i++) {
                    short v = pcm.get(i);
                    buf[i * 2] = (byte) v;
                    buf[i * 2 + 1] = (byte) (v >> 8);
                }
                return n * 2;
            }

            @Override public synchronized void close() {
                if (closed) return;
                closed = true;
                STBVorbis.stb_vorbis_close(h);
                MemoryUtil.memFree(pcm);
                MemoryUtil.memFree(mem);
            }
        };
    }

    public static long durationMs(byte[] raw) {
        ByteBuffer mem = MemoryUtil.memAlloc(raw.length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            mem.put(raw).flip();
            IntBuffer err = stack.mallocInt(1);
            long h = STBVorbis.stb_vorbis_open_memory(mem, err, null);
            if (h == MemoryUtil.NULL) return 0;
            try {
                STBVorbisInfo info = STBVorbisInfo.malloc(stack);
                STBVorbis.stb_vorbis_get_info(h, info);
                int samples = STBVorbis.stb_vorbis_stream_length_in_samples(h);
                return info.sample_rate() <= 0 ? 0 : samples * 1000L / info.sample_rate();
            } finally {
                STBVorbis.stb_vorbis_close(h);
            }
        } finally {
            MemoryUtil.memFree(mem);
        }
    }
}
