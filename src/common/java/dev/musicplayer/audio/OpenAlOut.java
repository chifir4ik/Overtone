package dev.musicplayer.audio;

import dev.musicplayer.MusicPlayerMod;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.ALUtil;
import org.lwjgl.openal.EXTThreadLocalContext;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Вывод через OpenAL — тот же API и устройство, что использует Minecraft.
 *
 * Контекст создаётся отдельный и делается текущим только для аудиопотока мода
 * (ALC_EXT_thread_local_context), поэтому звук игры не затрагивается.
 */
final class OpenAlOut implements AudioOut {
    private static final int QUEUE = 8;

    private final AudioFormat format;
    private final int alFormat;
    private final int frameSize;
    private final int sampleRate;
    private final int capacityFrames;
    private final long device;
    private final long context;
    private final int source;
    private final int[] bufferIds = new int[QUEUE];
    private final int[] bufferFrames = new int[QUEUE];
    private final ByteBuffer[] bufferMemory = new ByteBuffer[QUEUE];
    private final boolean[] bufferQueued = new boolean[QUEUE];
    private final ArrayDeque<Integer> queue = new ArrayDeque<>(QUEUE);
    private final int[] oneBuffer = new int[1];

    private volatile boolean aborted;
    private volatile boolean running;
    private volatile boolean closed;
    private volatile float volume = 0.8f;
    private volatile long positionUs;
    private volatile int queuedBytes;
    private float appliedVolume = -1f;
    private long playedFrames;

