package dev.musicplayer.audio;

import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Устройство вывода, выбранное в настройках звука Minecraft.
 *
 * Ищем настройку по типу, а не по имени метода: Fabric в боевой сборке переименовывает
 * классы и методы Minecraft, поэтому строки вида {@code "soundDevice"} там не работают.
 * Настройка звука — единственная {@code OptionInstance<String>} в {@link Options}.
 */
final class MinecraftAudioDevice {
    private static boolean lookedUp;
    private static Method soundDevice;
    private static Method optionGet;

    private MinecraftAudioDevice() {}

    static String current() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.options == null) return null;
            ensureLookup(mc.options);
            if (soundDevice == null || optionGet == null) return null;
            Object option = soundDevice.invoke(mc.options);
            if (option == null) return null;
            Object value = optionGet.invoke(option);
            if (value instanceof String s && !s.isBlank()) return s;
        } catch (Throwable ignored) {
            // Настройки нет, мод загрузился раньше игры или изменилась сигнатура — просто default.
        }
        return null;
    }

    private static synchronized void ensureLookup(Options options) {
        if (lookedUp) return;
        lookedUp = true;
        try {
            optionGet = valueGetter();
            if (optionGet == null) return;
            for (Method m : Options.class.getMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (!OptionInstance.class.isAssignableFrom(m.getReturnType())) continue;
                try {
                    Object option = m.invoke(options);
                    if (option != null && optionGet.invoke(option) instanceof String) {
                        soundDevice = m;
                        return;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
            soundDevice = null;
            optionGet = null;
        }
    }

    /** {@code OptionInstance.get()} — единственный метод без аргументов, возвращающий Object. */
    private static Method valueGetter() {
        for (Method m : OptionInstance.class.getMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() == Object.class) return m;
        }
        return null;
    }
}
