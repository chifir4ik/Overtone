package dev.musicplayer.util;

/** Открытие ссылки в системном браузере без зависимостей от API Minecraft. */
public final class Browser {
    private Browser() {}

    public static void open(String url) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            else if (os.contains("mac")) new ProcessBuilder("open", url).start();
            else new ProcessBuilder("xdg-open", url).start();
        } catch (Exception ignored) {
            // не страшно: ссылку экран ещё и копирует в буфер обмена
        }
    }
}