    private OpenAlOut(AudioFormat f, String requested) throws Exception {
        if (f.getSampleSizeInBits() != 16
                || !AudioFormat.Encoding.PCM_SIGNED.equals(f.getEncoding())
                || f.isBigEndian()) {
            throw new IllegalArgumentException("OpenAL output needs signed 16-bit little-endian PCM");
        }
        int channels = f.getChannels();
        if (channels == 1) {
            alFormat = AL10.AL_FORMAT_MONO16;
        } else if (channels == 2) {
            alFormat = AL10.AL_FORMAT_STEREO16;
        } else {
            throw new IllegalArgumentException("OpenAL output supports mono/stereo only: " + channels);
        }
        if (f.getSampleRate() <= 0) throw new IllegalArgumentException("Bad sample rate: " + f.getSampleRate());

        this.format = f;
        this.frameSize = f.getFrameSize();
        this.sampleRate = Math.round(f.getSampleRate());
        this.capacityFrames = Math.max(1024, sampleRate / 10);

        long dev = 0L;
        long ctx = 0L;
        int src = 0;
        boolean alReady = false;
        try {
            dev = ALC10.alcOpenDevice((CharSequence) requested);
            if (dev == 0L) {
                throw new IllegalStateException("ALC could not open device '" + name(requested) + "'");
            }
            ctx = ALC10.alcCreateContext(dev, (int[]) null);
            if (ctx == 0L) {
                throw new IllegalStateException("ALC could not create context for '" + name(requested) + "'");
            }
            if (!EXTThreadLocalContext.alcSetThreadContext(ctx)) {
                throw new IllegalStateException("ALC could not make context current");
            }

            ALCCapabilities alcCaps = ALC.createCapabilities(dev);
            AL.createCapabilities(alcCaps);
            alReady = true;

            src = AL10.alGenSources();
            checkAl("alGenSources");
            if (src == 0) throw new IllegalStateException("alGenSources returned 0");
            for (int i = 0; i < QUEUE; i++) {
                bufferIds[i] = AL10.alGenBuffers();
                if (bufferIds[i] == 0) throw new IllegalStateException("alGenBuffers returned 0");
                bufferMemory[i] = MemoryUtil.memAlloc(capacityFrames * frameSize);
            }

            AL10.alSourcei(src, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(src, AL10.AL_POSITION, 0f, 0f, 0f);
            AL10.alSource3f(src, AL10.AL_VELOCITY, 0f, 0f, 0f);
            AL10.alSourcef(src, AL10.AL_ROLLOFF_FACTOR, 0f);
            AL10.alSourcef(src, AL10.AL_GAIN, volume);
            checkAl("OpenAL source setup");
            appliedVolume = volume;
        } catch (Throwable t) {
            cleanup(dev, ctx, src, alReady);
            throw asException(t);
        }

        this.device = dev;
        this.context = ctx;
        this.source = src;

        String actual = null;
        try { actual = ALC10.alcGetString(dev, ALC11.ALC_ALL_DEVICES_SPECIFIER); } catch (Throwable ignored) {}
        String actualName = actual != null && !actual.isBlank() ? actual : name(requested);
        MusicPlayerMod.LOGGER.info("Audio: OpenAL device '{}' opened ({} Hz, {} ch, {} buffers x {} frames)",
                actualName, sampleRate, channels, QUEUE, capacityFrames);
    }

    static boolean isSupported() {
        try {
            return ALC10.alcIsExtensionPresent(0L, "ALC_EXT_thread_local_context");
        } catch (Throwable t) {
            return false;
        }
    }

    static List<String> devices() {
        List<String> out = new ArrayList<>();
        try {
            if (!ALC10.alcIsExtensionPresent(0L, "ALC_ENUMERATE_ALL_EXT")) return out;
            List<String> all = ALUtil.getStringList(0L, ALC11.ALC_ALL_DEVICES_SPECIFIER);
            if (all != null) {
                for (String s : all) {
                    if (s != null && !s.isBlank() && !out.contains(s)) out.add(s);
                }
            }
            String def = ALC10.alcGetString(0L, ALC11.ALC_DEFAULT_ALL_DEVICES_SPECIFIER);
            if (def != null && !def.isBlank() && !out.contains(def)) out.add(0, def);
        } catch (Throwable ignored) {}
        return out;
    }

    static AudioOut open(AudioFormat format, String wanted) throws Exception {
        String target = wanted == null ? "" : wanted.trim();
        if (target.isEmpty()) target = MinecraftAudioDevice.current();
        try {
            return new OpenAlOut(format, target);
        } catch (Throwable first) {
            if (target != null && !target.isBlank()) {
                MusicPlayerMod.LOGGER.warn("Audio: OpenAL device '{}' is unavailable ({}), using the default",
                        target, first.toString());
                try {
                    return new OpenAlOut(format, null);
                } catch (Throwable second) {
                    second.addSuppressed(first);
                    throw asException(second);
                }
            }
            throw asException(first);
        }
    }

    @Override public AudioFormat format() { return format; }

    @Override public void start() {
        // OpenAL стартует, когда в очередь попадает первый буфер.
    }

    @Override public void pause() {
        if (closed) return;
        AL10.alSourcePause(source);
        updatePosition();
        updateRunning();
    }

    @Override public void resume() {
        if (closed) return;
        applyVolume();
        AL10.alSourcePlay(source);
        updateRunning();
        updatePosition();
    }

    @Override public boolean isRunning() { return running; }

    @Override public void write(byte[] pcm, int off, int len) throws Exception {
        if (closed || aborted) throw new IOException("OpenAL output stopped");
        int usable = len - Math.floorMod(len, frameSize);
        int end = off + usable;
        int pos = off;
        while (pos < end) {
            int slot = acquireSlot();
            if (aborted || closed) throw new IOException("OpenAL output stopped");
            int frames = Math.min(capacityFrames, (end - pos) / frameSize);
            ByteBuffer mem = bufferMemory[slot];
            mem.clear();
            mem.put(pcm, pos, frames * frameSize);
            mem.flip();

            applyVolume();
            AL10.alBufferData(bufferIds[slot], alFormat, mem, sampleRate);
            checkAl("alBufferData");
            AL10.alSourceQueueBuffers(source, bufferIds[slot]);
            checkAl("alSourceQueueBuffers");

            bufferFrames[slot] = frames;
            bufferQueued[slot] = true;
            queue.addLast(slot);
            queuedBytes += frames * frameSize;
            pos += frames * frameSize;

            int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
            if (state != AL10.AL_PLAYING && state != AL10.AL_PAUSED) {
                AL10.alSourcePlay(source);
                checkAl("alSourcePlay");
            }
            updateRunning();
            updatePosition();
        }
    }

    @Override public void drain() throws Exception {
        while (!queue.isEmpty()) {
            recycle();
            if (aborted || closed) return;
            if (!queue.isEmpty()) {
                int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
                if (state == AL10.AL_STOPPED) AL10.alSourcePlay(source);
                Thread.sleep(5);
            }
        }
        updatePosition();
    }

    @Override public void abort() {
        aborted = true;
        running = false;
    }

    @Override public long microsecondPosition() { return positionUs; }

    @Override public int queuedBytes() { return queuedBytes; }

    @Override public void setVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        aborted = true;
        running = false;
        if (source != 0) {
            try { AL10.alSourceStop(source); } catch (Throwable ignored) {}
            try { AL10.alDeleteSources(source); } catch (Throwable ignored) {}
        }
        for (int id : bufferIds) {
            if (id == 0) continue;
            try { AL10.alDeleteBuffers(id); } catch (Throwable ignored) {}
        }
        for (ByteBuffer mem : bufferMemory) {
            if (mem != null) MemoryUtil.memFree(mem);
        }
        try { EXTThreadLocalContext.alcSetThreadContext(0L); } catch (Throwable ignored) {}
        try { ALC10.alcDestroyContext(context); } catch (Throwable ignored) {}
        try { ALC10.alcCloseDevice(device); } catch (Throwable ignored) {}
    }

