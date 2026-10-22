package dev.musicplayer.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.musicplayer.compat.CameraPos;
import dev.musicplayer.compat.WorldText;
import dev.musicplayer.config.HudConfig;
import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.lyrics.LrcParser;
import dev.musicplayer.model.LyricLine;
import dev.musicplayer.util.T;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class WorldLyricsRenderer {
   private static final int ROW_H = 12;
   private static final int MAX_ROW_W = 230;
   private static final float BASE_SCALE = 0.026F;
   private static final long FLY_MS = 380L;
   private static final long HOLD_MS = 1500L;
   private static final long FADE_MS = 800L;
   private static final int C_SUNG = 16777215;
   private static final Map<Integer, WorldLyricsRenderer.Group> groups = new HashMap<>();
   private static final Map<Integer, List<WorldLyricsRenderer.Group>> scatterLines = new HashMap<>();
   private static final List<WorldLyricsRenderer.Group> extras = new ArrayList<>();
   private static final List<WorldLyricsRenderer.Debris> debris = new ArrayList<>();
   private static final int MAX_DEBRIS = 900;
   private static final float GRAVITY = 11.0F;
   private static List<LyricLine> lastLines;
   private static long lastRender;
   private static double estPos;
   private static boolean estInit;
   /** Камера не прыгает на столько блоков за кадр без телепорта, смерти или смены мира. */
   private static final double TELEPORT_SQ = 32 * 32;
   private static Object lastPlayer;
   private static Vec3 lastCam;
   private static float pauseK = 1.0F;
   private static final long SCATTER_HOLD_MS = 3200L;
   private static boolean seeNow;
   private static final long OCCLUSION_CHECK_NS = 150_000_000L;

   private WorldLyricsRenderer() {
   }

   /** Летающий текст есть и обычный HUD-текст из-за него прячется. */
   public static boolean hidesHud() {
      return takesOver() && !HudConfig.get().lyricsWithWorld;
   }

   public static boolean takesOver() {
      if (!HudConfig.get().worldLyrics) {
         return false;
      } else if (Minecraft.getInstance().player == null) {
         return false;
      } else {
         PlayerController ctl = PlayerController.I;
         if (ctl.current != null && !ctl.idle) {
            List<LyricLine> l = ctl.lyrics;
            return !l.isEmpty() && l.get(0).timeMs() != Long.MAX_VALUE;
         } else {
            return false;
         }
      }
   }

   public static void toast(String text) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.font != null) {
         extras.removeIf(gx -> gx.index == -1);
         WorldLyricsRenderer.Group g = new WorldLyricsRenderer.Group(-1, true, 0L, false);
         g.vert = -0.35F;
         addWords(g, mc.font, text, 90L);
         g.endRel = Math.max(g.endRel, 400L);
         extras.add(g);
      }
   }

   public static void demo() {
      Minecraft mc = Minecraft.getInstance();
      if (mc.font != null) {
         extras.removeIf(gx -> gx.index == -2 || gx.index <= -1000);
         String text = T.get("world.demo");
         if (HudConfig.get().worldLayout != HudConfig.WorldLayout.SCATTER) {
            WorldLyricsRenderer.Group g = new WorldLyricsRenderer.Group(-2, true, 0L, false);
            addWords(g, mc.font, text, 330L);
            extras.add(g);
         } else {
            String[] parts = text.split("\\s+");
            long now = System.nanoTime();
            List<WorldLyricsRenderer.Group> placed = new ArrayList<>();

            for (int k = 0; k < parts.length; k++) {
               WorldLyricsRenderer.Group g = new WorldLyricsRenderer.Group(-1000 - k, true, 0L, false);
               WorldLyricsRenderer.Word w = new WorldLyricsRenderer.Word();
               w.text = parts[k];
               w.startRel = 0L;
               w.durRel = 330L;
               g.words.add(w);
               finish(g, mc.font);
               makeScatter(g, placed);
               g.wallStart = now + k * 520000000L;
               placed.add(g);
               extras.add(g);
            }
         }
      }
   }

   private static void addWords(WorldLyricsRenderer.Group g, Font font, String text, long stepMs) {
      String[] parts = text.trim().split("\\s+");
      long t = 0L;

      for (String p : parts) {
         WorldLyricsRenderer.Word w = new WorldLyricsRenderer.Word();
         w.text = p;
         w.startRel = t;
         w.durRel = stepMs;
         g.words.add(w);
         t += stepMs;
      }

      finish(g, font);
   }

   private static WorldLyricsRenderer.Group fromLine(int index, LyricLine line, LyricLine next, boolean follow, boolean fixedYaw, Font font) {
      WorldLyricsRenderer.Group g = new WorldLyricsRenderer.Group(index, follow, line.timeMs(), true);
      g.fixedYaw = fixedYaw;
      long nextGap = next != null && next.timeMs() != Long.MAX_VALUE ? Math.max(1L, next.timeMs() - line.timeMs()) : 5000L;
      if (line.hasWordTimings()) {
         List<LyricLine.Word> src = line.words();

         for (int i = 0; i < src.size(); i++) {
            WorldLyricsRenderer.Word w = new WorldLyricsRenderer.Word();
            w.text = src.get(i).text();
            w.startRel = Math.max(0L, src.get(i).timeMs() - line.timeMs());
            long end = i + 1 < src.size() ? src.get(i + 1).timeMs() - line.timeMs() : Math.min(nextGap - 100L, w.startRel + 1500L);
            w.durRel = Math.max(140L, Math.min(2500L, end - w.startRel));
            g.words.add(w);
         }
      } else {
         String[] parts = line.text().trim().split("\\s+");
         int chars = 0;

         for (String p : parts) {
            chars += Math.max(1, p.length());
         }

         long usable = Math.max(400L, Math.min(nextGap - 120L, Math.max(1400L, chars * 80L)));
         int before = 0;

         for (String p : parts) {
            int len = Math.max(1, p.length());
            WorldLyricsRenderer.Word w = new WorldLyricsRenderer.Word();
            w.text = p;
            w.startRel = usable * before / chars;
            w.durRel = Math.max(140L, usable * len / chars);
            g.words.add(w);
            before += len;
         }
      }

      finish(g, font);
      return g;
   }

   private static void finish(WorldLyricsRenderer.Group g, Font font) {
      int space = Math.max(2, font.width(" "));
      List<List<WorldLyricsRenderer.Word>> rows = new ArrayList<>();
      List<WorldLyricsRenderer.Word> row = new ArrayList<>();
      int cur = 0;

      for (WorldLyricsRenderer.Word w : g.words) {
         w.w = Math.max(1, font.width(w.text));
         w.seq = FormattedCharSequence.forward(w.text, Style.EMPTY);
         w.glyphs = glyphs(w.text, font);
         if (!row.isEmpty() && cur + space + w.w > 230) {
            rows.add(row);
            row = new ArrayList<>();
            cur = 0;
         }

         w.x = cur;
         cur += w.w + space;
         row.add(w);
      }

      if (!row.isEmpty()) {
         rows.add(row);
      }

      for (int r = 0; r < rows.size(); r++) {
         List<WorldLyricsRenderer.Word> rw = rows.get(r);
         WorldLyricsRenderer.Word last = rw.get(rw.size() - 1);
         float width = last.x + last.w;

         for (WorldLyricsRenderer.Word w : rw) {
            w.x -= width / 2.0F;
            w.y = (r - (rows.size() - 1) / 2.0F) * 12.0F;
         }
      }

      g.rows = Math.max(1, rows.size());
      g.minX = Float.MAX_VALUE;
      g.maxX = -Float.MAX_VALUE;

      for (WorldLyricsRenderer.Word w : g.words) {
         g.minX = Math.min(g.minX, w.x);
         g.maxX = Math.max(g.maxX, w.x + w.w);
      }

      int seed = g.index * 131;

      for (int i = 0; i < g.words.size(); i++) {
         WorldLyricsRenderer.Word w = g.words.get(i);
         int h = (seed + i * 7919 + 17) * -1640531535;
         w.sx = (h >>> 3 & 63) - 32;
         w.sy = 14 + (h >>> 9 & 31);
         w.sz = -(40 + (h >>> 14 & 31));
         g.endRel = Math.max(g.endRel, w.startRel + w.durRel);
      }

      int h = g.index * 2654435 + 12345;
      g.lateral = ((h >>> 4 & 0xFF) % 5 - 2) * 0.16F;
      if (g.vert == 0.0F) {
         g.vert = ((h >>> 12 & 0xFF) % 3 - 1) * 0.18F;
      }
   }

   private static List<WorldLyricsRenderer.Glyph> glyphs(String text, Font font) {
      List<WorldLyricsRenderer.Glyph> out = new ArrayList<>();
      int x = 0;
      int i = 0;

      while (i < text.length()) {
         int cp = text.codePointAt(i);
         String ch = new String(Character.toChars(cp));
         int cw = font.width(ch);
         WorldLyricsRenderer.Glyph gl = new WorldLyricsRenderer.Glyph();
         gl.seq = FormattedCharSequence.forward(ch, Style.EMPTY);
         gl.w = Math.max(1, cw);
         gl.x = x + cw / 2.0F;
         out.add(gl);
         x += cw;
         i += Character.charCount(cp);
      }

      return out;
   }

   private static void makeScatter(WorldLyricsRenderer.Group g, List<WorldLyricsRenderer.Group> others) {
      g.scatter = true;
      g.hold = 3200L;
      g.lateral = 0.0F;
      Random rnd = new Random(g.index * 7919L + 12345L);
      double base = HudConfig.get().worldDistance;
      double span = Math.max(0.2, Math.toRadians(HudConfig.get().worldSpread));
      double bestYaw = 0.0;
      double bestVert = 0.0;
      double bestK = 0.6;
      double bestScore = -1.0;

      for (int c = 0; c < 16; c++) {
         double sign = rnd.nextBoolean() ? 1.0 : -1.0;
         double yo = rnd.nextDouble() < 0.85 ? sign * (0.12 + rnd.nextDouble() * (span - 0.12)) : (rnd.nextDouble() * 2.0 - 1.0) * 0.12;
         double vt = -1.35 + Math.pow(rnd.nextDouble(), 1.3) * 1.6;
         double k = 0.45 + rnd.nextDouble() * 0.5;
         double d = base * k;
         double x = -Math.sin(yo) * d;
         double z = Math.cos(yo) * d;
         double score = 10.0;

         for (WorldLyricsRenderer.Group o : others) {
            if (o.scatter) {
               double od = base * o.distK;
               double ox = -Math.sin(o.yawOff) * od;
               double oz = Math.cos(o.yawOff) * od;
               double dx = x - ox;
               double dz = z - oz;
               double dy = (vt - o.vert) * 1.3;
               score = Math.min(score, Math.sqrt(dx * dx + dz * dz + dy * dy));
            }
         }

         if (score > bestScore) {
            bestScore = score;
            bestYaw = yo;
            bestVert = vt;
            bestK = k;
         }
      }

      g.yawOff = bestYaw;
      g.vert = (float)bestVert;
      g.distK = (float)bestK;
      g.tilt = rnd.nextFloat() < 0.55F ? (rnd.nextFloat() * 2.0F - 1.0F) * 0.28F : (rnd.nextFloat() * 2.0F - 1.0F) * 0.06F;
      g.sizeK = 0.6F + rnd.nextFloat() * 0.4F;
   }

   private static List<WorldLyricsRenderer.Group> scatterFromLine(
      int index, LyricLine line, LyricLine next, boolean follow, boolean fixedYaw, Font font, Collection<WorldLyricsRenderer.Group> existing
   ) {
      WorldLyricsRenderer.Group whole = fromLine(index, line, next, follow, fixedYaw, font);
      List<WorldLyricsRenderer.Group> out = new ArrayList<>();
      List<WorldLyricsRenderer.Group> placed = new ArrayList<>(existing);

      for (int k = 0; k < whole.words.size(); k++) {
         WorldLyricsRenderer.Word src = whole.words.get(k);
         WorldLyricsRenderer.Group g = new WorldLyricsRenderer.Group(index * 256 + k, follow, line.timeMs() + src.startRel, true);
         g.fixedYaw = fixedYaw;
         WorldLyricsRenderer.Word w = new WorldLyricsRenderer.Word();
         w.text = src.text;
         w.seq = src.seq;
         w.glyphs = src.glyphs;
         w.w = src.w;
         w.x = -src.w / 2.0F;
         w.y = 0.0F;
         w.startRel = 0L;
         w.durRel = Math.max(300L, src.durRel);
         w.sx = src.sx;
         w.sy = src.sy;
         w.sz = src.sz;
         g.words.add(w);
         g.endRel = w.durRel;
         g.rows = 1;
         g.minX = w.x;
         g.maxX = w.x + w.w;
         makeScatter(g, placed);
         placed.add(g);
         out.add(g);
      }

      return out;
   }

   public static void render(PoseStack pose, WorldText out) {
      long now = System.nanoTime();
      if (now - lastRender < 500000L) {
         return; // событие может прийти дважды за кадр
      }

      float dt = lastRender == 0L ? 0.016F : Math.min(0.1F, (float)(now - lastRender) / 1.0E9F);
      lastRender = now;
      Minecraft mc = Minecraft.getInstance();
      if (mc.player == null || mc.font == null) {
         resetState();
         lastPlayer = null;
         lastCam = null;
         return;
      }

      if (pose == null || out == null) {
         return;
      }

      Vec3 cam = CameraPos.get();
      if (cam == null) {
         cam = mc.player.getEyePosition();
      }

      // после смерти, телепорта и перехода между мирами слова остались бы там, где были, и летели бы через полкарты
      if (mc.player != lastPlayer || lastCam != null && lastCam.distanceToSqr(cam) > TELEPORT_SQ) {
         resetState();
      }

      lastPlayer = mc.player;
      lastCam = cam;
      HudConfig cfg = HudConfig.get();
      double yaw = Math.toRadians(mc.player.getYRot());
      Font font = mc.font;
      if (takesOver()) {
         drawTrackLyrics(pose, out, font, cam, yaw, cfg, dt, now);
      } else {
         groups.clear();
         scatterLines.clear();
         estInit = false;
      }

      for (int i = extras.size() - 1; i >= 0; i--) {
         WorldLyricsRenderer.Group g = extras.get(i);
         long rel = (now - g.wallStart) / 1000000L;
         if (rel > g.total()) {
            extras.remove(i);
         } else {
            draw(pose, out, font, g, rel, 0.0F, cam, yaw, cfg, dt);
         }
      }

      drawDebris(pose, out, mc.level, cam, cfg, dt);
      out.flush();
   }

   private static void resetState() {
      groups.clear();
      scatterLines.clear();
      extras.clear();
      debris.clear();
      estInit = false;
   }

   private static void drawTrackLyrics(PoseStack pose, WorldText out, Font font, Vec3 cam, double yaw, HudConfig cfg, float dt, long now) {
      PlayerController ctl = PlayerController.I;
      List<LyricLine> lines = ctl.lyrics;
      if (lines != lastLines) {
         lastLines = lines;
         groups.clear();
         scatterLines.clear();
         estInit = false;
      }

      long polled = ctl.engine().positionMs();
      if (estInit && !(Math.abs(polled - estPos) > 300.0)) {
         if (ctl.engine().isPlaying()) {
            estPos += dt * 1000.0;
         }

         estPos = estPos + (polled - estPos) * Math.min(1.0, dt * 6.0);
      } else {
         estPos = polled;
         estInit = true;
      }

      long pos = Math.round(estPos);
      boolean playing = ctl.engine().isPlaying();
      pauseK = playing ? Math.min(1.0F, pauseK + dt / 0.35F) : Math.max(0.0F, pauseK - dt / 0.6F);
      int cur = LrcParser.currentIndex(lines, pos);
      if (cur < 0) {
         groups.clear();
         scatterLines.clear();
      } else {
         long spos = pos - cfg.lyricsShiftMs;
         List<WorldLyricsRenderer.Group> active = new ArrayList<>();
         Set<Integer> keep = new HashSet<>();
         boolean scatter = cfg.worldLayout == HudConfig.WorldLayout.SCATTER;
         boolean follow = cfg.worldMode == HudConfig.WorldMode.FOLLOW;
         boolean fixedYaw = follow && (scatter || cfg.worldFollowStyle == HudConfig.FollowStyle.WORLD);
         if (scatter) {
            scatterLines.keySet().removeIf(kx -> kx < cur - 8 || kx > cur);
         }

         for (int i = Math.max(0, cur - (scatter ? 7 : 4)); i <= cur; i++) {
            LyricLine line = lines.get(i);
            if (line.text() != null && !line.text().isBlank() && line.timeMs() != Long.MAX_VALUE) {
               LyricLine next = i + 1 < lines.size() ? lines.get(i + 1) : null;
               if (scatter) {
                  List<WorldLyricsRenderer.Group> made = scatterLines.get(i);
                  if (made == null || made.get(0).follow != follow || made.get(0).fixedYaw != fixedYaw) {
                     List<WorldLyricsRenderer.Group> existing = new ArrayList<>();

                     for (List<WorldLyricsRenderer.Group> l : scatterLines.values()) {
                        for (WorldLyricsRenderer.Group o : l) {
                           if (spos - o.lineMs <= o.total()) {
                              existing.add(o);
                           }
                        }
                     }

                     made = scatterFromLine(i, line, next, follow, fixedYaw, font, existing);
                     scatterLines.put(i, made);
                  }

                  for (WorldLyricsRenderer.Group g : made) {
                     if (spos - g.lineMs <= g.total()) {
                        active.add(g);
                     }
                  }
               } else {
                  WorldLyricsRenderer.Group gx = groups.get(i);
                  if (gx == null || gx.follow != follow || gx.fixedYaw != fixedYaw) {
                     gx = fromLine(i, line, next, follow, fixedYaw, font);
                     groups.put(i, gx);
                  }

                  if (spos - gx.lineMs <= gx.total()) {
                     keep.add(i);
                     active.add(gx);
                  }
               }
            }
         }

         groups.keySet().retainAll(keep);
         if (!scatter) {
            scatterLines.clear();
         }

         if (!scatter) {
            float acc = 0.0F;

            for (int k = active.size() - 1; k >= 0; k--) {
               WorldLyricsRenderer.Group gxx = active.get(k);
               if (k < active.size() - 1) {
                  WorldLyricsRenderer.Group newer = active.get(k + 1);
                  acc += (newer.rows + gxx.rows) / 2.0F * 12.0F + 8.0F;
               }

               draw(pose, out, font, gxx, spos - gxx.lineMs, -acc, cam, yaw, cfg, dt);
            }
         } else {
            for (WorldLyricsRenderer.Group gxx : active) {
               draw(pose, out, font, gxx, spos - gxx.lineMs, 0.0F, cam, yaw, cfg, dt);
            }
         }
      }
   }

   private static Vec3 target(WorldLyricsRenderer.Group g, Vec3 cam, double yaw, HudConfig cfg) {
      double dist = cfg.worldDistance * g.distK;
      double ang = yaw + g.yawOff;
      double fx = -Math.sin(ang);
      double fz = Math.cos(ang);
      double rx = -Math.cos(ang);
      double rz = -Math.sin(ang);
      double lat = g.lateral * dist * (cfg.worldSpread / 57.0);
      return new Vec3(cam.x + fx * dist + rx * lat, cam.y + cfg.worldHeight + g.vert, cam.z + fz * dist + rz * lat);
   }

   private static void draw(
      PoseStack pose, WorldText out, Font font, WorldLyricsRenderer.Group g, long rel, float stackLift, Vec3 cam, double yaw, HudConfig cfg, float dt
   ) {
      if (rel >= 0L) {
         if (!g.placed) {
            g.spawnYaw = yaw;
         }

         Vec3 t = target(g, cam, g.fixedYaw ? g.spawnYaw : yaw, cfg);
         if (!g.placed) {
            g.px = t.x;
            g.py = t.y;
            g.pz = t.z;
            g.placed = true;
         } else if (g.follow) {
            double a = 1.0 - Math.exp(-dt * (g.scatter ? 12.0 : 7.0));
            double ay = 1.0 - Math.exp(-dt * 2.6);
            g.px = g.px + (t.x - g.px) * a;
            g.py = g.py + (t.y - g.py) * ay;
            g.pz = g.pz + (t.z - g.pz) * a;
         }

         if (cfg.worldExit != HudConfig.WorldExit.FADE && rel >= g.endRel + g.hold) {
            if (!g.shattered) {
               g.shattered = true;
               spawnDebris(g, cam, cfg);
            }

            return;
         }

         seeNow = cfg.worldSeeThrough || occluded(g, cam);
         float exit = clamp01((float)(rel - g.endRel - g.hold) / 800.0F);
         float fade = 1.0F - exit * exit;
         if (g.timed) {
            fade *= pauseK * pauseK * (3.0F - 2.0F * pauseK);
         }

         if (!(fade <= 0.02F)) {
            if (!g.liftInit) {
               g.liftCur = stackLift;
               g.liftInit = true;
            } else {
               g.liftCur = g.liftCur + (stackLift - g.liftCur) * (1.0F - (float)Math.exp(-dt * 6.0));
            }

            float lift = g.liftCur - 14.0F * easeOutCubic(exit);
            float s = 0.026F * cfg.worldScale * g.sizeK;
            double wall = System.nanoTime() / 1.0E9;
            pose.pushPose();
            pose.translate((float)(g.px - cam.x), (float)(g.py - cam.y), (float)(g.pz - cam.z));
            float face = (float)Math.atan2(cam.x - g.px, cam.z - g.pz);
            out.rotateY(pose, face);
            if (g.scatter) {
               out.rotateZ(pose, g.tilt + 0.05F * (float)Math.sin(wall * 1.1 + g.index * 0.9));
            }

            pose.scale(s, -s, s);
            boolean letters = cfg.worldUnit == HudConfig.WorldUnit.LETTERS;
            float speed = cfg.worldAnimSpeed / 100.0F;
            float bounce = cfg.worldBounce / 100.0F;
            int glyphIndex = 0;

            for (int i = 0; i < g.words.size(); i++) {
               WorldLyricsRenderer.Word w = g.words.get(i);
               long tw = rel - w.startRel;
               if (tw >= 0L) {
                  boolean sungNow = tw < w.durRel;
                  float sweep = sungNow ? clamp01((float)tw / w.durRel) : 1.0F;
                  float pulse = sungNow ? 1.0F + 0.09F * (float)Math.sin(Math.PI * tw / w.durRel) : 1.0F;
                  float bob = (float)Math.sin(wall * 2.2 + g.index * 1.3 + i * 0.7) * 1.3F * bounce;
                  float baseX = w.x;
                  float baseY = w.y + lift;
                  if (!letters) {
                     float p = clamp01((float)tw * speed / 380.0F);
                     float cx = baseX + w.w / 2.0F;
                     int rgb = color(cfg, g, cx, glyphIndex, sungNow, wall);
                     drawPiece(pose, out, cfg, w.seq, w.w, cx, baseY + bob * easeOutCubic(p), 0.0F, p, w, i, g.index, wall, pulse, rgb, fade, sungNow);
                     glyphIndex += w.glyphs.size();
                  } else {
                     int n = w.glyphs.size();

                     for (int j = 0; j < n; j++) {
                        WorldLyricsRenderer.Glyph gl = w.glyphs.get(j);
                        long tl = tw - (long)j * cfg.worldLetterMs;
                        if (tl >= 0L) {
                           float p = clamp01((float)tl * speed / 380.0F);
                           boolean lit = sungNow && j < Math.ceil(sweep * n);
                           float cx = baseX + gl.x;
                           float letterBob = (float)Math.sin(wall * 2.2 + g.index * 1.3 + i * 0.7 + j * 0.25) * 1.3F * bounce;
                           int rgb = color(cfg, g, cx, glyphIndex + j, lit, wall);
                           drawPiece(pose, out, cfg, gl.seq, gl.w, cx, baseY + letterBob * easeOutCubic(p), 0.0F, p, w, j, g.index * 31 + i, wall, pulse, rgb, fade, lit);
                        }
                     }

                     glyphIndex += n;
                  }
               }
            }

            pose.popPose();
         }
      }
   }

   /**
    * Draws one word or letter: entrance animation from progress p, optional glow ring behind it,
    * then the text itself centred on (cx, cy).
    */
   private static void drawPiece(
      PoseStack pose,
      WorldText out,
      HudConfig cfg,
      FormattedCharSequence seq,
      int width,
      float cx,
      float cy,
      float cz,
      float p,
      WorldLyricsRenderer.Word w,
      int j,
      int seed,
      double wall,
      float pulse,
      int rgb,
      float fade,
      boolean lit
   ) {
      float e = easeOutCubic(p);
      float alpha = Math.min(1.0F, p * 1.8F) * fade;
      float k = pulse;
      float rotY = 0.0F;
      float rotZ = 0.0F;
      switch (cfg.worldAnim) {
         case FLY:
            cx += w.sx * (1.0F - e) * 0.5F;
            cy += w.sy * (1.0F - e);
            cz += w.sz * (1.0F - e);
            k *= 0.55F + 0.45F * easeOutBack(p);
            break;
         case RISE:
            cy += 11.0F * (1.0F - easeOutBack(p));
            break;
         case DROP:
            cy -= 14.0F * (1.0F - easeOutBounce(p));
            alpha = Math.min(1.0F, p * 4.0F) * fade;
            break;
         case POP:
            k *= Math.max(0.0F, easeOutBack(p));
            break;
         case WAVE:
            cy += 9.0F * (1.0F - e) + (float)Math.sin(wall * 5.0 - j * 0.55 - seed * 0.3) * 1.8F * (cfg.worldBounce / 100.0F) * e;
            break;
         case TYPE:
            alpha = (p > 0.05F ? 1.0F : 0.0F) * fade;
            k *= p < 0.25F ? 1.25F - p : 1.0F;
            break;
         case SPIN:
            rotY = (float)(Math.PI * 0.5 * (1.0F - easeOutBack(p)));
            k *= 0.7F + 0.3F * e;
            break;
         case GLITCH:
            float jitter = (1.0F - e) * 4.0F;
            int h = (seed * 73 + j * 151 + (int)(wall * 30.0)) * -1640531535;
            cx += ((h >>> 3 & 15) - 7.5F) / 7.5F * jitter;
            cy += ((h >>> 9 & 15) - 7.5F) / 7.5F * jitter;
            if (p < 0.6F && (h >>> 20 & 3) == 0) {
               rgb = 16777215 - rgb & 16777215;
            }
      }

      if (!(alpha < 0.04F) && !(k <= 0.01F)) {
         pose.pushPose();
         pose.translate(cx, cy, cz);
         if (rotY != 0.0F) {
            out.rotateY(pose, rotY);
         }

         if (rotZ != 0.0F) {
            out.rotateZ(pose, rotZ);
         }

         pose.scale(k, k, k);
         float x0 = -width / 2.0F;
         boolean glow = cfg.worldGlow == HudConfig.WorldGlow.ALL || cfg.worldGlow == HudConfig.WorldGlow.SUNG && lit;
         if (glow && cfg.worldGlowStrength > 0) {
            // soft glow: two rings of faint copies around the glyph, brightest close in
            float r = cfg.worldGlowSize / 100.0F;
            int glowRgb = lerpRgb(rgb, 16777215, 0.2F);
            int inner = Math.round(alpha * cfg.worldGlowStrength * 0.9F);
            int outer = Math.round(alpha * cfg.worldGlowStrength * 0.4F);

            for (int d = 0; d < 8; d++) {
               double a = d * Math.PI / 4.0;
               float ox = (float)Math.cos(a);
               float oy = (float)Math.sin(a);
               out.text(pose, x0 + ox * 0.6F * r, -4.5F + oy * 0.6F * r, seq, false, seeNow, inner << 24 | glowRgb);
               out.text(pose, x0 + ox * 1.3F * r, -4.5F + oy * 1.3F * r, seq, false, seeNow, outer << 24 | glowRgb);
            }
         }

         int argb = Math.round(alpha * 255.0F) << 24 | rgb & 16777215;
         // Без глубины текст «сквозь блоки» затирается полупрозрачными слоями (вода, портал, листва), поэтому
         // видимую часть сначала рисуем обычным текстом: он пишет глубину и защищает её.
         if (seeNow) {
            out.text(pose, x0, -4.5F, seq, false, false, argb);
         }

         out.text(pose, x0, -4.5F, seq, cfg.worldShadow && !glow, seeNow, argb);
         pose.popPose();
      }
   }

   /** Breaks a group that is done into per-letter pieces that live on with simple physics. */
   private static void spawnDebris(WorldLyricsRenderer.Group g, Vec3 cam, HudConfig cfg) {
      if (g.placed) {
         Level level = Minecraft.getInstance().level;
         float s = 0.026F * cfg.worldScale * g.sizeK;
         float face = (float)Math.atan2(cam.x - g.px, cam.z - g.pz);
         double cos = Math.cos(face);
         double sin = Math.sin(face);
         double wall = System.nanoTime() / 1.0E9;
         Random rnd = new Random(g.index * 4447L + g.lineMs);
         float stagger = cfg.worldLetterMs / 1000.0F;
         float spanX = Math.max(1.0F, g.maxX - g.minX);
         int k = 0;
         int glyphIndex = 0;

         for (WorldLyricsRenderer.Word w : g.words) {
            for (WorldLyricsRenderer.Glyph gl : w.glyphs) {
               float lx = w.x + gl.x;
               float ly = w.y + g.liftCur;
               WorldLyricsRenderer.Debris d = new WorldLyricsRenderer.Debris();
               d.seq = gl.seq;
               d.w = gl.w;
               d.s = s;
               d.mode = cfg.worldExit;
               d.x = g.px + cos * lx * s;
               d.y = g.py - ly * s;
               d.z = g.pz - sin * lx * s;
               d.yaw = face;
               d.rgb = color(cfg, g, lx, glyphIndex++, false, wall);
               d.restYaw = face + (rnd.nextFloat() - 0.5F) * 0.9F;
               d.ground = level == null ? d.y - 64.0 : groundBelow(level, d.x, d.y, d.z);
               switch (d.mode) {
                  case FALL:
                     d.delay = k * stagger;
                     d.vx = (rnd.nextFloat() - 0.5F) * 0.5F;
                     d.vy = 0.4F + rnd.nextFloat() * 0.8F;
                     d.vz = (rnd.nextFloat() - 0.5F) * 0.5F;
                     d.rollV = (rnd.nextFloat() - 0.5F) * 9.0F;
                     break;
                  case SHATTER:
                     double ox = d.x - (g.px + cos * (g.minX + spanX / 2.0F) * s);
                     double oz = d.z - (g.pz - sin * (g.minX + spanX / 2.0F) * s);
                     double len = Math.max(0.05, Math.sqrt(ox * ox + oz * oz));
                     float power = 1.6F + rnd.nextFloat() * 2.2F;
                     d.vx = (float)(ox / len * power);
                     d.vz = (float)(oz / len * power);
                     d.vx += (float)((cam.x - g.px) * 0.25 * rnd.nextFloat());
                     d.vz += (float)((cam.z - g.pz) * 0.25 * rnd.nextFloat());
                     d.vy = 2.0F + rnd.nextFloat() * 2.5F;
                     d.rollV = (rnd.nextFloat() - 0.5F) * 22.0F;
                     break;
                  case FLOAT:
                     d.delay = k * 0.02F;
                     d.vy = 0.45F + rnd.nextFloat() * 0.5F;
                     d.phase = rnd.nextFloat() * 6.28F;
                     break;
                  case WIND:
                     d.delay = (lx - g.minX) / spanX * 0.45F;
                     float push = 2.8F + rnd.nextFloat() * 2.4F;
                     d.vx = (float)(cos * push);
                     d.vz = (float)(-sin * push);
                     d.vy = 0.4F + rnd.nextFloat() * 0.7F;
                     d.rollV = (rnd.nextFloat() - 0.3F) * 14.0F;
                     d.phase = rnd.nextFloat() * 6.28F;
                     break;
                  default:
                     d.delay = k * stagger;
               }

               debris.add(d);
               k++;
            }
         }

         while (debris.size() > MAX_DEBRIS) {
            debris.remove(0);
         }
      }
   }

   /** Слова за неполным блоком (стекло, листва, блоки из ресурспаков) рисуются поверх него, цельные стены по-прежнему прячут текст. */
   private static boolean occluded(WorldLyricsRenderer.Group g, Vec3 cam) {
      long now = System.nanoTime();
      if (now - g.occCheckedAt < OCCLUSION_CHECK_NS) {
         return g.occluded;
      }

      g.occCheckedAt = now;
      Minecraft mc = Minecraft.getInstance();
      if (mc.level == null || mc.player == null) {
         return g.occluded = false;
      }

      try {
         Vec3 to = new Vec3(g.px, g.py, g.pz);
         HitResult hit = mc.level.clip(new ClipContext(cam, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
         g.occluded = hit instanceof BlockHitResult block
            && hit.getType() != HitResult.Type.MISS
            && hit.getLocation().distanceToSqr(cam) < to.distanceToSqr(cam) - 0.04
            && !mc.level.getBlockState(block.getBlockPos()).canOcclude();
      } catch (RuntimeException e) {
         g.occluded = false;
      }

      return g.occluded;
   }

   /** Top of the first solid surface at or below (x, y, z), honouring slabs, stairs and so on. */
   private static double groundBelow(Level level, double x, double y, double z) {
      BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
      int bx = (int)Math.floor(x);
      int bz = (int)Math.floor(z);
      double fx = x - bx;
      double fz = z - bz;

      for (int by = (int)Math.floor(y); by > (int)Math.floor(y) - 48; by--) {
         pos.set(bx, by, bz);
         BlockState st = level.getBlockState(pos);
         if (!st.isAir()) {
            VoxelShape shape = st.getCollisionShape(level, pos);
            if (!shape.isEmpty()) {
               double top = shape.max(Direction.Axis.Y, fx, fz);
               if (!Double.isFinite(top) || top < 0.0) {
                  top = shape.max(Direction.Axis.Y);
               }

               if (by + top <= y + 0.05) {
                  return by + top;
               }
            }
         }
      }

      return y - 64.0;
   }

   private static void drawDebris(PoseStack pose, WorldText out, Level level, Vec3 cam, HudConfig cfg, float dt) {
      for (int i = debris.size() - 1; i >= 0; i--) {
         WorldLyricsRenderer.Debris d = debris.get(i);
         d.age += dt;
         float t = d.age - d.delay;
         float alpha = 1.0F;
         float k = 1.0F;
         boolean dead = false;
         if (t > 0.0F) {
            switch (d.mode) {
               case FALL:
               case SHATTER:
                  if (!d.landed) {
                     d.vy -= GRAVITY * dt;
                     d.x += d.vx * dt;
                     d.y += d.vy * dt;
                     d.z += d.vz * dt;
                     d.roll += d.rollV * dt;
                     double half = 4.5 * d.s;
                     if (d.y - half <= d.ground) {
                        if (d.vy < -3.0F && !d.bounced) {
                           // one small hop on impact so it does not look glued
                           d.bounced = true;
                           d.vy = -d.vy * 0.22F;
                           d.vx *= 0.4F;
                           d.vz *= 0.4F;
                           d.y = d.ground + half;
                        } else {
                           d.landed = true;
                           d.landedAt = t;
                           d.y = d.ground + 0.015;
                        }
                     }

                     if (d.y < d.ground - 40.0) {
                        dead = true;
                     }
                  } else {
                     d.flat = Math.min(1.0F, d.flat + dt / 0.14F);
                     float left = t - d.landedAt - cfg.worldLinger;
                     alpha = 1.0F - clamp01(left / 1.2F);
                     dead = left > 1.2F;
                  }
                  break;
               case FLOAT:
                  d.y += d.vy * dt;
                  d.x += Math.sin(t * 2.3 + d.phase) * 0.25 * dt;
                  d.z += Math.cos(t * 1.9 + d.phase) * 0.25 * dt;
                  k = 1.0F + t * 0.18F;
                  alpha = 1.0F - clamp01(t / 2.6F);
                  dead = t > 2.6F;
                  break;
               case WIND:
                  d.vy -= 0.9F * dt;
                  d.x += d.vx * dt;
                  d.y += (d.vy + Math.sin(t * 7.0 + d.phase) * 0.5) * dt;
                  d.z += d.vz * dt;
                  d.roll += d.rollV * dt;
                  alpha = 1.0F - clamp01(t / 2.1F);
                  dead = t > 2.1F;
                  break;
               case SHRINK:
                  k = 1.0F - easeOutCubic(clamp01(t / 0.35F));
                  dead = t > 0.35F;
                  break;
               case SINK:
                  d.y -= 0.85 * dt;
                  alpha = 1.0F - clamp01(t / 0.9F);
                  dead = t > 0.9F;
                  break;
               default:
                  dead = true;
            }
         }

         if (dead) {
            debris.remove(i);
         } else if (!(alpha < 0.03F) && !(k <= 0.01F)) {
            pose.pushPose();
            pose.translate((float)(d.x - cam.x), (float)(d.y - cam.y), (float)(d.z - cam.z));
            out.rotateY(pose, d.yaw + (d.restYaw - d.yaw) * d.flat);
            if (d.flat > 0.0F) {
               out.rotateX(pose, (float)(-Math.PI / 2.0) * easeOutCubic(d.flat));
            }

            if (d.roll != 0.0F) {
               out.rotateZ(pose, d.roll * (1.0F - d.flat));
            }

            float sc = d.s * k;
            pose.scale(sc, -sc, sc);
            int a255 = Math.round(alpha * 255.0F);
            if (cfg.worldGlow == HudConfig.WorldGlow.ALL && cfg.worldGlowStrength > 0) {
               int glowRgb = lerpRgb(d.rgb, 16777215, 0.2F);
               int ga = Math.round(alpha * cfg.worldGlowStrength * 0.8F);
               float r = cfg.worldGlowSize / 100.0F * 0.7F;

               for (int q = 0; q < 8; q++) {
                  double ang = q * Math.PI / 4.0;
                  out.text(pose, -d.w / 2.0F + (float)Math.cos(ang) * r, -4.5F + (float)Math.sin(ang) * r, d.seq, false, false, ga << 24 | glowRgb);
               }
            }

            out.text(pose, -d.w / 2.0F, -4.5F, d.seq, cfg.worldShadow, false, a255 << 24 | d.rgb & 16777215);
            pose.popPose();
         }
      }
   }

   private static int color(HudConfig cfg, WorldLyricsRenderer.Group g, float x, int index, boolean lit, double wall) {
      int a = cfg.worldAccent.rgb;
      return switch (cfg.worldColor) {
         case ACCENT -> lit ? lerpRgb(a, 16777215, 0.5F) : a;
         case GRADIENT -> {
            float t = g.maxX > g.minX ? clamp01((x - g.minX) / (g.maxX - g.minX)) : 0.5F;
            int c = lerpRgb(a, cfg.worldAccent2.rgb, t);
            yield lit ? lerpRgb(c, 16777215, 0.45F) : c;
         }
         case RAINBOW -> hsv((float)(index * 0.035 + wall * 0.12), lit ? 0.35F : 0.65F, 1.0F);
         case WHITE -> lit ? lerpRgb(16777215, a, 0.25F) : 16777215;
         default -> lit ? cfg.accentRgb() : 16777215;
      };
   }

   private static int lerpRgb(int from, int to, float t) {
      int r = Math.round((from >> 16 & 0xFF) + ((to >> 16 & 0xFF) - (from >> 16 & 0xFF)) * t);
      int gr = Math.round((from >> 8 & 0xFF) + ((to >> 8 & 0xFF) - (from >> 8 & 0xFF)) * t);
      int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
      return r << 16 | gr << 8 | b;
   }

   private static int hsv(float hue, float sat, float val) {
      float h = (hue - (float)Math.floor(hue)) * 6.0F;
      int sector = (int)h;
      float f = h - sector;
      float p = val * (1.0F - sat);
      float q = val * (1.0F - sat * f);
      float t = val * (1.0F - sat * (1.0F - f));
      float[] rgb = switch (sector) {
         case 0 -> new float[]{val, t, p};
         case 1 -> new float[]{q, val, p};
         case 2 -> new float[]{p, val, t};
         case 3 -> new float[]{p, q, val};
         case 4 -> new float[]{t, p, val};
         default -> new float[]{val, p, q};
      };
      return Math.round(rgb[0] * 255.0F) << 16 | Math.round(rgb[1] * 255.0F) << 8 | Math.round(rgb[2] * 255.0F);
   }

   private static float easeOutBounce(float t) {
      t = clamp01(t);
      float n = 7.5625F;
      float d = 2.75F;
      if (t < 1.0F / d) {
         return n * t * t;
      } else if (t < 2.0F / d) {
         t -= 1.5F / d;
         return n * t * t + 0.75F;
      } else if (t < 2.5F / d) {
         t -= 2.25F / d;
         return n * t * t + 0.9375F;
      } else {
         t -= 2.625F / d;
         return n * t * t + 0.984375F;
      }
   }

   private static float clamp01(float v) {
      return Math.max(0.0F, Math.min(1.0F, v));
   }

   private static float easeOutCubic(float t) {
      float u = 1.0F - clamp01(t);
      return 1.0F - u * u * u;
   }

   private static float easeOutBack(float t) {
      t = clamp01(t);
      float c1 = 1.70158F;
      float c3 = c1 + 1.0F;
      float u = t - 1.0F;
      return 1.0F + c3 * u * u * u + c1 * u * u;
   }

   private static final class Group {
      final int index;
      final boolean follow;
      boolean fixedYaw;
      double spawnYaw;
      final long lineMs;
      long wallStart;
      final boolean timed;
      final List<WorldLyricsRenderer.Word> words = new ArrayList<>();
      long endRel;
      int rows = 1;
      float lateral;
      float vert;
      double px;
      double py;
      double pz;
      boolean placed;
      float liftCur;
      boolean liftInit;
      boolean scatter;
      double yawOff;
      float distK = 1.0F;
      float tilt;
      float sizeK = 1.0F;
      long hold = 1500L;
      float minX;
      float maxX;
      boolean shattered;
      boolean occluded;
      long occCheckedAt;

      Group(int index, boolean follow, long lineMs, boolean timed) {
         this.index = index;
         this.follow = follow;
         this.lineMs = lineMs;
         this.timed = timed;
         this.wallStart = System.nanoTime();
      }

      long total() {
         return this.endRel + this.hold + 800L;
      }
   }

   private static final class Debris {
      FormattedCharSequence seq;
      int w;
      float s;
      int rgb;
      HudConfig.WorldExit mode;
      double x;
      double y;
      double z;
      float vx;
      float vy;
      float vz;
      float yaw;
      float restYaw;
      float roll;
      float rollV;
      float flat;
      float phase;
      float delay;
      float age;
      float landedAt;
      double ground;
      boolean landed;
      boolean bounced;
   }

   private static final class Glyph {
      FormattedCharSequence seq;
      float x;
      int w;
   }

   private static final class Word {
      String text;
      FormattedCharSequence seq;
      List<WorldLyricsRenderer.Glyph> glyphs = List.of();
      long startRel;
      long durRel;
      float x;
      float y;
      int w;
      float sx;
      float sy;
      float sz;
   }
}
