package dev.musicplayer.hud;

import dev.musicplayer.MusicPlayerMod;
import dev.musicplayer.compat.Textures;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads album art off-thread, rounds its corners and hands finished textures to the renderer.
 * Texture creation has to happen on the render thread, so decoded pixels wait in a queue.
 * Two sizes: small for the HUD widget, big for the player window. Based on the cover loader of
 * SpotifyMusicMod (Kart-Modding, MIT).
 */
public final class CoverArt {
    /** HUD: art is drawn at 32 GUI pixels; the texture is kept at twice that for sharper scaling. */
    public static final int TEXTURE_SIZE = 64;
    /** Player window: covers up to ~110 GUI pixels (x2 for high GUI scales). */
    public static final int BIG_SIZE = 256;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final ExecutorService DOWNLOADER = Executors.newFixedThreadPool(1, runnable -> {
        Thread thread = new Thread(runnable, "musicplayer-art");
        thread.setDaemon(true);
        return thread;
    });

    private static final class Cache {
        final int size;
        final int keep;
        final String prefix;
        /** LRU: при переполнении выбрасывается текстура, которую дольше всего не рисовали. */
        final Map<String, Identifier> ready = Collections.synchronizedMap(new LinkedHashMap<String, Identifier>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Identifier> eldest) {
                if (size() <= Cache.this.keep) return false;
                Textures.release(eldest.getValue());
                return true;
            }
        });
        final Map<String, int[]> decoded = new ConcurrentHashMap<>();
        final Map<String, Boolean> inFlight = new ConcurrentHashMap<>();

        Cache(int size, int keep, String prefix) { this.size = size; this.keep = keep; this.prefix = prefix; }

        void offer(String key, String url) {
            if (key == null || url == null || url.isBlank()) return;
            if (ready.containsKey(key) || decoded.containsKey(key)) return;
            if (inFlight.putIfAbsent(key, Boolean.TRUE) != null) return;
            DOWNLOADER.submit(() -> download(this, key, url));
        }

        void upload() {
            if (decoded.isEmpty()) return;
            for (String key : decoded.keySet().toArray(new String[0])) {
                int[] pixels = decoded.remove(key);
                if (pixels == null) continue;
                Identifier id = Textures.upload(prefix + Integer.toHexString(key.hashCode()), pixels, size);
                if (id == null) continue;
                Identifier previous = ready.put(key, id);
                if (previous != null && !previous.equals(id)) Textures.release(previous);
            }
        }
    }

    private static final Cache SMALL = new Cache(TEXTURE_SIZE, 16, "cover_");
    private static final Cache BIG = new Cache(BIG_SIZE, 8, "coverbig_");

    // ---- дисковый кэш: config/musicplayer-cache/<ключ>_<хеш url>.img (исходные байты картинки) ----
    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;
    private static final int MAX_CACHE_FILES = 300;
    private static volatile boolean pruned;

    private CoverArt() {
    }

    private static Path cacheDir() { return FabricLoader.getInstance().getConfigDir().resolve("musicplayer-cache"); }

    /** Имя файла только из безопасных символов — ключ приходит из внешнего API. */
    private static Path cacheFile(String key, String url) {
        String safe = key.replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe.length() > 60) safe = safe.substring(0, 60);
        return cacheDir().resolve(safe + "_" + Integer.toHexString(url.hashCode()) + ".img");
    }

    private static byte[] readCache(Path f) {
        try {
            if (Files.isRegularFile(f) && Files.size(f) <= MAX_IMAGE_BYTES) return Files.readAllBytes(f);
        } catch (Exception ignored) {}
        return null;
    }

    /** Обложки локальных файлов лежат в нашей папке индекса; любой другой путь игнорируется. */
    private static byte[] readLocal(String url) {
        try {
            URI uri = URI.create(url);
            if (!dev.musicplayer.local.LocalLibrary.isCoverUri(uri)) return null;
            Path p = Path.of(uri);
            return Files.isRegularFile(p) && Files.size(p) <= MAX_IMAGE_BYTES ? Files.readAllBytes(p) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeCache(Path f, byte[] data) {
        try {
            Files.createDirectories(f.getParent());
            Files.write(f, data);
        } catch (Exception ignored) {}
    }

    private static void pruneCache() {
        if (pruned) return;
        pruned = true;
        try {
            File[] files = cacheDir().toFile().listFiles();
            if (files == null || files.length <= MAX_CACHE_FILES) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            for (int i = 0; i < files.length - MAX_CACHE_FILES; i++) files[i].delete();
        } catch (Exception ignored) {}
    }

    public static Identifier get(String key) {
        if (key == null || key.isBlank()) return null;
        return SMALL.ready.get(key);
    }

    public static Identifier getBig(String key) {
        if (key == null || key.isBlank()) return null;
        return BIG.ready.get(key);
    }

    public static void offerRemote(String key, String url) { SMALL.offer(key, url); }

    public static void offerBig(String key, String url) { BIG.offer(key, url); }

    /**
     * Uploads finished downloads to the GPU. Called from the client tick so texture creation never
     * happens in the middle of HUD rendering.
     */
    public static void tick() {
        SMALL.upload();
        BIG.upload();
    }

    private static HttpResponse<byte[]> fetch(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static void download(Cache cache, String key, String url) {
        try {
            pruneCache();
            Path file = cacheFile(key, url);
            byte[] body = url.startsWith("file:") ? readLocal(url) : readCache(file);
            if (body == null && url.startsWith("file:")) return;
            if (body == null) {
                HttpResponse<byte[]> response = fetch(url);
                if (response.statusCode() == 404 && url.contains("-t500x500.")) response = fetch(url.replace("-t500x500.", "-large."));
                if (response.statusCode() != 200) {
                    MusicPlayerMod.LOGGER.warn("Album art request returned {} for {}", response.statusCode(), url);
                    return;
                }
                body = response.body();
                if (body.length > MAX_IMAGE_BYTES) return;
                writeCache(file, body);
            }
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(body));
            if (source == null) return;
            cache.decoded.put(key, toRoundedArgb(source, cache.size));
        } catch (Exception e) {
            MusicPlayerMod.LOGGER.warn("Album art download failed", e);
        } finally {
            cache.inFlight.remove(key);
        }
    }

    private static int[] toRoundedArgb(BufferedImage source, int size) {
        BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        int side = Math.min(source.getWidth(), source.getHeight());
        int sx = (source.getWidth() - side) / 2, sy = (source.getHeight() - side) / 2;
        graphics.drawImage(source, 0, 0, size, size, sx, sy, sx + side, sy + side, null);
        graphics.dispose();

        int radius = size / 8;
        int[] pixels = scaled.getRGB(0, 0, size, size, null, 0, size);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float coverage = cornerCoverage(x, y, size, radius);
                if (coverage >= 1.0f) continue;
                int index = y * size + x;
                int argb = pixels[index];
                int alpha = Math.round(((argb >>> 24) & 0xFF) * coverage);
                pixels[index] = (alpha << 24) | (argb & 0x00FFFFFF);
            }
        }
        return pixels;
    }

    private static float cornerCoverage(int x, int y, int size, int radius) {
        float cx = x + 0.5f;
        float cy = y + 0.5f;
        float dx = 0;
        float dy = 0;
        if (cx < radius) dx = radius - cx;
        else if (cx > size - radius) dx = cx - (size - radius);
        if (cy < radius) dy = radius - cy;
        else if (cy > size - radius) dy = cy - (size - radius);
        if (dx == 0 || dy == 0) return 1.0f;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        if (distance <= radius - 0.5f) return 1.0f;
        if (distance >= radius + 0.5f) return 0.0f;
        return radius + 0.5f - distance;
    }
}
