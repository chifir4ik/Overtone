package dev.musicplayer.ui;

import dev.musicplayer.MusicPlayerMod;
import dev.musicplayer.compat.Canvas;
import dev.musicplayer.compat.Game;
import dev.musicplayer.compat.ModScreen;
import dev.musicplayer.config.PlayerConfig;
import dev.musicplayer.font.FontCatalog;
import dev.musicplayer.font.FontPack;
import dev.musicplayer.font.FontPreview;
import dev.musicplayer.font.LyricFonts;
import dev.musicplayer.util.Browser;
import dev.musicplayer.util.T;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Каталог шрифтов для текста песен. Первая строка всегда обычный шрифт игры (его меняют ресурспаками), дальше
 * свои файлы из папки мода и системные шрифты. Выбор применяется кнопкой: игра перезагружает ресурсы.
 */
public class FontScreen extends ModScreen {
    private enum Filter { ALL, CYR, OWN, SYS }

    private static final int HEADER_H = 28, FOOTER_H = 32, PAD = 8, RIGHT_W = 170, ROW_H = 34, BAR_W = 5, MAX_ROWS = 14;
    private static final float[] MIN = {4f, 1f, -8f, -8f}, MAX = {32f, 8f, 8f, 8f}, STEP = {0.5f, 1f, 0.5f, 0.5f};
    private static final String[] KEYS = {"font.size", "font.oversample", "font.shift_x", "font.shift_y"};

    private final Screen parent;
    private Filter filter = Filter.ALL;
    private String query = "";
    private EditBox search;
    private String pendingId;
    private final float[] v = new float[4];
    private int first;
    private final List<FontCatalog.Entry> shown = new ArrayList<>();
    private PlayerTheme th;

    private int px, py, pw, ph, listX, listW, listTop, listBottom, rightX, sbX;
    private int mouseX, mouseY;
    private final int[] folderBtn = new int[4], refreshBtn = new int[4], applyBtn = new int[4], doneBtn = new int[4];
    private final int[][] filterBtn = new int[Filter.values().length][4];
    private final int[][] stepBtn = new int[8][4];

    private Button[] rowHits, filterHits, stepHits;
    private Button folderHit, refreshHit, applyHit, doneHit;

    public FontScreen(Screen parent) {
        super(Component.literal(MusicPlayerMod.NAME));
        this.parent = parent;
        PlayerConfig c = PlayerConfig.get();
        pendingId = c.lyricFont == null ? "" : c.lyricFont;
        v[0] = c.lyricFontSize; v[1] = c.lyricFontOversample; v[2] = c.lyricFontShiftX; v[3] = c.lyricFontShiftY;
    }

    @Override
    protected void init() {
        FontCatalog.scanOnce();
        layout();
        search = new EditBox(font, listX + 7, py + HEADER_H + 12, Math.max(40, listW - 14), 10, Component.empty());
        search.setBordered(false);
        search.setHint(Component.literal(T.get("font.search")));
        search.setMaxLength(60);
        search.setValue(query);
        search.setResponder(s -> { query = s; refilter(); });
        addRenderableWidget(search);

        rowHits = new Button[MAX_ROWS];
        for (int i = 0; i < MAX_ROWS; i++) {
            int k = i;
            rowHits[i] = hit(Hit.make(() -> pick(first + k)));
        }
        filterHits = new Button[Filter.values().length];
        for (int i = 0; i < filterHits.length; i++) {
            Filter f = Filter.values()[i];
            filterHits[i] = hit(Hit.make(() -> { filter = f; first = 0; refilter(); }));
        }
        stepHits = new Button[8];
        for (int i = 0; i < 8; i++) {
            int idx = i / 2, dir = i % 2 == 0 ? -1 : 1;
            stepHits[i] = hit(Hit.make(() -> step(idx, dir)));
        }
        folderHit = hit(Hit.make(() -> {
            try { Files.createDirectories(FontCatalog.folder()); } catch (Exception ignored) { }
            Browser.openFolder(FontCatalog.folder());
        }));
        refreshHit = hit(Hit.make(() -> { FontPreview.clear(); FontCatalog.rescan(); }));
        applyHit = hit(Hit.make(this::apply));
        doneHit = hit(Hit.make(this::onClose));
        refilter();
    }

