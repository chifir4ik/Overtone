package dev.musicplayer.audio;

import javax.sound.sampled.AudioFormat;

public final class FlacPlayer extends StreamPlayer {
    public FlacPlayer() { super(FlacPlayer::open, "musicplayer-audio-flac"); }

    private static Source open(byte[] raw, long startMs) throws Exception {
        FlacDecoder dec = new FlacDecoder(raw);
        if (startMs > 0) dec.seekToSample(startMs * dec.sampleRate / 1000);
        AudioFormat format = new AudioFormat(dec.sampleRate, 16, dec.outChannels, true, false);
        return new Source() {
            @Override public AudioFormat format() { return format; }
            @Override public int read(byte[] buf) throws Exception { return dec.read(buf); }
            @Override public void close() { }
        };
    }
}
