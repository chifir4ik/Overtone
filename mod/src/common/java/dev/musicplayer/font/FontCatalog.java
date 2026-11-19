package dev.musicplayer.font;

import dev.musicplayer.Env;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Каталог шрифтов: своя папка мода и системные шрифты. Файлы читаются руками (таблицы name и cmap),
 * без AWT, чтобы работало и там, где его нет, и чтобы знать, есть ли в шрифте кириллица.
 */
public final class FontCatalog {
    private FontCatalog() {}

    public enum Source { SYSTEM, FOLDER }

    public record Entry(String id, String family, String style, Path file, Source source, boolean cyrillic, boolean latin) {
        public String title() {
            return style.isEmpty() || style.equalsIgnoreCase("Regular") || style.equalsIgnoreCase("Book") || style.equalsIgnoreCase("Roman") || style.equalsIgnoreCase("Normal") ? family : family + " " + style;
        }
    }

    private static volatile List<Entry> entries = List.of();
    private static volatile boolean scanning, scanned;

    /** Сюда пользователь кладёт свои .ttf / .otf. */
    public static Path folder() { return Env.configDir().resolve("overtone-fonts"); }

    public static List<Entry> entries() { return entries; }

    public static boolean scanning() { return scanning; }

    public static boolean scanned() { return scanned; }

    public static Entry find(String id) {
        if (id == null || id.isEmpty()) return null;
        for (Entry e : entries) if (e.id().equals(id)) return e;
        return null;
    }

    /** Запускает поиск в фоне; повторный вызов во время поиска ничего не делает. */
    public static synchronized void rescan() {
        if (scanning) return;
        scanning = true;
        Thread t = new Thread(() -> {
            try {
                entries = scanNow(systemRoots(), List.of(folder()));
            } catch (Throwable ignored) {
                // каталог просто останется пустым
            } finally {
                scanned = true;
                scanning = false;
            }
        }, "Overtone-fonts");
        t.setDaemon(true);
        t.start();
    }

    public static void scanOnce() { if (!scanned && !scanning) rescan(); }

    static List<Entry> scanNow(List<Path> system, List<Path> own) {
        Map<String, Entry> out = new LinkedHashMap<>();
        for (Path root : own) collect(root, Source.FOLDER, out);
        for (Path root : system) collect(root, Source.SYSTEM, out);
        List<Entry> list = new ArrayList<>(out.values());
        list.sort(Comparator.comparing((Entry e) -> e.source() == Source.FOLDER ? 0 : 1)
                .thenComparing(e -> e.family().toLowerCase(Locale.ROOT))
                .thenComparing(e -> e.style().toLowerCase(Locale.ROOT)));
        return List.copyOf(list);
    }

    private static List<Path> systemRoots() {
        List<Path> roots = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", "");
        if (os.contains("win")) {
            String win = System.getenv("WINDIR"), local = System.getenv("LOCALAPPDATA");
            roots.add(Path.of(win != null ? win : "C:\\Windows", "Fonts"));
            if (local != null) roots.add(Path.of(local, "Microsoft", "Windows", "Fonts"));
        } else if (os.contains("mac")) {
            roots.add(Path.of("/System/Library/Fonts"));
            roots.add(Path.of("/Library/Fonts"));
            roots.add(Path.of(home, "Library", "Fonts"));
        } else {
            roots.add(Path.of("/usr/share/fonts"));
            roots.add(Path.of("/usr/local/share/fonts"));
            roots.add(Path.of(home, ".fonts"));
            roots.add(Path.of(home, ".local", "share", "fonts"));
        }
        return roots;
    }