    private int acquireSlot() throws Exception {
        while (true) {
            recycle();
            for (int i = 0; i < QUEUE; i++) {
                if (!bufferQueued[i]) return i;
            }
            if (aborted || closed) throw new IOException("OpenAL output stopped");
            Thread.sleep(5);
        }
    }

    private void recycle() {
        int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        if (processed <= 0) return;
        if (processed > queue.size()) processed = queue.size();
        for (int i = 0; i < processed; i++) {
            int slot = queue.removeFirst();
            oneBuffer[0] = bufferIds[slot];
            AL10.alSourceUnqueueBuffers(source, oneBuffer);
            playedFrames += bufferFrames[slot];
            queuedBytes = Math.max(0, queuedBytes - bufferFrames[slot] * frameSize);
            bufferQueued[slot] = false;
        }
        updateRunning();
        updatePosition();
    }

    private void updateRunning() {
        int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
        running = state == AL10.AL_PLAYING;
    }

    private void updatePosition() {
        int offset = AL10.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET);
        if (offset < 0) offset = 0;
        positionUs = (playedFrames + offset) * 1_000_000L / sampleRate;
    }

    private void applyVolume() {
        if (Math.abs(volume - appliedVolume) < 0.0005f) return;
        float v = Math.max(0f, Math.min(1f, volume));
        AL10.alSourcef(source, AL10.AL_GAIN, v);
        appliedVolume = v;
    }

    private void cleanup(long dev, long ctx, int src, boolean alReady) {
        if (alReady) {
            if (src != 0) {
                try { AL10.alDeleteSources(src); } catch (Throwable ignored) {}
            }
            for (int id : bufferIds) {
                if (id == 0) continue;
                try { AL10.alDeleteBuffers(id); } catch (Throwable ignored) {}
            }
        }
        for (ByteBuffer mem : bufferMemory) {
            if (mem != null) MemoryUtil.memFree(mem);
        }
        try { EXTThreadLocalContext.alcSetThreadContext(0L); } catch (Throwable ignored) {}
        if (ctx != 0L) {
            try { ALC10.alcDestroyContext(ctx); } catch (Throwable ignored) {}
        }
        if (dev != 0L) {
            try { ALC10.alcCloseDevice(dev); } catch (Throwable ignored) {}
        }
    }

    private static void checkAl(String op) {
        int err = AL10.alGetError();
        if (err != AL10.AL_NO_ERROR) throw new IllegalStateException(op + " failed, AL error " + err);
    }

    private static String name(String requested) {
        return requested == null || requested.isBlank() ? "default" : requested;
    }

    private static Exception asException(Throwable t) {
        if (t instanceof Exception e) return e;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }
}
