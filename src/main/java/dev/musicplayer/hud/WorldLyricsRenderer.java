package dev.musicplayer.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.musicplayer.config.HudConfig;
import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.lyrics.LrcParser;
import dev.musicplayer.model.LyricLine;
import dev.musicplayer.util.T;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * «Летающий» текст песни в игровом мире: каждое слово прилетает в момент, когда его поют, звучащее подсвечено,
 * через пару секунд строка всплывает и растворяется, новые строки вытесняют старые вверх.
 * Тайминги слов — из тегов расширенного LRC, иначе длительность строки делится по длине слов.
 * Режимы: «Рядом с игроком» (держится перед игроком) и «На месте строки» (остаётся там, где появилась).
 * Для треков без таймкодов ничего не рисует — работает обычный HUD-текст (см. {@link #takesOver()}).
 */
public final class WorldLyricsRenderer {
    private WorldLyricsRenderer() {}

    private static final int LIGHT = 0xF000F0;          // полная яркость, независимо от освещения мира
    private static final int ROW_H = 12;
    private static final int MAX_ROW_W = 230;
    private static final float BASE_SCALE = 0.026f;      // блоков на единицу шрифта (у табличек над игроками 0.025)
    private static final long FLY_MS = 380;
    private static final long HOLD_MS = 1500;
    private static final long FADE_MS = 800;
    private static final int C_SUNG = 0xFFFFFF;

    private static final class Word {
        String text;
        FormattedCharSequence seq;
        long startRel, durRel;     // относительно начала строки, мс
        float x, y;                // левый край и центр по вертикали в рядах, единицы шрифта
        int w;
        float sx, sy, sz;
    }

    private static final class Group {
        final int index;
        final boolean follow;
        boolean fixedYaw;          // следует за игроком, но не поворачивается с его взглядом
        double spawnYaw;
        final long lineMs;
        long wallStart;            // момент создания (для подсказок и примера)
        final boolean timed;       // true — время берётся из трека, false — из часов
        final List<Word> words = new ArrayList<>();
        long endRel;
        int rows = 1;
        float lateral, vert;
        double px, py, pz;
        boolean placed;
        float liftCur;             // текущий сдвиг вверх (плавно догоняет цель, поэтому стопка строк не «прыгает»)
        boolean liftInit;
        // режим «вокруг игрока»: одно слово в случайном месте рядом
        boolean scatter;
        double yawOff;             // угол от направления взгляда, рад
        float distK = 1f;          // доля от дистанции
        float tilt, sizeK = 1f;    // наклон (рад) и размер слова
        long hold = HOLD_MS;

        Group(int index, boolean follow, long lineMs, boolean timed) {
            this.index = index; this.follow = follow; this.lineMs = lineMs; this.timed = timed;
            this.wallStart = System.nanoTime();
        }

        long total() { return endRel + hold + FADE_MS; }
    }

    private static final Map<Integer, Group> groups = new HashMap<>();
    /** Режим «вокруг игрока»: слова каждой строки, созданные один раз (место слова не должно меняться, пока оно живо). */
    private static final Map<Integer, List<Group>> scatterLines = new HashMap<>();
    private static final List<Group> extras = new ArrayList<>();
    private static List<LyricLine> lastLines;

    private static long lastRender;
    private static double estPos;
    private static boolean estInit;

    private static float pauseK = 1f;                 // 1 — играет, 0 — пауза (текст плавно растворяется)

    private static Method cameraMethod;
    private static boolean cameraResolved;

    public static void register() {
        LevelRenderEvents.COLLECT_SUBMITS.register(WorldLyricsRenderer::render);
    }

    /**
     * true — текст песни сейчас рисуется в мире, поэтому обычный HUD-текст не нужен. Это так, только если режим
     * включён, мы в мире и у текущего трека есть текст с таймкодами; иначе HUD работает как раньше.
     */
    public static boolean takesOver() {
        if (!HudConfig.get().worldLyrics) return false;
        if (Minecraft.getInstance().player == null) return false;
        PlayerController ctl = PlayerController.I;
        if (ctl.current == null || ctl.idle) return false;
        List<LyricLine> l = ctl.lyrics;
        return !l.isEmpty() && l.get(0).timeMs() != LyricLine.UNSYNCED;
    }

    public static void toast(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.font == null) return;
        extras.removeIf(g -> g.index == -1);
        Group g = new Group(-1, true, 0, false);
        g.vert = -0.35f;
        addWords(g, mc.font, text, 90);
        g.endRel = Math.max(g.endRel, 400);
        extras.add(g);
    }

    public static void demo() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.font == null) return;
        extras.removeIf(g -> g.index == -2 || g.index <= -1000);
        String text = T.get("world.demo");
        if (HudConfig.get().worldLayout == HudConfig.WorldLayout.SCATTER) {
            String[] parts = text.split("\\s+");
            long now = System.nanoTime();
            List<Group> placed = new ArrayList<>();
            for (int k = 0; k < parts.length; k++) {
                Group g = new Group(-1000 - k, true, 0, false);
                Word w = new Word();
                w.text = parts[k];
                w.startRel = 0; w.durRel = 330;
                g.words.add(w);
                finish(g, mc.font);
                makeScatter(g, placed);
                g.wallStart = now + k * 520_000_000L;
                placed.add(g);
                extras.add(g);
            }
            return;
        }
        Group g = new Group(-2, true, 0, false);
        addWords(g, mc.font, text, 330);
        extras.add(g);
    }

    private static void addWords(Group g, Font font, String text, long stepMs) {
        String[] parts = text.trim().split("\\s+");
        long t = 0;
        for (String p : parts) {
            Word w = new Word();
            w.text = p;
            w.startRel = t;
            w.durRel = stepMs;
            g.words.add(w);
            t += stepMs;
        }
        finish(g, font);
    }

    private static Group fromLine(int index, LyricLine line, LyricLine next, boolean follow, boolean fixedYaw, Font font) {
        Group g = new Group(index, follow, line.timeMs(), true);
        g.fixedYaw = fixedYaw;
        long nextGap = next != null && next.timeMs() != LyricLine.UNSYNCED ? Math.max(1, next.timeMs() - line.timeMs()) : 5000;

        if (line.hasWordTimings()) {
            List<LyricLine.Word> src = line.words();
            for (int i = 0; i < src.size(); i++) {
                Word w = new Word();
                w.text = src.get(i).text();
                w.startRel = Math.max(0, src.get(i).timeMs() - line.timeMs());
                long end = i + 1 < src.size() ? src.get(i + 1).timeMs() - line.timeMs()
                        : Math.min(nextGap - 100, w.startRel + 1500);
                w.durRel = Math.max(140, Math.min(2500, end - w.startRel));
                g.words.add(w);
            }
        } else {
            String[] parts = line.text().trim().split("\\s+");
            int chars = 0;
            for (String p : parts) chars += Math.max(1, p.length());
            // поют не всю паузу до следующей строки: оцениваем по длине текста и не вылезаем за следующую строку
            long usable = Math.max(400, Math.min(nextGap - 120, Math.max(1400L, chars * 80L)));
            int before = 0;
            for (String p : parts) {
                int len = Math.max(1, p.length());
                Word w = new Word();
                w.text = p;
                w.startRel = usable * before / chars;
                w.durRel = Math.max(140, usable * len / chars);
                g.words.add(w);
                before += len;
            }
        }
        finish(g, font);
        return g;
    }

    private static void finish(Group g, Font font) {
        int space = Math.max(2, font.width(" "));
        List<List<Word>> rows = new ArrayList<>();
        List<Word> row = new ArrayList<>();
        int cur = 0;
        for (Word w : g.words) {
            w.w = Math.max(1, font.width(w.text));
            w.seq = FormattedCharSequence.forward(w.text, Style.EMPTY);
            if (!row.isEmpty() && cur + space + w.w > MAX_ROW_W) {
                rows.add(row);
                row = new ArrayList<>();
                cur = 0;
            }
            w.x = cur;
            cur += w.w + space;
            row.add(w);
        }
        if (!row.isEmpty()) rows.add(row);

        for (int r = 0; r < rows.size(); r++) {
            List<Word> rw = rows.get(r);
            Word last = rw.get(rw.size() - 1);
            float width = last.x + last.w;
            for (Word w : rw) {
                w.x -= width / 2f;
                w.y = (r - (rows.size() - 1) / 2f) * ROW_H;
            }
        }
        g.rows = Math.max(1, rows.size());

        int seed = g.index * 131;
        for (int i = 0; i < g.words.size(); i++) {
            Word w = g.words.get(i);
            int h = (seed + i * 7919 + 17) * 0x9E3779B1;
            w.sx = ((h >>> 3) & 63) - 32;
            w.sy = 14 + ((h >>> 9) & 31);                // снизу (в единицах шрифта ось Y смотрит вниз)
            w.sz = -(40 + ((h >>> 14) & 31));
            g.endRel = Math.max(g.endRel, w.startRel + w.durRel);
        }

        int h = g.index * 2654435 + 12345;
        g.lateral = (((h >>> 4) & 0xFF) % 5 - 2) * 0.16f;
        if (g.vert == 0f) g.vert = (((h >>> 12) & 0xFF) % 3 - 1) * 0.18f;
    }

    private static final long SCATTER_HOLD_MS = 3200;

    /**
     * Превращает уже собранную группу из одного слова в «разбросанное»: слово висит в паре блоков от игрока —
     * спереди, сбоку, иногда почти за плечом, на разной высоте (вплоть до уровня ног) и на разной дистанции.
     */
    private static void makeScatter(Group g, List<Group> others) {
        g.scatter = true;
        g.hold = SCATTER_HOLD_MS;
        g.lateral = 0f;
        Random rnd = new Random(g.index * 7919L + 12345L);
        double base = HudConfig.get().worldDistance;
        double bestYaw = 0, bestVert = 0, bestK = 0.6, bestScore = -1;
        for (int c = 0; c < 16; c++) {
            // всё в поле зрения, чтобы слова были видны на бегу: спереди и по бокам (до ±57°), ниже уровня глаз
            double sign = rnd.nextBoolean() ? 1 : -1;
            double yo = rnd.nextDouble() < 0.85 ? sign * (0.12 + rnd.nextDouble() * 0.88)
                                                : (rnd.nextDouble() * 2 - 1) * 0.12;
            double vt = -1.35 + Math.pow(rnd.nextDouble(), 1.3) * 1.6;
            double k = 0.45 + rnd.nextDouble() * 0.5;
            double d = base * k;
            double x = -Math.sin(yo) * d, z = Math.cos(yo) * d;
            double score = 10;
            for (Group o : others) {
                if (!o.scatter) continue;
                double od = base * o.distK;
                double ox = -Math.sin(o.yawOff) * od, oz = Math.cos(o.yawOff) * od;
                double dx = x - ox, dz = z - oz, dy = (vt - o.vert) * 1.3;
                score = Math.min(score, Math.sqrt(dx * dx + dz * dz + dy * dy));
            }
            if (score > bestScore) { bestScore = score; bestYaw = yo; bestVert = vt; bestK = k; }
        }
        g.yawOff = bestYaw;
        g.vert = (float) bestVert;
        g.distK = (float) bestK;
        g.tilt = rnd.nextFloat() < 0.55f ? (rnd.nextFloat() * 2 - 1) * 0.28f : (rnd.nextFloat() * 2 - 1) * 0.06f;
        g.sizeK = 0.6f + rnd.nextFloat() * 0.4f;         // чуть мельче обычного: рядом они и так выглядят крупно
    }

    private static List<Group> scatterFromLine(int index, LyricLine line, LyricLine next, boolean follow,
                                               boolean fixedYaw, Font font, Collection<Group> existing) {
        Group whole = fromLine(index, line, next, follow, fixedYaw, font);
        List<Group> out = new ArrayList<>();
        List<Group> placed = new ArrayList<>(existing);
        for (int k = 0; k < whole.words.size(); k++) {
            Word src = whole.words.get(k);
            Group g = new Group(index * 256 + k, follow, line.timeMs() + src.startRel, true);
            g.fixedYaw = fixedYaw;
            Word w = new Word();
            w.text = src.text; w.seq = src.seq; w.w = src.w;
            w.x = -src.w / 2f; w.y = 0;
            w.startRel = 0; w.durRel = Math.max(300, src.durRel);
            w.sx = src.sx; w.sy = src.sy; w.sz = src.sz;
            g.words.add(w);
            g.endRel = w.durRel;
            g.rows = 1;
            makeScatter(g, placed);
            placed.add(g);
            out.add(g);
        }
        return out;
    }

    private static void render(LevelRenderContext ctx) {
        long now = System.nanoTime();
        if (now - lastRender < 500_000L) return;     // событие может прийти дважды за кадр
        float dt = lastRender == 0 ? 0.016f : Math.min(0.1f, (now - lastRender) / 1_000_000_000f);
        lastRender = now;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.font == null) { groups.clear(); scatterLines.clear(); extras.clear(); return; }
        PoseStack pose = ctx.poseStack();
        SubmitNodeCollector out = ctx.submitNodeCollector();
        if (pose == null || out == null) return;

        Camera camera = cameraOf(ctx.gameRenderer());
        Vec3 cam = camera != null ? camera.position() : mc.player.getEyePosition();
        HudConfig cfg = HudConfig.get();
        double yaw = Math.toRadians(mc.player.getYRot());
        Font font = mc.font;

        if (takesOver()) drawTrackLyrics(pose, out, font, cam, yaw, cfg, dt, now);
        else { groups.clear(); scatterLines.clear(); estInit = false; }

        for (int i = extras.size() - 1; i >= 0; i--) {
            Group g = extras.get(i);
            long rel = (now - g.wallStart) / 1_000_000L;
            if (rel > g.total()) { extras.remove(i); continue; }
            draw(pose, out, font, g, rel, 0, cam, yaw, cfg, dt);
        }
    }

    private static void drawTrackLyrics(PoseStack pose, SubmitNodeCollector out, Font font, Vec3 cam, double yaw,
                                        HudConfig cfg, float dt, long now) {
        PlayerController ctl = PlayerController.I;
        List<LyricLine> lines = ctl.lyrics;
        if (lines != lastLines) { lastLines = lines; groups.clear(); scatterLines.clear(); estInit = false; }

        // позиция трека: опрашиваем плеер, но между опросами идём по часам — слова не дёргаются
        long polled = ctl.engine().positionMs();
        if (!estInit || Math.abs(polled - estPos) > 300) { estPos = polled; estInit = true; }
        else {
            if (ctl.engine().isPlaying()) estPos += dt * 1000.0;
            estPos += (polled - estPos) * Math.min(1.0, dt * 6.0);
        }
        long pos = Math.round(estPos);

        // пауза: строка не замирает, а плавно исчезает; при возобновлении так же плавно возвращается
        boolean playing = ctl.engine().isPlaying();
        pauseK = playing ? Math.min(1f, pauseK + dt / 0.35f) : Math.max(0f, pauseK - dt / 0.6f);

        int cur = LrcParser.currentIndex(lines, pos);
        if (cur < 0) { groups.clear(); scatterLines.clear(); return; }
        long spos = pos - cfg.lyricsShiftMs;

        List<Group> active = new ArrayList<>();
        java.util.Set<Integer> keep = new java.util.HashSet<>();
        boolean scatter = cfg.worldLayout == HudConfig.WorldLayout.SCATTER;
        boolean follow = cfg.worldMode == HudConfig.WorldMode.FOLLOW;
        // слова вокруг игрока остаются на своих местах в мире (иначе слова сбоку и сзади крутились бы вместе с головой)
        boolean fixedYaw = follow && (scatter || cfg.worldFollowStyle == HudConfig.FollowStyle.WORLD);
        if (scatter) scatterLines.keySet().removeIf(k -> k < cur - 8 || k > cur);
        for (int i = Math.max(0, cur - (scatter ? 7 : 4)); i <= cur; i++) {
            LyricLine line = lines.get(i);
            if (line.text() == null || line.text().isBlank() || line.timeMs() == LyricLine.UNSYNCED) continue;
            LyricLine next = i + 1 < lines.size() ? lines.get(i + 1) : null;
            if (scatter) {
                List<Group> made = scatterLines.get(i);
                if (made == null || made.get(0).follow != follow || made.get(0).fixedYaw != fixedYaw) {
                    List<Group> existing = new ArrayList<>();
                    for (List<Group> l : scatterLines.values())
                        for (Group o : l) if (spos - o.lineMs <= o.total()) existing.add(o);
                    made = scatterFromLine(i, line, next, follow, fixedYaw, font, existing);
                    scatterLines.put(i, made);
                }
                for (Group g : made) {
                    if (spos - g.lineMs > g.total()) continue;
                    active.add(g);
                }
                continue;
            }
            Group g = groups.get(i);
            if (g == null || g.follow != follow || g.fixedYaw != fixedYaw) {
                g = fromLine(i, line, next, follow, fixedYaw, font);
                groups.put(i, g);
            }
            if (spos - g.lineMs > g.total()) continue;
            keep.add(i);
            active.add(g);
        }
        groups.keySet().retainAll(keep);
        if (!scatter) scatterLines.clear();

        if (scatter) {
            for (Group g : active) draw(pose, out, font, g, spos - g.lineMs, 0f, cam, yaw, cfg, dt);
            return;
        }
        // стопка строк: каждая более старая стоит выше более новой; сдвиг не прыгает, а плавно догоняет цель (см. draw)
        float acc = 0f;
        for (int k = active.size() - 1; k >= 0; k--) {
            Group g = active.get(k);
            if (k < active.size() - 1) {
                Group newer = active.get(k + 1);
                acc += (newer.rows + g.rows) / 2f * ROW_H + 8;
            }
            draw(pose, out, font, g, spos - g.lineMs, -acc, cam, yaw, cfg, dt);
        }
    }

    /**
     * Точка в воздухе перед игроком, куда «хочет» эта группа. Блоки её не двигают: у стены текст остаётся на своём
     * расстоянии и прячется за ней (при выключенном «сквозь блоки»). Раньше его подтягивало к камере, и он
     * разрастался на весь экран.
     */
    private static Vec3 target(Group g, Vec3 cam, double yaw, HudConfig cfg) {
        double dist = cfg.worldDistance * g.distK;
        double ang = yaw + g.yawOff;
        double fx = -Math.sin(ang), fz = Math.cos(ang);
        double rx = -Math.cos(ang), rz = -Math.sin(ang);
        double lat = g.lateral * dist;
        return new Vec3(cam.x + fx * dist + rx * lat,
                cam.y + cfg.worldHeight + g.vert,
                cam.z + fz * dist + rz * lat);
    }

    private static void draw(PoseStack pose, SubmitNodeCollector out, Font font, Group g, long rel, float stackLift,
                             Vec3 cam, double yaw, HudConfig cfg, float dt) {
        if (rel < 0) return;

        if (!g.placed) g.spawnYaw = yaw;
        Vec3 t = target(g, cam, g.fixedYaw ? g.spawnYaw : yaw, cfg);
        if (!g.placed) { g.px = t.x; g.py = t.y; g.pz = t.z; g.placed = true; }
        else if (g.follow) {
            double a = 1.0 - Math.exp(-dt * (g.scatter ? 12.0 : 7.0));     // слова вокруг догоняют бегущего игрока плотнее
            double ay = 1.0 - Math.exp(-dt * 2.6);
            g.px += (t.x - g.px) * a;
            g.py += (t.y - g.py) * ay;
            g.pz += (t.z - g.pz) * a;
        }

        float exit = clamp01((rel - g.endRel - g.hold) / (float) FADE_MS);
        float fade = 1f - exit * exit;
        if (g.timed) fade *= pauseK * pauseK * (3f - 2f * pauseK);
        if (fade <= 0.02f) return;
        if (!g.liftInit) { g.liftCur = stackLift; g.liftInit = true; }
        else g.liftCur += (stackLift - g.liftCur) * (1f - (float) Math.exp(-dt * 6.0));
        float lift = g.liftCur - 14f * easeOutCubic(exit);   // вверх = минус по Y шрифта

        float s = BASE_SCALE * cfg.worldScale * g.sizeK;
        double wall = System.nanoTime() / 1_000_000_000.0;
        Font.DisplayMode mode = cfg.worldSeeThrough ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL;
        int accent = cfg.accent.rgb;

        pose.pushPose();
        pose.translate((float) (g.px - cam.x), (float) (g.py - cam.y), (float) (g.pz - cam.z));
        float face = (float) Math.atan2(cam.x - g.px, cam.z - g.pz);
        pose.mulPose(new Matrix4f().rotationY(face));
        if (g.scatter) pose.mulPose(new Matrix4f().rotationZ(g.tilt + 0.05f * (float) Math.sin(wall * 1.1 + g.index * 0.9)));
        pose.scale(s, -s, s);

        for (int i = 0; i < g.words.size(); i++) {
            Word w = g.words.get(i);
            long tw = rel - w.startRel;
            if (tw < 0) continue;

            float p = clamp01(tw / (float) FLY_MS);
            float e = easeOutCubic(p);
            float k = 0.55f + 0.45f * easeOutBack(p);
            boolean sungNow = tw < w.durRel;
            if (sungNow) k *= 1f + 0.09f * (float) Math.sin(Math.PI * tw / (double) w.durRel);

            float alpha = Math.min(1f, p * 1.8f) * fade;
            if (alpha < 0.04f) continue;                                       // при очень малой альфе шрифт рисует непрозрачно

            float bob = (float) Math.sin(wall * 2.2 + g.index * 1.3 + i * 0.7) * 1.3f * e;
            float cx = w.x + w.w / 2f + w.sx * (1f - e);
            float cy = w.y + lift + bob + w.sy * (1f - e);
            float cz = w.sz * (1f - e);

            int rgb = sungNow ? accent : C_SUNG;
            int color = (Math.round(alpha * 255f) << 24) | (rgb & 0xFFFFFF);

            pose.pushPose();
            pose.translate(cx, cy, cz);
            pose.scale(k, k, k);
            out.submitText(pose, -w.w / 2f, -4.5f, w.seq, cfg.worldShadow, mode, LIGHT, color, 0, 0);
            pose.popPose();
        }
        pose.popPose();
    }

    /** Камера главного вида. Имя метода менялось между версиями, поэтому ищем его по типу результата один раз. */
    private static Camera cameraOf(GameRenderer renderer) {
        if (renderer == null) return null;
        try {
            if (!cameraResolved) {
                cameraResolved = true;
                for (Method m : GameRenderer.class.getMethods()) {
                    if (m.getParameterCount() == 0 && Camera.class.isAssignableFrom(m.getReturnType())
                            && (m.getName().equals("getMainCamera") || m.getName().equals("mainCamera"))) {
                        cameraMethod = m;
                        break;
                    }
                }
                if (cameraMethod == null) {
                    for (Method m : GameRenderer.class.getMethods()) {
                        if (m.getParameterCount() == 0 && Camera.class.isAssignableFrom(m.getReturnType())) {
                            cameraMethod = m;
                            break;
                        }
                    }
                }
            }
            if (cameraMethod != null) return (Camera) cameraMethod.invoke(renderer);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }

    private static float easeOutCubic(float t) { float u = 1f - clamp01(t); return 1f - u * u * u; }

    private static float easeOutBack(float t) {
        t = clamp01(t);
        float c1 = 1.70158f, c3 = c1 + 1f, u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }
}