    private Button hit(Button b) { addWidget(b); return b; }

    private void layout() {
        pw = Math.min(width - 16, 500);
        ph = Math.min(height - 16, 340);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        rightX = px + pw - PAD - RIGHT_W;
        listX = px + PAD;
        sbX = rightX - BAR_W - 6;
        listW = sbX - 4 - listX;
        listTop = py + HEADER_H + 6 + 25 + 22;   // под полем поиска и рядом фильтров
        listBottom = py + ph - FOOTER_H - 4;
    }

    private int rowsFit() { return Math.max(1, Math.min(MAX_ROWS, (listBottom - listTop) / ROW_H)); }

    private int total() { return shown.size() + 1; }

    private void refilter() {
        shown.clear();
        String q = query.toLowerCase(Locale.ROOT).trim();
        for (FontCatalog.Entry e : FontCatalog.entries()) {
            if (filter == Filter.CYR && !e.cyrillic()) continue;
            if (filter == Filter.OWN && e.source() != FontCatalog.Source.FOLDER) continue;
            if (filter == Filter.SYS && e.source() != FontCatalog.Source.SYSTEM) continue;
            if (!q.isEmpty() && !(e.title().toLowerCase(Locale.ROOT).contains(q) || e.file().getFileName().toString().toLowerCase(Locale.ROOT).contains(q))) continue;
            shown.add(e);
        }
        first = Math.max(0, Math.min(first, Math.max(0, total() - rowsFit())));
    }

    private void pick(int index) {
        if (index < 0 || index >= total()) return;
        pendingId = index == 0 ? "" : shown.get(index - 1).id();
    }

    private void step(int idx, int dir) {
        v[idx] = Math.max(MIN[idx], Math.min(MAX[idx], Math.round((v[idx] + dir * STEP[idx]) / STEP[idx]) * STEP[idx]));
    }

    private boolean changed() {
        PlayerConfig c = PlayerConfig.get();
        String cur = c.lyricFont == null ? "" : c.lyricFont;
        if (!pendingId.equals(cur)) return true;
        if (pendingId.isEmpty()) return false;
        return v[0] != c.lyricFontSize || v[1] != c.lyricFontOversample || v[2] != c.lyricFontShiftX || v[3] != c.lyricFontShiftY
                || FontPack.state() == FontPack.State.ERROR || !LyricFonts.active();
    }

    private boolean canApply() {
        if (FontPack.state() == FontPack.State.WORKING || !changed()) return false;
        return pendingId.isEmpty() || FontCatalog.find(pendingId) != null;
    }

    private void apply() {
        if (!canApply()) return;
        if (pendingId.isEmpty()) { FontPack.useDefault(); return; }
        FontPack.use(FontCatalog.find(pendingId), new FontPack.Params(v[0], v[1], v[2], v[3]));
    }

    // ---------------- отрисовка ----------------

    @Override
    protected void draw(Canvas g, int mx, int my, float delta) {
        mouseX = mx; mouseY = my;
        th = PlayerTheme.current();
        layout();
        FontPreview.pump();
        if (!FontCatalog.scanning() && FontCatalog.entries().size() != lastCount) { lastCount = FontCatalog.entries().size(); refilter(); }

        th.window(g, px, py, pw, ph);
        g.text(font, "♪ " + MusicPlayerMod.NAME, px + 12, py + 10, th.accent);
        g.text(font, T.get("font.title"), px + 14 + font.width("♪ " + MusicPlayerMod.NAME) + 6, py + 10, PlayerTheme.TEXT_DIM);
        g.fill(px + 10, py + HEADER_H - 2, px + pw - 10, py + HEADER_H - 1, PlayerTheme.argb(0xFFFFFF, 16));

        drawToolbar(g);
        drawList(g);
        drawRight(g);
        drawFooter(g);
        syncHits();
        if (!transitioning()) drawWidgets(g, mx, my, delta);
    }

    private int lastCount = -1;

