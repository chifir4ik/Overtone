package dev.musicplayer.viz;

import dev.musicplayer.config.HudConfig;
import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.model.Platform;
import dev.musicplayer.model.Track;

/** Picks where the visualizer hears music from and turns spectra into bars that move like cava's. */
public final class Visualizer {
   private float[] bandL = new float[0];
   private float[] bandR = new float[0];
   private float[] raw = new float[0];
   private float[] level = new float[0];
   private float[] velocity = new float[0];
   private float[] peak = new float[0];
   private float[] hold = new float[0];
   private float gain = 1.0F;
   private long lastNs;
   private boolean live;

   /** Called every client tick: starts/stops system capture depending on the source setting. */
   public static void tick() {
      HudConfig cfg = HudConfig.get();
      PlayerController ctl = PlayerController.I;
      Track t = ctl.current;
      boolean wanted = cfg.master && (cfg.vizEnabled || cfg.showWaveform);
      boolean spotify = t != null && Platform.SPOTIFY.id.equals(t.provider()) && !ctl.idle;
      boolean system = cfg.vizSource == HudConfig.VizSource.SYSTEM || cfg.vizSource == HudConfig.VizSource.AUTO && spotify;
      SystemCapture.setRunning(wanted && system);
   }

   public float[] levels() {
      return this.level;
   }

   public float[] peaks() {
      return this.peak;
   }

   public boolean live() {
      return this.live;
   }

   /**
    * Advances the bars one frame. sensitivity is a percentage, smoothing 0..95 (higher = slower fall).
    * Stereo lays the bars out like cava: left channel mirrored on the left half, right channel on the
    * right half, bass meeting in the middle. With demo set, silence becomes a fake animation (previews).
    */
   public void update(int count, int sensitivity, int smoothing, boolean auto, boolean demo, boolean stereo) {
      int half = (count + 1) / 2;
      if (this.level.length != count) {
         this.raw = new float[count];
         this.level = new float[count];
         this.velocity = new float[count];
         this.peak = new float[count];
         this.hold = new float[count];
      }

      if (this.bandL.length != (stereo ? half : count)) {
         this.bandL = new float[stereo ? half : count];
         this.bandR = new float[stereo ? half : count];
      }

      long now = System.nanoTime();
      float dt = this.lastNs == 0L ? 0.016F : Math.min(0.1F, (float)(now - this.lastNs) / 1.0E9F);
      this.lastNs = now;
      if (stereo) {
         this.live = Spectrum.bands(this.bandL, 50.0F, 10000.0F, Spectrum.LEFT) & Spectrum.bands(this.bandR, 50.0F, 10000.0F, Spectrum.RIGHT);
      } else {
         this.live = Spectrum.bands(this.bandL, 50.0F, 10000.0F, Spectrum.MIX);
      }

      if (!this.live) {
         double t = now / 1.0E9;

         for (int i = 0; i < this.bandL.length; i++) {
            float v = demo ? (float)(0.3 + 0.3 * Math.max(0.0, Math.sin(t * 4.2 - i * 0.35)) * (1.0 - (double)i / this.bandL.length) + 0.1 * Math.sin(t * 2.3 + i)) : 0.0F;
            this.bandL[i] = v;
            this.bandR[i] = v;
         }
      }

      if (stereo) {
         // bass in the centre, treble towards both edges; an odd middle bar averages both channels
         for (int i = 0; i < half; i++) {
            int li = half - 1 - i;
            int ri = count - half + i;
            this.raw[li] = this.bandL[i];
            this.raw[ri] = li == ri ? (this.bandL[i] + this.bandR[i]) * 0.5F : this.bandR[i];
         }
      } else {
         System.arraycopy(this.bandL, 0, this.raw, 0, count);
      }

      float max = 0.0F;

      for (int i = 0; i < count; i++) {
         float v = this.raw[i] * (this.live ? this.gain * sensitivity / 100.0F : 1.0F);
         max = Math.max(max, v);
         this.raw[i] = Math.min(1.0F, Math.max(0.0F, v));
      }

      if (auto && this.live) {
         // cava-style autosens: back off while bars clip, creep up while the music is quiet
         if (max > 1.0F) {
            this.gain *= (float)Math.pow(0.98, dt * 60.0F);
         } else if (max > 0.01F) {
            this.gain *= (float)Math.pow(1.002, dt * 60.0F);
         }

         this.gain = Math.max(0.02F, Math.min(60.0F, this.gain));
      } else if (!auto) {
         this.gain = 1.0F;
      }

      float s = Math.min(95, smoothing) / 100.0F;
      // hits land almost at once; falling accelerates like gravity (cava's look), slower with more smoothing
      float attack = 1.0F - (float)Math.exp(-dt * (60.0F - 35.0F * s));
      float gravity = 14.0F - 12.0F * s;

      for (int i = 0; i < count; i++) {
         float v = this.raw[i];
         if (v >= this.level[i]) {
            this.level[i] += (v - this.level[i]) * attack;
            this.velocity[i] = 0.0F;
         } else {
            this.velocity[i] += gravity * dt;
            this.level[i] = Math.max(v, this.level[i] - this.velocity[i] * dt);
         }

         if (this.level[i] >= this.peak[i]) {
            this.peak[i] = this.level[i];
            this.hold[i] = 0.35F;
         } else if ((this.hold[i] -= dt) <= 0.0F) {
            this.peak[i] = Math.max(this.level[i], this.peak[i] - dt * 0.7F);
         }
      }
   }
}