    private static void collect(Path root, Source source, Map<String, Entry> out) {
        if (root == null || !Files.isDirectory(root)) return;
        try (Stream<Path> walk = Files.walk(root, 4)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                String n = p.getFileName().toString();
                String low = n.toLowerCase(Locale.ROOT);
                if (!(low.endsWith(".ttf") || low.endsWith(".otf") || low.endsWith(".ttc") || low.endsWith(".otc"))) return;
                String id = (source == Source.FOLDER ? "dir:" : "sys:") + n;
                if (out.containsKey(id)) return;
                Entry e = read(p, source, id);
                if (e != null) out.put(id, e);
            });
        } catch (IOException | RuntimeException ignored) {
            // нечитаемая папка пропускается
        }
    }

    // ---- разбор sfnt ----

    static Entry read(Path file, Source source, String id) {
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
            if (f.length() < 64 || f.length() > 96L * 1024 * 1024) return null;
            byte[] head = new byte[16];
            f.readFully(head);
            long base = 0;
            if (head[0] == 't' && head[1] == 't' && head[2] == 'c' && head[3] == 'f') base = u32(head, 12);
            byte[] dir = new byte[12];
            f.seek(base);
            f.readFully(dir);
            int tag = (int) u32(dir, 0);
            boolean ok = tag == 0x00010000 || tag == 0x4F54544F /* OTTO */ || tag == 0x74727565 /* true */;
            if (!ok) return null;
            int numTables = u16(dir, 4);
            if (numTables <= 0 || numTables > 200) return null;
            byte[] tables = new byte[numTables * 16];
            f.readFully(tables);
            byte[] name = null, cmap = null;
            for (int i = 0; i < numTables; i++) {
                String t = new String(tables, i * 16, 4, StandardCharsets.ISO_8859_1);
                long off = u32(tables, i * 16 + 8), len = u32(tables, i * 16 + 12);
                if (len <= 0 || len > 8L * 1024 * 1024 || off + len > f.length()) continue;
                if (t.equals("name") || t.equals("cmap")) {
                    byte[] data = new byte[(int) len];
                    f.seek(off);
                    f.readFully(data);
                    if (t.equals("name")) name = data; else cmap = data;
                }
            }
            if (cmap == null) return null;
            boolean latin = hasGlyph(cmap, 'A') && hasGlyph(cmap, 'a');
            boolean cyr = hasGlyph(cmap, 0x0410) && hasGlyph(cmap, 0x0430) && hasGlyph(cmap, 0x0451);
            if (!latin && !cyr) return null;   // значки и символы подписью не нужны
            String family = name == null ? "" : pickName(name, 16, 1);
            String style = name == null ? "" : pickName(name, 17, 2);
            if (family.isEmpty()) family = stripExt(file.getFileName().toString());
            return new Entry(id, family, style, file, source, cyr, latin);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String stripExt(String n) {
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static String pickName(byte[] n, int primary, int fallback) {
        String s = findName(n, primary);
        return s.isEmpty() ? findName(n, fallback) : s;
    }

    private static String findName(byte[] n, int nameId) {
        if (n.length < 6) return "";
        int count = u16(n, 2), strings = u16(n, 4);
        String best = "";
        int bestRank = -1;
        for (int i = 0; i < count; i++) {
            int r = 6 + i * 12;
            if (r + 12 > n.length) break;
            int platform = u16(n, r), lang = u16(n, r + 4), id = u16(n, r + 6), len = u16(n, r + 8), off = u16(n, r + 10);
            if (id != nameId) continue;
            int from = strings + off;
            if (len <= 0 || from + len > n.length) continue;
            Charset cs;
            int rank;
            if (platform == 3) { cs = StandardCharsets.UTF_16BE; rank = lang == 0x409 ? 4 : 3; }
            else if (platform == 0) { cs = StandardCharsets.UTF_16BE; rank = 2; }
            else if (platform == 1) { cs = StandardCharsets.ISO_8859_1; rank = 1; }
            else continue;
            if (rank > bestRank) {
                String s = new String(n, from, len, cs).trim();
                if (!s.isEmpty()) { best = s; bestRank = rank; }
            }
        }
        return best;
    }

    static boolean hasGlyph(byte[] cmap, int cp) {
        if (cmap.length < 4) return false;
        int tables = u16(cmap, 2);
        for (int i = 0; i < tables; i++) {
            int r = 4 + i * 8;
            if (r + 8 > cmap.length) break;
            int platform = u16(cmap, r), encoding = u16(cmap, r + 2);
            long off = u32(cmap, r + 4);
            boolean unicode = platform == 0 || (platform == 3 && (encoding == 1 || encoding == 10));
            if (!unicode || off + 8 > cmap.length) continue;
            int sub = (int) off;
            int format = u16(cmap, sub);
            if (format == 4 && glyph4(cmap, sub, cp)) return true;
            if (format == 12 && glyph12(cmap, sub, cp)) return true;
        }
        return false;
    }

    private static boolean glyph4(byte[] b, int sub, int cp) {
        if (cp > 0xFFFF || sub + 16 > b.length) return false;
        int segs = u16(b, sub + 6) / 2;
        int end = sub + 14, start = end + segs * 2 + 2, delta = start + segs * 2, range = delta + segs * 2;
        if (range + segs * 2 > b.length) return false;
        int lo = 0, hi = segs - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (u16(b, end + mid * 2) < cp) lo = mid + 1; else hi = mid;
        }
        if (lo >= segs || u16(b, start + lo * 2) > cp || u16(b, end + lo * 2) < cp) return false;
        int ro = u16(b, range + lo * 2), d = u16(b, delta + lo * 2);
        if (ro == 0) return ((cp + d) & 0xFFFF) != 0;
        int at = range + lo * 2 + ro + 2 * (cp - u16(b, start + lo * 2));
        if (at + 2 > b.length) return false;
        return u16(b, at) != 0;
    }

    private static boolean glyph12(byte[] b, int sub, int cp) {
        if (sub + 16 > b.length) return false;
        long groups = u32(b, sub + 12);
        if (groups <= 0 || sub + 16 + groups * 12 > b.length) return false;
        int lo = 0, hi = (int) groups - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int g = sub + 16 + mid * 12;
            long s = u32(b, g), e = u32(b, g + 4);
            if (cp < s) hi = mid - 1;
            else if (cp > e) lo = mid + 1;
            else return u32(b, g + 8) + (cp - s) != 0;
        }
        return false;
    }

    private static int u16(byte[] b, int o) { return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF); }

    private static long u32(byte[] b, int o) {
        return ((long) (b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }
}
