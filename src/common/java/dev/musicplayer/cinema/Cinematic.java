package dev.musicplayer.cinema;

import dev.musicplayer.compat.Canvas;
import dev.musicplayer.compat.Game;
import dev.musicplayer.config.HudConfig;
import dev.musicplayer.viz.Visualizer;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Режим «автосалон»: интерфейс уезжает, камера вылетает из глаз игрока и снимает его с меняющихся ракурсов
 * от третьего лица. Длина кадра, скорость и склейки зависят от музыки (удары берутся из спектра визуализатора).
 */
public final class Cinematic {
    private Cinematic() {}

    private enum Phase { OFF, ENTER, ON, EXIT }

    public record View(Vec3 pos, float yaw, float pitch) {}

    private static final float ENTER_S = 1.6f, EXIT_S = 1.2f, HUD_S = 0.6f, BLEND_S = 1.1f;
    private static final Random RND = new Random();
    private static final Visualizer VIZ = new Visualizer();
    private static final HudConfig.CineShot[] ALL = {
            HudConfig.CineShot.ORBIT, HudConfig.CineShot.LOW, HudConfig.CineShot.HIGH,
            HudConfig.CineShot.DOLLY, HudConfig.CineShot.CRANE, HudConfig.CineShot.TRACK};

    private static Phase phase = Phase.OFF;
    private static float phaseT;
    private static long lastNs;
    private static CameraType savedType;
    private static boolean savedHideGui;
    private static Shot shot, prevShot;
    private static float blend = 1f;
    private static float hud;
    private static float fovOffset;
    private static float energy, bassAvg, pulse, punch, clock;
    private static float sinceBeat = 9f;

    public static boolean active() { return phase != Phase.OFF; }

    /** Насколько спрятан интерфейс: 0 — на месте, 1 — убран. */
    public static float hudHidden() { return hud; }

    public static void toggle() {
        if (phase != Phase.OFF && phase != Phase.EXIT) {
            phase = Phase.EXIT;
            phaseT = 0;
        } else {
            start();
        }
    }

