package dev.musicplayer.local;

import dev.musicplayer.util.T;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.musicplayer.MusicPlayerMod;
import dev.musicplayer.model.Track;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Библиотека локальных файлов: индекс тегов и длительностей лежит в config/musicplayer-local/, музыка не копируется и никуда не отправляется. */
public final class LocalLibrary {
    public static final Set<String> MP3 = Set.of("mp3");
    public static final Set<String> PCM = Set.of("wav", "wave", "aif", "aiff", "au");

    private static final int MAX_DEPTH = 12;
    private static final int MAX_FILES = 50_000;
    private static final long MAX_IMAGE_BYTES = 8L << 20;
    private static final int MAX_RESULTS = 3000;

    static final class Entry {
        String id, path, title, artist, album, cover;
        long size, mtime, durationMs;
    }

    private static final class IndexFile { List<Entry> entries = new ArrayList<>(); }
    private static final class SourcesFile { List<String> sources = new ArrayList<>(); }

    private final Gson gson = new GsonBuilder().create();
    private final Map<String, Entry> byId = new ConcurrentHashMap<>();
    private final Map<Path, String> folderCovers = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "musicplayer-local-index");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final AtomicBoolean scanning = new AtomicBoolean();
    private volatile List<Entry> sorted = List.of();
    private volatile List<String> sources = List.of();
    private volatile String progress = "";
    private volatile int version;
    private boolean loaded;

    private static Path dir() { return FabricLoader.getInstance().getConfigDir().resolve("musicplayer-local"); }
    private static Path coversDir() { return dir().resolve("covers"); }
    public static Path defaultFolder() { return FabricLoader.getInstance().getGameDir().resolve("overtone-music"); }

    /** Обложки читаются только из нашей папки: адрес из сохранённой сессии не должен вести к чужим файлам. */
    public static boolean isCoverUri(java.net.URI uri) {
        try {
            return Path.of(uri).toAbsolutePath().normalize().startsWith(coversDir().toAbsolutePath().normalize());
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isSupported(Path p) {
        String e = extension(p);
        return MP3.contains(e) || PCM.contains(e);
    }

    public static boolean isMp3(Path p) { return MP3.contains(extension(p)); }

    private static String extension(Path p) {
        String n = p.getFileName() == null ? "" : p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            Path s = dir().resolve("sources.json");
            if (Files.exists(s)) {
                SourcesFile f = gson.fromJson(Files.readString(s, StandardCharsets.UTF_8), SourcesFile.class);
                if (f != null && f.sources != null) sources = List.copyOf(f.sources);
            }
            Path i = dir().resolve("index.json");
            if (Files.exists(i)) {
                IndexFile f = gson.fromJson(Files.readString(i, StandardCharsets.UTF_8), IndexFile.class);
                if (f != null && f.entries != null)
                    for (Entry e : f.entries) if (e != null && e.id != null && e.path != null) byId.put(e.id, e);
            }
        } catch (Exception e) {
            MusicPlayerMod.LOGGER.warn("Local library index is unreadable, it will be rebuilt", e);
        }
        publish();
    }

    public int size() { ensureLoaded(); return byId.size(); }

    /** Растёт при каждом изменении списка — экран по нему понимает, что пора обновить результаты. */
    public int version() { return version; }

    public String status() {
        ensureLoaded();
        String p = progress;
        if (!p.isEmpty()) return p;
        int n = byId.size();
        return n == 0 ? T.get("local.empty") : T.tracks(n);
    }

    public Path pathOf(String id) {
        ensureLoaded();
        Entry e = id == null ? null : byId.get(id);
        return e == null ? null : Path.of(e.path);
    }

    public List<Track> search(String query) {
        ensureLoaded();
        String[] words = normalize(query == null ? "" : query).split(" ");
        List<Track> out = new ArrayList<>();
        for (Entry e : sorted) {
            if (out.size() >= MAX_RESULTS) break;
            if (matches(e, words)) out.add(toTrack(e));
        }
        return out;
    }

    private static boolean matches(Entry e, String[] words) {
        String hay = normalize(e.title + " " + e.artist + " " + e.album);
        for (String w : words) if (!w.isEmpty() && !hay.contains(w)) return false;
        return true;
    }

    private static String normalize(String s) { return s.toLowerCase(Locale.ROOT).replace('ё', 'е').trim(); }

    private static Track toTrack(Entry e) {
        return new Track(e.id, e.title, e.artist, e.album == null ? "" : e.album, e.durationMs, e.cover, "local");
    }

    public synchronized int addPaths(List<Path> dropped) {
        ensureLoaded();
        List<String> next = new ArrayList<>(sources);
        int added = 0;
        for (Path p : dropped) {
            Path abs = p.toAbsolutePath().normalize();
            boolean ok = Files.isDirectory(abs) || (Files.isRegularFile(abs) && isSupported(abs));
            if (!ok || isCovered(next, abs)) continue;
            next.add(abs.toString());
            added++;
        }
        if (added > 0) {
            sources = List.copyOf(next);
            saveSources();
            rescan();
        }
        return added;
    }

    private static boolean isCovered(List<String> sources, Path p) {
        for (String s : sources) {
            Path src = Path.of(s);
            if (p.equals(src) || (Files.isDirectory(src) && p.startsWith(src))) return true;
        }
        return p.startsWith(defaultFolder().toAbsolutePath().normalize());
    }

    public void rescan() {
        ensureLoaded();
        if (scanning.compareAndSet(false, true)) worker.execute(this::scan);
    }

    private void scan() {
        try {
            folderCovers.clear();
            List<Path> files = collect();
            Set<String> seen = new HashSet<>();
            int done = 0;
            for (Path f : files) {
                progress = T.get("local.indexing", ++done, files.size());
                Entry e = entryFor(f);
                if (e == null) continue;
                seen.add(e.id);
                byId.put(e.id, e);
                if (done % 40 == 0) publish();
            }
            byId.keySet().retainAll(seen);
            publish();
            saveIndex();
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.warn("Local library scan failed", t);
        } finally {
            progress = "";
            scanning.set(false);
        }
    }

    private List<Path> collect() {
        List<Path> roots = new ArrayList<>();
        Path def = defaultFolder();
        if (Files.isDirectory(def)) roots.add(def);
        for (String s : sources) roots.add(Path.of(s));

        Set<Path> out = new java.util.LinkedHashSet<>();
        for (Path root : roots) {
            if (out.size() >= MAX_FILES) break;
            if (Files.isRegularFile(root)) { if (isSupported(root)) out.add(root); continue; }
            if (!Files.isDirectory(root)) continue;
            try {
                Files.walkFileTree(root, Set.<FileVisitOption>of(), MAX_DEPTH, new SimpleFileVisitor<>() {
                    @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                        if (a.isRegularFile() && isSupported(f)) out.add(f.toAbsolutePath().normalize());
                        return out.size() >= MAX_FILES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }
                });
            } catch (IOException ignored) {
                // недоступная папка (например, отключённый диск) просто пропускается
            }
        }
        return new ArrayList<>(out);
    }

    private Entry entryFor(Path f) {
        try {
            long size = Files.size(f), mtime = Files.getLastModifiedTime(f).toMillis();
            String id = idOf(f);
            Entry old = byId.get(id);
            if (old != null && old.size == size && old.mtime == mtime) return old;

            boolean mp3 = isMp3(f);
            long duration = TagReader.durationMs(f, mp3);
            if (duration <= 0) return null;
            TagReader.Tags tags = mp3 ? TagReader.read(f) : TagReader.Tags.EMPTY;
            String[] guess = fromFileName(f);

            Entry e = new Entry();
            e.id = id; e.path = f.toString(); e.size = size; e.mtime = mtime; e.durationMs = duration;
            e.title = tags.title().isBlank() ? guess[1] : tags.title();
            e.artist = tags.artist().isBlank() ? guess[0] : tags.artist();
            e.album = tags.album();
            e.cover = storeCover(tags.cover(), f.getParent());
            return e;
        } catch (Exception ex) {
            MusicPlayerMod.LOGGER.debug("Skipping {}: {}", f, ex.toString());
            return null;
        }
    }

    private static String idOf(Path f) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(f.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder("L");
        for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
        return sb.toString();
    }

    /** «01. Исполнитель - Название.mp3» → {исполнитель, название}. */
    private static String[] fromFileName(Path f) {
        String n = f.getFileName().toString();
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        n = n.replace('_', ' ').replaceFirst("^\\s*\\d{1,3}\\s*[.)\\-]\\s*", "").trim();
        int sep = n.indexOf(" - ");
        if (sep > 0 && sep + 3 < n.length()) return new String[]{n.substring(0, sep).trim(), n.substring(sep + 3).trim()};
        return new String[]{"", n.isEmpty() ? f.getFileName().toString() : n};
    }

    private String storeCover(byte[] embedded, Path folder) {
        try {
            byte[] bytes = embedded;
            if (bytes == null || bytes.length == 0) {
                String cached = folder == null ? null : folderCovers.get(folder);
                if (cached != null) return cached.isEmpty() ? null : cached;
                String uri = null;
                byte[] img = folder == null ? null : folderImage(folder);
                if (img != null) uri = writeCover(img);
                if (folder != null) folderCovers.put(folder, uri == null ? "" : uri);
                return uri;
            }
            return writeCover(bytes);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] folderImage(Path folder) throws IOException {
        for (String name : new String[]{"cover", "folder", "front", "album"})
            for (String ext : new String[]{"jpg", "jpeg", "png"}) {
                Path p = folder.resolve(name + "." + ext);
                if (Files.isRegularFile(p) && Files.size(p) <= MAX_IMAGE_BYTES) return Files.readAllBytes(p);
            }
        return null;
    }

    /** Одинаковые обложки (весь альбом) хранятся одним файлом: имя — хеш содержимого. */
    private String writeCover(byte[] bytes) throws Exception {
        if (bytes.length > MAX_IMAGE_BYTES) return null;
        byte[] d = MessageDigest.getInstance("SHA-1").digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        Path file = coversDir().resolve(sb + ".img");
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        }
        return file.toUri().toString();
    }

    /** Текст рядом с файлом: «песня.lrc» или «песня.txt», а если их нет — вшитый в теги. */
    public String lyricsText(Path audio) {
        String name = audio.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        for (String ext : new String[]{"lrc", "txt"}) {
            Path side = audio.resolveSibling(base + "." + ext);
            try {
                if (Files.isRegularFile(side) && Files.size(side) < (1 << 20))
                    return TagReader.decodeText(Files.readAllBytes(side));
            } catch (IOException ignored) {
                // нечитаемый файл текста — пробуем следующий источник
            }
        }
        return isMp3(audio) ? TagReader.read(audio).lyrics() : "";
    }

    private void publish() {
        List<Entry> list = new ArrayList<>(byId.values());
        list.sort(Comparator.comparing((Entry e) -> normalize(e.artist == null ? "" : e.artist))
                .thenComparing(e -> normalize(e.title == null ? "" : e.title)));
        sorted = list;
        version++;
    }

    private void saveSources() {
        try {
            SourcesFile f = new SourcesFile();
            f.sources = new ArrayList<>(sources);
            Files.createDirectories(dir());
            Files.writeString(dir().resolve("sources.json"), gson.toJson(f), StandardCharsets.UTF_8);
        } catch (IOException e) {
            MusicPlayerMod.LOGGER.warn("Could not save local sources", e);
        }
    }

    private void saveIndex() {
        try {
            IndexFile f = new IndexFile();
            f.entries = new ArrayList<>(byId.values());
            Files.createDirectories(dir());
            Path tmp = dir().resolve("index.json.tmp");
            Files.writeString(tmp, gson.toJson(f), StandardCharsets.UTF_8);
            Files.move(tmp, dir().resolve("index.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            MusicPlayerMod.LOGGER.warn("Could not save local index", e);
        }
    }
}
