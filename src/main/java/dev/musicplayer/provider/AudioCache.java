package dev.musicplayer.provider;

import dev.musicplayer.config.PlayerConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кэш треков в два слоя: память и диск (переживает перезапуск). Треки из «keep» и играющий вытесняются последними,
 * поэтому повтор трека или альбома не требует новой загрузки.
 */
final class AudioCache {
    private static final long MEM_CAP = 96L << 20;
    private static final long DISK_CAP = 512L << 20;

    private final LinkedHashMap<String, byte[]> mem = new LinkedHashMap<>(16, 0.75f, true);
    private long memBytes;
    private volatile Set<String> keep = Set.of();
    private volatile String pinned;
    private final Set<String> onDisk = ConcurrentHashMap.newKeySet();
    private final Path dir;

    AudioCache() {
        Path d = null;
        try { d = FabricLoader.getInstance().getGameDir().resolve("musicplayer-cache"); } catch (Throwable ignored) {}
        dir = d;
        Thread t = new Thread(this::scan, "musicplayer-cache-scan");
        t.setDaemon(true);
        t.start();
    }

    private static String safe(String id) { return id.replaceAll("[^0-9A-Za-z_.-]", "_"); }

    private Path file(String id) { return dir.resolve(safe(id) + ".mp3"); }

    private boolean diskEnabled() { return dir != null && PlayerConfig.get().diskCache; }

    private void scan() {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (var s = Files.list(dir)) {
            s.forEach(p -> {
                String n = p.getFileName().toString();
                if (n.endsWith(".part")) { try { Files.deleteIfExists(p); } catch (IOException ignored) {} }
                else if (n.endsWith(".mp3")) onDisk.add(n.substring(0, n.length() - 4));
            });
        } catch (IOException ignored) {}
        prune();
    }

    void setKeep(Set<String> ids, String current) { keep = ids; pinned = current; }

    boolean has(String id) {
        synchronized (this) { if (mem.containsKey(id)) return true; }
        return onDisk.contains(safe(id));
    }

    /** Байты трека из кэша или null. Чтение с диска переносит трек в память. */
    byte[] get(String id) {
        synchronized (this) {
            byte[] b = mem.get(id);
            if (b != null) return b;
        }
        if (dir == null || !onDisk.contains(safe(id))) return null;
        try {
            Path f = file(id);
            byte[] data = Files.readAllBytes(f);
            try { Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis())); } catch (IOException ignored) {}
            if (data.length == 0) { onDisk.remove(safe(id)); return null; }
            memPut(id, data);
            return data;
        } catch (IOException e) {
            onDisk.remove(safe(id));
            return null;
        }
    }

    void put(String id, byte[] data) {
        memPut(id, data);
        if (!diskEnabled()) return;
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(safe(id) + ".part");
            Files.write(tmp, data);
            Files.move(tmp, file(id), StandardCopyOption.REPLACE_EXISTING);
            onDisk.add(safe(id));
            prune();
        } catch (IOException ignored) {
            // диск недоступен или полон — остаёмся на кэше в памяти
        }
    }

    private synchronized void memPut(String id, byte[] data) {
        byte[] old = mem.put(id, data);
        if (old != null) memBytes -= old.length;
        memBytes += data.length;
        // вытесняем сначала то, что не нужно в ближайшее время, потом всё остальное, кроме играющего
        for (int pass = 0; pass < 2 && memBytes > MEM_CAP && mem.size() > 1; pass++) {
            Iterator<Map.Entry<String, byte[]>> it = mem.entrySet().iterator();
            while (it.hasNext() && memBytes > MEM_CAP && mem.size() > 1) {
                Map.Entry<String, byte[]> e = it.next();
                String k = e.getKey();
                if (k.equals(id) || k.equals(pinned)) continue;
                if (pass == 0 && keep.contains(k)) continue;
                memBytes -= e.getValue().length;
                it.remove();
            }
        }
    }

    private void prune() {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (var s = Files.list(dir)) {
            List<Path> files = new ArrayList<>(s.filter(p -> p.toString().endsWith(".mp3")).toList());
            long total = 0;
            for (Path p : files) total += Files.size(p);
            if (total <= DISK_CAP) return;
            files.sort(Comparator.comparingLong(p -> {
                try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0L; }
            }));
            for (Path p : files) {
                if (total <= DISK_CAP) break;
                String n = p.getFileName().toString();
                String base = n.substring(0, n.length() - 4);
                if (base.equals(pinned == null ? null : safe(pinned))) continue;
                boolean kept = false;
                for (String k : keep) if (safe(k).equals(base)) { kept = true; break; }
                if (kept) continue;
                long size = Files.size(p);
                Files.deleteIfExists(p);
                onDisk.remove(base);
                total -= size;
            }
        } catch (IOException ignored) {}
    }

    synchronized void clear() {
        String keepId = pinned;
        mem.keySet().removeIf(k -> !k.equals(keepId));
        memBytes = 0;
        for (byte[] b : mem.values()) memBytes += b.length;
        if (dir == null) return;
        try (var s = Files.list(dir)) {
            s.forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
        onDisk.clear();
    }

    long diskMegabytes() {
        if (dir == null || !Files.isDirectory(dir)) return 0;
        try (var s = Files.list(dir)) {
            long t = 0;
            for (Path p : s.toList()) t += Files.size(p);
            return t >> 20;
        } catch (IOException e) { return 0; }
    }
}
