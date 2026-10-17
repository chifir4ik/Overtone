package dev.musicplayer.util;

/** Открытие ссылок и папок системными средствами, без зависимостей от API Minecraft. */
public final class Browser {
    private Browser() {}

    public static void open(String url) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
        launch(url, "rundll32", "url.dll,FileProtocolHandler");
    }

    /** true — проводник запущен. Путь нормализуется: игровая папка часто заканчивается на «\.». */
    public static boolean openFolder(java.nio.file.Path dir) {
        if (dir == null) return false;
        java.nio.file.Path abs = dir.toAbsolutePath().normalize();
        if (!java.nio.file.Files.isDirectory(abs)) return false;
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win") && launch(abs.toString(), "rundll32", "url.dll,FileProtocolHandler")) return true;
        if (os.contains("win")) return launch(abs.toString(), "explorer");
        return launch(abs.toString());
    }

    private static boolean launch(String target, String... windowsCommand) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win") && windowsCommand.length > 0) {
                String[] cmd = java.util.Arrays.copyOf(windowsCommand, windowsCommand.length + 1);
                cmd[cmd.length - 1] = target;
                new ProcessBuilder(cmd).start();
            } else if (os.contains("mac")) new ProcessBuilder("open", target).start();
            else new ProcessBuilder("xdg-open", target).start();
            return true;
        } catch (Exception e) {
            dev.musicplayer.MusicPlayerMod.LOGGER.warn("Could not open {}", target, e);
            return false;
        }
    }
}
