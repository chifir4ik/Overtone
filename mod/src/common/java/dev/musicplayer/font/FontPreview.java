package dev.musicplayer.font;

import dev.musicplayer.compat.Tex;
import dev.musicplayer.compat.Textures;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Превью строки выбранным шрифтом для каталога. Растеризуется в фоне через Java2D (без AWT-окон), затем кладётся
 * текстурой в игру. Если Java2D недоступен или формат не поддерживается (например .ttc), превью просто нет.
 */
public final class FontPreview {
    private FontPreview() {}

    /** Готовое превью: тексель-размеры и текстура; рисуется кусок w×h из квадрата SIZE×SIZE. */
    public record Slot(Tex tex, int w, int h) {}

    public static final int SIZE = 512;
    private static final int KEEP = 20;

    private record Done(String key, String name, int[] pixels, int w, int h) {}

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Overtone-fontpreview");
        t.setDaemon(true);
        return t;
    });
    private static final ConcurrentLinkedQueue<Done> DONE = new ConcurrentLinkedQueue<>();
    private static final Set<String> PENDING = new HashSet<>();
    private static final Set<String> FAILED = new HashSet<>();
    private static final Map<String, Slot> CACHE = new LinkedHashMap<>(32, 0.75f, true);

    /**
     * Превью или null, если оно ещё готовится (или невозможно). Вызывать только из потока отрисовки.
     * px — высота шрифта в экранных пикселях (размер в GUI × масштаб интерфейса).
     */
    public static Slot get(FontCatalog.Entry e, float px, String text) {
        String key = e.id() + '|' + Math.round(px * 2) + '|' + text.hashCode();
        Slot s = CACHE.get(key);
        if (s != null) return s;
        if (!FAILED.contains(key) && PENDING.add(key)) {
            String name = "fontprev" + Integer.toHexString(key.hashCode());
            POOL.execute(() -> {
                Done d = render(e, px, text, key, name);
                if (d == null) synchronized (FAILED) { FAILED.add(key); }
                else DONE.add(d);
                synchronized (PENDING) { PENDING.remove(key); }
            });
        }
        return null;
    }

    /** Заливает готовые картинки в игру. Раз в кадр из потока отрисовки. */
    public static void pump() {
        Done d;
        int guard = 0;
        while (guard++ < 3 && (d = DONE.poll()) != null) {
            Tex tex = Textures.upload(d.name(), d.pixels(), SIZE);
            if (tex == null) { FAILED.add(d.key()); continue; }
            CACHE.put(d.key(), new Slot(tex, d.w(), d.h()));
            while (CACHE.size() > KEEP) {
                var it = CACHE.entrySet().iterator();
                Slot old = it.next().getValue();
                it.remove();
                Textures.release(old.tex());
            }
        }
    }

    public static void clear() {
        for (Slot s : CACHE.values()) Textures.release(s.tex());
        CACHE.clear();
        FAILED.clear();
    }

    private static Done render(FontCatalog.Entry e, float px, String text, String key, String name) {
        try {
            if (System.getProperty("java.awt.headless") == null) System.setProperty("java.awt.headless", "true");
            Font base = Font.createFont(Font.TRUETYPE_FONT, e.file().toFile());
            BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                float size = Math.max(6f, px);
                Font f = base.deriveFont(size);
                FontMetrics m = g.getFontMetrics(f);
                while (m.stringWidth(text) > SIZE - 6 && size > 8f) {   // длинное превью чуть уменьшается, чтобы поместиться
                    size -= 1f;
                    f = base.deriveFont(size);
                    m = g.getFontMetrics(f);
                }
                int w = Math.min(SIZE, m.stringWidth(text) + 4), h = Math.min(SIZE, m.getAscent() + m.getDescent() + 2);
                g.setFont(f);
                g.setColor(Color.WHITE);
                g.drawString(text, 1, m.getAscent());
                int[] px32 = new int[SIZE * SIZE];
                img.getRGB(0, 0, SIZE, SIZE, px32, 0, SIZE);
                return new Done(key, name, px32, w, h);
            } finally {
                g.dispose();
            }
        } catch (Throwable t) {
            return null;
        }
    }
}