    private static void start() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;
        if (phase == Phase.OFF) {
            savedType = mc.options.getCameraType();
            savedHideGui = Game.hudHidden();
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            // первый кадр начинается за плечом, чтобы было видно, как камера вылетает из головы
            double yaw = Math.toRadians(p.getYRot());
            shot = newShot(null, (float) (Math.PI - yaw + 0.6));
            prevShot = null;
            blend = 1f;
        }
        phase = Phase.ENTER;
        phaseT = 0;
        lastNs = 0;
    }

    private static void finish() {
        phase = Phase.OFF;
        hud = 0;
        fovOffset = 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null) {
            if (savedType != null) mc.options.setCameraType(savedType);
            Game.setHudHidden(savedHideGui);
        }
        savedType = null;
    }

    /** Раз в тик: выходим, если игрок пошёл, или игрока больше нет. */
    public static void tick() {
        if (phase == Phase.OFF) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            finish();
        } else if (HudConfig.get().cineExitOnMove && phase != Phase.EXIT && Game.screen() == null && moving(mc.options)) {
            toggle();
        }
    }

    private static boolean moving(Options o) {
        return o.keyUp.isDown() || o.keyDown.isDown() || o.keyLeft.isDown() || o.keyRight.isDown()
                || o.keyJump.isDown() || o.keyAttack.isDown() || o.keyUse.isDown();
    }

    /** Обычный FOV плюс объектив кадра и «удар» от музыки. */
    public static float fov(float vanilla) { return Math.max(10f, vanilla + fovOffset); }

    /** Чёрные полосы сверху и снизу, пока интерфейс убран. */
    public static void drawBars(Canvas c) {
        float h = hud;
        if (h <= 0f || !HudConfig.get().cineBars) return;
        float e = h * h * (3f - 2f * h);
        int bh = Math.round(c.screenHeight() * 0.11f * e);
        c.fill(0, 0, c.screenWidth(), bh, 0xFF000000);
        c.fill(0, c.screenHeight() - bh, c.screenWidth(), c.screenHeight(), 0xFF000000);
    }

    /** Зовётся камерой каждый кадр: откуда снимать, или null — камеру не трогаем. */
    public static View frame(float partial) {
        if (phase == Phase.OFF) return null;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) {
            finish();
            return null;
        }

        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : Math.min(0.1f, (now - lastNs) / 1e9f);
        lastNs = now;
        phaseT += dt;

        HudConfig cfg = HudConfig.get();
        float k = cfg.cineMusic ? cfg.cineMusicStrength / 100f : 0f;
        boolean beat = listen(dt);
        float speed = cfg.cineSpeed / 100f * Math.max(0.35f, Math.min(2.5f, 1f + (energy - 0.35f) * 1.3f * k));
        shot.t += dt * speed;
        shot.age += dt;
        if (prevShot != null) {
            prevShot.t += dt * speed;
            blend = Math.min(1f, blend + dt / BLEND_S);
        }

        float minS = cfg.cineShotSec * (k > 0 ? 0.6f : 1f);
        float maxS = cfg.cineShotSec * (k > 0 ? 1.7f : 1f);
        if (phase != Phase.EXIT && (shot.age >= maxS || k > 0 && beat && shot.age >= minS)) {
            boolean smooth = cfg.cineCuts == HudConfig.CineCuts.SMOOTH;
            prevShot = smooth ? shot : null;
            shot = newShot(shot, shot.a0 + (float) (Math.PI * (0.55 + RND.nextFloat() * 0.9)));
            blend = smooth ? 0f : 1f;
        }

        Vec3 target = p.getPosition(partial).add(0, p.getEyeHeight() * 0.72, 0);
        double feet = -p.getEyeHeight() * 0.72;
        double playerYaw = Math.toRadians(p.getYRot());
        Vec3[] cur = shot.eval(target, cfg, feet, playerYaw);
        Vec3 pos = cur[0], look = cur[1];
        if (prevShot != null && blend < 1f) {
            Vec3[] old = prevShot.eval(target, cfg, feet, playerYaw);
            float e = smooth(blend);
            pos = old[0].lerp(pos, e);
            look = old[1].lerp(look, e);
        }

        // на кике камера подаётся вперёд и чуть покачивается; punch — сглаженный кик, поэтому движение
        // нарастает и затихает, а не рвётся (случайная тряска каждый кадр давала дёрганье)
        clock += dt;
        double sway = 0.035 * punch * k;
        pos = target.add(pos.subtract(target).scale(1.0 - 0.04 * punch * k));
        pos = pos.add((Math.sin(clock * 11) + Math.sin(clock * 6.3)) * 0.5 * sway,
                (Math.sin(clock * 9.1 + 1.3) + Math.sin(clock * 4.7)) * 0.5 * sway, 0);
        pos = unblock(p, target, pos);

        Vec3 eye = p.getEyePosition(partial);
        Vec3 eyeLook = eye.add(p.getViewVector(partial).scale(8));
        float into;
        if (phase == Phase.ENTER) {
            into = smooth(Math.min(1f, phaseT / ENTER_S));
            hud = smooth(Math.min(1f, phaseT / HUD_S));
            if (phaseT >= ENTER_S) phase = Phase.ON;
        } else if (phase == Phase.EXIT) {
            into = 1f - smooth(Math.min(1f, phaseT / EXIT_S));
            hud = 1f - smooth(Math.max(0f, Math.min(1f, (phaseT - (EXIT_S - HUD_S)) / HUD_S)));
            if (phaseT >= EXIT_S) {
                finish();
                return null;
            }
        } else {
            into = 1f;
            hud = 1f;
        }
        Game.setHudHidden(savedHideGui || hud > 0.3f);

        pos = eye.lerp(pos, into);
        look = eyeLook.lerp(look, into);
        fovOffset = (cfg.cineFov - 3.5f * punch * k) * into;
        Vec3 d = look.subtract(pos);
        double flat = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) Math.toDegrees(-Math.atan2(d.y, flat));
        return new View(pos, yaw, pitch);
    }

    /** Спектр в детектор ударов; true — сейчас был кик. */
    private static boolean listen(float dt) {
        VIZ.update(8, 100, 20, true, false, false);
        sinceBeat += dt;
        pulse *= (float) Math.exp(-dt * 4.5f);
        // мягкая атака (~100 мс), потом спад следует за затухающим импульсом
        punch += (pulse - punch) * (1f - (float) Math.exp(-dt * 10f));
        float[] lv = VIZ.levels();
        if (!VIZ.live()) {
            energy += (0.35f - energy) * Math.min(1f, dt * 2f);
            return false;
        }

        float sum = 0;
        for (float v : lv) sum += v;
        energy += (sum / lv.length - energy) * Math.min(1f, dt * 3f);
        float bass = Math.max(lv[0], lv[1]);
        boolean kick = bass > 0.45f && bass - bassAvg > 0.16f && sinceBeat > 0.28f;
        bassAvg += (bass - bassAvg) * Math.min(1f, dt * 2.5f);
        if (kick) {
            sinceBeat = 0;
            pulse = 1;
        }
        return kick;
    }

    /** Держит камеру по свою сторону стен и потолка. */
    private static Vec3 unblock(LocalPlayer p, Vec3 target, Vec3 pos) {
        BlockHitResult hit = p.level().clip(new ClipContext(target, pos, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p));
        if (hit.getType() == HitResult.Type.MISS) return pos;
        Vec3 dir = pos.subtract(target);
        double len = dir.length();
        double keep = Math.max(0.6, target.distanceTo(hit.getLocation()) - 0.3);
        return len < 1e-4 ? pos : target.add(dir.scale(keep / len));
    }

    private static Shot newShot(Shot prev, float angle) {
        HudConfig.CineShot type = HudConfig.get().cineShots;
        if (type == HudConfig.CineShot.MIX) {
            do {
                type = ALL[RND.nextInt(ALL.length)];
            } while (prev != null && type == prev.type);
        }
        Shot s = new Shot();
        s.type = type;
        s.a0 = angle;
        s.dir = RND.nextBoolean() ? 1f : -1f;
        return s;
    }

    private static float smooth(float t) { return t * t * t * (t * (t * 6f - 15f) + 10f); }

    private static final class Shot {
        HudConfig.CineShot type;
        float a0, dir, t, age;

        /** Позиция камеры и точка, на которую она смотрит, в текущий момент кадра. */
        Vec3[] eval(Vec3 target, HudConfig cfg, double feet, double playerYaw) {
            double dist = cfg.cineDistance, h = cfg.cineHeight;
            Vec3 pos;
            Vec3 look = target;
            switch (type) {
                case LOW -> {
                    double ang = a0 + dir * t * 0.22;
                    pos = target.add(Math.sin(ang) * dist * 0.75, feet + 0.35, Math.cos(ang) * dist * 0.75);
                    look = target.add(0, 0.35, 0);
                }
                case HIGH -> {
                    double ang = a0 + dir * t * 0.15;
                    pos = target.add(Math.sin(ang) * dist * 0.55, dist * 0.9 + h, Math.cos(ang) * dist * 0.55);
                }
                case DOLLY -> {
                    // медленный наезд на лицо спереди: игрок смотрит вдоль (-sin yaw, cos yaw), камера стоит на этой линии
                    double ang = playerYaw + dir * 0.35;
                    double d = dist * Math.max(0.45, 1.5 - 0.11 * t);
                    pos = target.add(-Math.sin(ang) * d, h * 0.25, Math.cos(ang) * d);
                }
                case CRANE -> {
                    double ang = a0 + dir * t * 0.08;
                    double rise = Math.min(1.0, t / 8.0);
                    rise = rise * rise * (3 - 2 * rise);
                    pos = target.add(Math.sin(ang) * dist, feet + 0.3 + (dist * 0.8 + h - feet) * rise, Math.cos(ang) * dist);
                }
                case TRACK -> {
                    // боковой проезд мимо игрока, он остаётся в кадре
                    double fx = Math.sin(a0), fz = Math.cos(a0);
                    double slide = dist * 0.9 * (-1 + Math.min(2.0, t * 0.12)) * dir;
                    pos = target.add(fx * dist * 0.85 + fz * slide, h * 0.3, fz * dist * 0.85 - fx * slide);
                    look = target.add(fz * slide * 0.25, 0, -fx * slide * 0.25);
                }
                default -> {
                    double ang = a0 + dir * t * 0.35;
                    pos = target.add(Math.sin(ang) * dist, h * 0.5, Math.cos(ang) * dist);
                }
            }
            return new Vec3[]{pos, look};
        }
    }
}
