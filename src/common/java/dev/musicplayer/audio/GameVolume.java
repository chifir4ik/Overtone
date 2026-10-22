package dev.musicplayer.audio;

import dev.musicplayer.config.PlayerConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

/** Множитель громкости музыки от состояния игры. Всё, что зависит от API настроек звука Minecraft, — только здесь. */
public final class GameVolume {
    private GameVolume() {}

    public static float factor() {
        PlayerConfig pc = PlayerConfig.get();
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return 1f;
        if (pc.duckWhenInactive && (!mc.isWindowActive() || mc.isPaused())) return 0f;
        if (pc.useGameVolume && mc.options != null) {
            return mc.options.getSoundSourceVolume(SoundSource.MASTER) * mc.options.getSoundSourceVolume(SoundSource.MUSIC);
        }
        return 1f;
    }
}
