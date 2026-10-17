package dev.musicplayer.util;

import net.minecraft.network.chat.Component;

/** Короткий доступ к переводам мода. Язык берётся из настроек Minecraft, недостающие ключи падают на en_us. */
public final class T {
    private T() {}

    public static String get(String key, Object... args) {
        return Component.translatable("overtone." + key, args).getString();
    }

    public static Component component(String key, Object... args) {
        return Component.translatable("overtone." + key, args);
    }

    /** «5 треков» с правильной формой множественного числа (формы задаются в языковых файлах). */
    public static String tracks(int n) {
        int m100 = n % 100, m10 = n % 10;
        String form = m100 >= 11 && m100 <= 14 ? "many" : m10 == 1 ? "one" : m10 >= 2 && m10 <= 4 ? "few" : "many";
        return get("local.count." + form, n);
    }
}
