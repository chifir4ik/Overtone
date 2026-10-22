package dev.musicplayer.audio;

import dev.musicplayer.MusicPlayerMod;
import dev.musicplayer.config.PlayerConfig;
import dev.musicplayer.util.T;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.LineUnavailableException;
import java.util.List;

/** Выбор аудиовыхода: сначала OpenAL (как Minecraft), затем Java Sound. */
public final class AudioOuts {
    private AudioOuts() {}

    public static AudioOut open(AudioFormat format) throws Exception {
        String wanted = PlayerConfig.get().audioDevice;
        Throwable openAlError = null;

        if (OpenAlOut.isSupported()) {
            try {
                return OpenAlOut.open(format, wanted);
            } catch (Throwable e) {
                openAlError = e;
                MusicPlayerMod.LOGGER.warn("Audio: OpenAL output unavailable ({}), falling back to Java Sound",
                        e.toString());
            }
        }

        try {
            return JavaSoundOut.open(format, wanted);
        } catch (Throwable e) {
            String names = String.join("; ", devices());
            LineUnavailableException out = new LineUnavailableException(
                    T.get("err.no_audio_output", e, names.isEmpty() ? T.get("err.none_found") : names));
            out.initCause(e);
            if (openAlError != null) out.addSuppressed(openAlError);
            throw out;
        }
    }

    /** Устройства текущего backend'а. При OpenAL это нормальные имена PipeWire/PulseAudio. */
    public static List<String> devices() {
        if (OpenAlOut.isSupported()) {
            try {
                return OpenAlOut.devices();
            } catch (Throwable e) {
                MusicPlayerMod.LOGGER.warn("Audio: cannot enumerate OpenAL devices: {}", e.toString());
            }
        }
        return JavaSoundOut.devices();
    }
}