    private boolean in(int[] r, int mx, int my) { return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]; }

    private void pill(Canvas g, int[] r, int x, int y, int w, String text, boolean active) {
        r[0] = x; r[1] = y; r[2] = w; r[3] = 16;
        boolean hover = in(r, mouseX, mouseY);
        PlayerTheme.rrect(g, x, y, w, 16, 8, active ? th.rowCurrent : hover ? th.hover : th.trackBg);
        g.text(font, text, x + (w - font.width(text)) / 2, y + 4, active ? th.accent : hover ? PlayerTheme.TEXT_MAIN : PlayerTheme.TEXT_MID);
    }

    private void drawToolbar(Canvas g) {
        int ay = py + HEADER_H + 6;
        int br = Math.min(th.radius, 4);
        PlayerTheme.rrect(g, listX - 1, ay - 1, listW + 2, 22, br + 1, search != null && search.isFocused() ? th.accent : th.trackBg);
        PlayerTheme.rrect(g, listX, ay, listW, 20, br, th.fieldBg);
        if (search != null) { search.setX(listX + 7); search.setY(ay + 6); search.setWidth(Math.max(40, listW - 14)); }

        // ряд фильтров под поиском
        int x = listX, fy = ay + 25;
        Filter[] fs = Filter.values();
        String[] names = {T.get("font.f_all"), T.get("font.f_cyr"), T.get("font.f_own"), T.get("font.f_sys")};
        for (int i = 0; i < fs.length; i++) {
            int w = font.width(names[i]) + 14;
            pill(g, filterBtn[i], x, fy, w, names[i], filter == fs[i]);
            x += w + 3;
        }
        // справа от поиска: папка и обновление
        String dir = T.get("font.folder"), ref = T.get("font.refresh");
        int half = (RIGHT_W - 4) / 2;
        pill(g, folderBtn, rightX, ay + 2, half, dir, false);
        pill(g, refreshBtn, rightX + half + 4, ay + 2, RIGHT_W - half - 4, ref, false);
    }

    private void tag(Canvas g, int x, int y, String text, int rgb) {
        int w = font.width(text) + 6;
        PlayerTheme.rrect(g, x, y, w, 10, 3, PlayerTheme.argb(rgb, 60));
        g.text(font, text, x + 3, y + 1, 0xFF000000 | PlayerTheme.mix(rgb, 0xFFFFFF, 0.55f));
    }

    private void drawList(Canvas g) {
        int fit = rowsFit();
        double scale = Minecraft.getInstance().getWindow().getGuiScale();
        String sample = null;
        int rows = Math.min(fit, total() - first);
        for (int k = 0; k < rows; k++) {
            int idx = first + k, y = listTop + k * ROW_H;
            boolean def = idx == 0;
            FontCatalog.Entry e = def ? null : shown.get(idx - 1);
            String id = def ? "" : e.id();
            boolean sel = id.equals(pendingId);
            boolean hover = mouseX >= listX && mouseX < listX + listW && mouseY >= y && mouseY < y + ROW_H - 2;
            PlayerTheme.rrect(g, listX, y, listW, ROW_H - 2, 4, sel ? th.rowCurrent : hover ? th.hover : th.panel2);
            if (sel) g.fill(listX + 1, y + 6, listX + 3, y + ROW_H - 8, th.accent);

            String title = def ? T.get("font.default") : e.title();
            int tx = listX + 10;
            int tagsW = def ? 0 : 56;
            g.text(font, font.plainSubstrByWidth(title, listW - 20 - tagsW), tx, y + 4, sel ? th.accent : PlayerTheme.TEXT_MAIN);
            int tagX = listX + listW - 8;
            if (def) {
                String note = T.get("font.default_note");
                int nw = font.width(note);
                if (nw < listW - 40 - font.width(title)) g.text(font, note, listX + listW - 8 - nw, y + 4, PlayerTheme.TEXT_DIM);
                String t = T.get("font.sample_ru");
                g.text(font, font.plainSubstrByWidth(t, listW - 20), tx, y + 17, PlayerTheme.TEXT_MID);
            } else {
                tagX -= font.width(e.cyrillic() ? "RU" : "EN") + 6;
                tag(g, tagX, y + 3, e.cyrillic() ? "RU" : "EN", e.cyrillic() ? 0x4CAF7A : 0xE0AF68);
                String src = T.get(e.source() == FontCatalog.Source.FOLDER ? "font.tag_own" : "font.tag_sys");
                tagX -= font.width(src) + 9;
                tag(g, tagX, y + 3, src, e.source() == FontCatalog.Source.FOLDER ? 0x7AA2F7 : 0x9A9AAE);
                sample = e.cyrillic() ? T.get("font.sample_ru") : T.get("font.sample_en");
                float size = sel ? v[0] : 10f;
                FontPreview.Slot s = FontPreview.get(e, size * (float) scale * 1.15f, sample);
                if (s != null) {
                    int w = Math.min(listW - 20, Math.round(s.w() / (float) scale)), h = Math.max(1, Math.round(s.h() / (float) scale));
                    g.blit(s.tex(), tx, y + 15, w, h, 0, 0, Math.round(w * (float) scale), Math.round(h * (float) scale), FontPreview.SIZE, 0xFFE8E8F0);
                } else {
                    g.text(font, "…", tx, y + 17, PlayerTheme.TEXT_DIM);
                }
            }
        }
        if (FontCatalog.scanning() || (FontCatalog.entries().isEmpty() && !FontCatalog.scanned())) {
            g.text(font, T.get("font.scanning"), listX + 10, listTop + ROW_H + 8, PlayerTheme.TEXT_DIM);
        } else if (shown.isEmpty()) {
            g.text(font, T.get("font.empty"), listX + 10, listTop + ROW_H + 8, PlayerTheme.TEXT_DIM);
        }
        int max = Math.max(0, total() - fit);
        if (max > 0) {
            int track = fit * ROW_H - 2;
            PlayerTheme.rrect(g, sbX, listTop, BAR_W, track, 2, th.fieldBg);
            int thumb = Math.max(16, track * fit / total());
            PlayerTheme.rrect(g, sbX, listTop + (track - thumb) * first / max, BAR_W, thumb, 2, th.accent);
        }
    }

    private void drawRight(Canvas g) {
        int x = rightX;
        g.text(font, T.get("font.tuning"), x + 2, py + HEADER_H + 6 + 25 + 4, PlayerTheme.TEXT_DIM);   // на уровне ряда фильтров
        int y = listTop;
        boolean custom = !pendingId.isEmpty();
        for (int i = 0; i < 4; i++) {
            int ry = y + i * 24;
            PlayerTheme.rrect(g, x, ry, RIGHT_W, 22, 4, th.panel2);
            g.text(font, font.plainSubstrByWidth(T.get(KEYS[i]), RIGHT_W - 90), x + 7, ry + 7, custom ? PlayerTheme.TEXT_MID : PlayerTheme.TEXT_DIM);
            String val = fmt(v[i]);
            g.text(font, val, x + RIGHT_W - 41 - font.width(val) / 2, ry + 7, custom ? PlayerTheme.TEXT_MAIN : PlayerTheme.TEXT_DIM);
            for (int d = 0; d < 2; d++) {
                int[] r = stepBtn[i * 2 + d];
                r[0] = d == 0 ? x + RIGHT_W - 76 : x + RIGHT_W - 22; r[1] = ry + 3; r[2] = 16; r[3] = 16;
                boolean hover = custom && in(r, mouseX, mouseY);
                PlayerTheme.rrect(g, r[0], r[1], r[2], r[3], 4, hover ? th.hover : th.trackBg);
                g.text(font, d == 0 ? "-" : "+", r[0] + 5, r[1] + 4, custom ? PlayerTheme.TEXT_MAIN : PlayerTheme.TEXT_DIM);
            }
        }
        y += 4 * 24 + 6;
        g.text(font, T.get("font.now"), x + 2, y, PlayerTheme.TEXT_DIM);
        y += 13;
        int boxH = Math.max(34, listBottom - y - 2);
        PlayerTheme.rrect(g, x, y, RIGHT_W, boxH, 4, th.fieldBg);
        // так выглядит текст песен в игре прямо сейчас; длинные строки переносятся, а не обрезаются
        int ty = y + 7, maxY = y + boxH - 10;
        String[] samples = {T.get("font.sample_ru"), T.get("font.sample_en")};
        for (int s = 0; s < samples.length; s++) {
            for (net.minecraft.util.FormattedCharSequence line : font.split(LyricFonts.component(samples[s]), RIGHT_W - 14)) {
                if (ty > maxY) break;
                g.textSeq(font, line, x + 7, ty, s == 0 ? 0xFFFFFFFF : th.accent, true);
                ty += font.lineHeight + 3;
            }
            ty += 4;
        }
    }

    private static String fmt(float f) {
        return f == Math.rint(f) ? String.valueOf((int) f) : String.format(Locale.ROOT, "%.1f", f);
    }

    private void drawFooter(Canvas g) {
        int fy = py + ph - FOOTER_H;
        g.fill(px + 10, fy, px + pw - 10, fy + 1, PlayerTheme.argb(0xFFFFFF, 16));
        String done = T.get("btn.done"), apply = T.get("font.apply");
        int dw = font.width(done) + 24, aw = font.width(apply) + 24;
        doneBtn[0] = px + pw - 10 - dw; doneBtn[1] = fy + 6; doneBtn[2] = dw; doneBtn[3] = 20;
        applyBtn[0] = doneBtn[0] - 6 - aw; applyBtn[1] = fy + 6; applyBtn[2] = aw; applyBtn[3] = 20;
        boolean can = canApply(), hoverDone = in(doneBtn, mouseX, mouseY), hoverApply = can && in(applyBtn, mouseX, mouseY);
        PlayerTheme.rrect(g, doneBtn[0], doneBtn[1], dw, 20, Math.min(th.radius + 1, 10), hoverDone ? th.hover : th.trackBg);
        g.text(font, done, doneBtn[0] + 12, doneBtn[1] + 6, PlayerTheme.TEXT_MAIN);
        PlayerTheme.rrect(g, applyBtn[0], applyBtn[1], aw, 20, Math.min(th.radius + 1, 10), !can ? th.trackBg : hoverApply ? th.accentHover : th.accent);
        g.text(font, apply, applyBtn[0] + 12, applyBtn[1] + 6, can ? PlayerTheme.TEXT_MAIN : PlayerTheme.TEXT_DIM);

        String status; int color = PlayerTheme.TEXT_DIM;
        FontPack.State st = FontPack.state();
        if (st == FontPack.State.WORKING) { status = T.get("font.working"); color = th.accent; }
        else if (st == FontPack.State.ERROR) { status = T.get("pack".equals(FontPack.message()) ? "font.pack_off" : "font.error"); color = PlayerTheme.WARN; }
        else if (changed()) status = T.get("font.pending");
        else status = T.get(pendingId.isEmpty() ? "font.hint_default" : "font.applied");
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.literal(status), applyBtn[0] - px - 24);
        for (int i = 0; i < Math.min(2, lines.size()); i++) g.text(font, lines.get(i), px + 14, fy + 7 + i * (font.lineHeight + 1), color);
    }

    private void syncHits() {
        int fit = rowsFit();
        for (int i = 0; i < rowHits.length; i++) {
            if (i < fit && first + i < total()) Hit.place(rowHits[i], listX, listTop + i * ROW_H, listW, ROW_H - 2, true);
            else Hit.place(rowHits[i], 0, 0, 0, 0, false);
        }
        for (int i = 0; i < filterHits.length; i++) Hit.place(filterHits[i], filterBtn[i], true);
        for (int i = 0; i < stepHits.length; i++) Hit.place(stepHits[i], stepBtn[i], !pendingId.isEmpty());
        Hit.place(folderHit, folderBtn, true);
        Hit.place(refreshHit, refreshBtn, true);
        Hit.place(applyHit, applyBtn, canApply());
        Hit.place(doneHit, doneBtn, true);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (my >= listTop && mx < rightX) {
            int max = Math.max(0, total() - rowsFit());
            first = Math.max(0, Math.min(max, first - (int) Math.signum(sy) * 2));
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public void removed() {
        FontPreview.clear();
        PlayerConfig.get().save();
        super.removed();
    }

    @Override
    public void onClose() {
        closeAnimated(() -> Game.open(parent));
    }

    @Override
    protected boolean animated() { return true; }

    @Override
    public boolean isPauseScreen() { return parent != null && parent.isPauseScreen(); }
}
