package dev.musicplayer.hud;

import dev.musicplayer.compat.Canvas;
import dev.musicplayer.config.HudConfig;
import dev.musicplayer.viz.Visualizer;

/** cava-style spectrum bars as a movable HUD element. */
public final class VisualizerRenderer {
   private static final int PAD = 4;
   private static final Visualizer VIZ = new Visualizer();
   private static float alpha;
   private static long lastNanos;

   private VisualizerRenderer() {
   }

   public static void render(Canvas c, boolean preview) {
      HudConfig cfg = HudConfig.get();
      HudBounds.visualizer = null;
      long now = System.nanoTime();
      float delta = lastNanos == 0L ? 0.0F : Math.min(0.1F, (float)(now - lastNanos) / 1.0E9F);
      lastNanos = now;
      boolean shown = cfg.master && cfg.vizEnabled && !Draw.hidden(cfg);
      if (!shown && !preview) {
         alpha = 0.0F;
      } else {
         VIZ.update(cfg.vizBars, cfg.vizSensitivity, cfg.vizSmoothing, cfg.vizAutoSens, preview, cfg.vizStereo);
         boolean wanted = VIZ.live() || !cfg.vizHideIdle;
         alpha = alpha + ((wanted ? 1.0F : 0.0F) - alpha) * Math.min(1.0F, delta * (wanted ? 8.0F : 2.0F));
         float a = preview ? 1.0F : alpha;
         if (!(a < 0.02F)) {
            int n = cfg.vizBars;
            int bw = cfg.vizBarWidth;
            int gap = cfg.vizGap;
            int barsW = n * bw + (n - 1) * gap;
            int barsH = cfg.vizHeight;
            int w = barsW + 8;
            int h = barsH + 8;
            int[] o = Draw.begin(c, cfg.vizAnchor, cfg.vizOffsetX, cfg.vizOffsetY, cfg.vizScale, w, h, a);
            int x = o[0] + 4;
            int y = o[1] + 4;
            if (cfg.vizBackground > 0) {
               Draw.panel(c, o[0], o[1], w, h, cfg.vizBackground, a);
            }

            float[] lv = VIZ.levels();
            float[] pk = VIZ.peaks();
            int accent = cfg.accentRgb();
            int alpha255 = Draw.a255(a * cfg.vizOpacity / 100.0F);
            boolean down = cfg.vizDirection == HudConfig.VizDirection.DOWN
               || cfg.vizDirection == HudConfig.VizDirection.AUTO && cfg.vizAnchor.v < 0;
            if (cfg.vizStyle == HudConfig.VizStyle.WAVE) {
               drawWave(c, cfg, lv, x, y, barsW, barsH, accent, alpha255, now, down);
            } else {
               for (int i = 0; i < n; i++) {
                  int bx = x + i * (bw + gap);
                  float hf = lv[i] * barsH;
                  int rgb = color(cfg.vizColor, accent, i, n, lv[i], now);
                  switch (cfg.vizStyle) {
                     case BLOCKS:
                        int col = Draw.argb(rgb, alpha255);
                        int bh = Math.round(hf);

                        for (int by = 0; by + 2 <= bh; by += 3) {
                           c.rect(bx, down ? y + by : y + barsH - by - 2, bw, 2, col);
                        }
                        break;
                     case MIRROR:
                        float half = Math.max(0.5F, hf / 2.0F);
                        int mid = y + barsH / 2;
                        column(c, bx, bw, mid, half, rgb, alpha255, false);
                        column(c, bx, bw, mid, half, rgb, alpha255, true);
                        break;
                     default:
                        column(c, bx, bw, down ? y : y + barsH, hf, rgb, alpha255, down);
                  }

                  if (cfg.vizPeaks && cfg.vizStyle != HudConfig.VizStyle.MIRROR) {
                     int off = Math.min(barsH - 1, Math.round(pk[i] * barsH));
                     c.rect(bx, down ? y + off : y + barsH - off - 1, bw, 1, Draw.argb(16777215, Math.round(alpha255 * 0.85F)));
                  }
               }
            }

            HudBounds.visualizer = Draw.lastBox;
            c.pop();
         }
      }
   }

   /**
    * A bar of fractional height growing from {@code base} (up, or down when {@code down}); the
    * partial last pixel is drawn translucent so small changes glide instead of stepping.
    */
   private static void column(Canvas c, int x, int w, int base, float height, int rgb, int alpha255, boolean down) {
      int full = (int)height;
      float frac = height - full;
      if (full > 0) {
         c.rect(x, down ? base : base - full, w, full, Draw.argb(rgb, alpha255));
      }

      if (frac > 0.02F) {
         c.rect(x, down ? base + full : base - full - 1, w, 1, Draw.argb(rgb, Math.round(alpha255 * frac)));
      }
   }

   private static void drawWave(Canvas c, HudConfig cfg, float[] lv, int x, int y, int w, int h, int accent, int alpha255, long now, boolean down) {
      int n = lv.length;

      for (int px = 0; px < w; px++) {
         float f = n == 1 ? 0.0F : (float)px / Math.max(1, w - 1) * (n - 1);
         int i = (int)f;
         float t = f - i;
         float s = (1.0F - (float)Math.cos(t * Math.PI)) / 2.0F;
         float v = lv[i] * (1.0F - s) + lv[Math.min(n - 1, i + 1)] * s;
         float hf = v * h;
         if (hf > 0.02F) {
            int rgb = color(cfg.vizColor, accent, i, n, v, now);
            column(c, x + px, 1, down ? y : y + h, hf, rgb, Math.round(alpha255 * 0.55F), down);
            int edge = Math.min(h - 1, (int)hf);
            c.rect(x + px, down ? y + edge : y + h - edge - 1, 1, 1, Draw.argb(rgb, alpha255));
         }
      }
   }

   private static int color(HudConfig.VizColor mode, int accent, int i, int n, float level, long now) {
      return switch (mode) {
         case ACCENT -> accent;
         case WHITE -> 16777215;
         case RAINBOW -> hsv((float)i / n + now / 8.0E9F, 0.65F, 1.0F);
         default -> Draw.lerpRgb(accent, 16777215, Math.min(1.0F, level * 0.75F));
      };
   }

   private static int hsv(float hue, float sat, float val) {
      float h = (hue - (float)Math.floor(hue)) * 6.0F;
      int sector = (int)h;
      float f = h - sector;
      float p = val * (1.0F - sat);
      float q = val * (1.0F - sat * f);
      float t = val * (1.0F - sat * (1.0F - f));
      float r;
      float g;
      float b;
      switch (sector) {
         case 0:
            r = val;
            g = t;
            b = p;
            break;
         case 1:
            r = q;
            g = val;
            b = p;
            break;
         case 2:
            r = p;
            g = val;
            b = t;
            break;
         case 3:
            r = p;
            g = q;
            b = val;
            break;
         case 4:
            r = t;
            g = p;
            b = val;
            break;
         default:
            r = val;
            g = p;
            b = q;
      }

      return Math.round(r * 255.0F) << 16 | Math.round(g * 255.0F) << 8 | Math.round(b * 255.0F);
   }
}
