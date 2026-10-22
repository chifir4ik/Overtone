package dev.musicplayer.viz;

/**
 * Audio spectrum for the visualizer. The mod's own players (or the system capture) push PCM
 * here; the renderer asks for log-spaced frequency bands of what is audible right now.
 * Left and right are kept apart so the bars can be mirrored per channel like cava's stereo mode.
 */
public final class Spectrum {
   public static final int LEFT = 0;
   public static final int RIGHT = 1;
   public static final int MIX = 2;
   private static final int RING = 1 << 15;
   private static final int N = 2048;
   private static final float[] ringL = new float[RING];
   private static final float[] ringR = new float[RING];
   private static final float[] window = new float[N];
   private static final float[] re = new float[N];
   private static final float[] im = new float[N];
   private static final Object LOCK = new Object();
   private static long written;
   private static long latency;
   private static long feedAtNs;
   private static volatile float rate = 44100.0F;
   private static volatile long lastFeedNs;
   private static volatile boolean system;

   static {
      for (int i = 0; i < N; i++) {
         window[i] = (float)(0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (N - 1)));
      }
   }

   private Spectrum() {
   }

   /** True while samples keep arriving, i.e. something is audible. */
   public static boolean live() {
      return System.nanoTime() - lastFeedNs < 250000000L;
   }

   static void useSystem(boolean on) {
      if (system != on) {
         system = on;
         lastFeedNs = 0L;
      }
   }

   /** 16-bit little-endian PCM from the mod's players; queuedBytes is what the line still has to play. */
   public static void feedPcm16(byte[] b, int len, int channels, float sampleRate, int queuedBytes) {
      if (!system) {
         push(b, len, channels, sampleRate, queuedBytes / (2L * Math.max(1, channels)));
      }
   }

   public static void feedPcm16(short[] s, int len, int channels, float sampleRate, int queuedBytes) {
      if (!system && channels > 0) {
         synchronized (LOCK) {
            for (int i = 0; i + channels <= len; i += channels) {
               int at = (int)(written++ & RING - 1);
               ringL[at] = s[i] / 32768.0F;
               ringR[at] = s[i + Math.min(1, channels - 1)] / 32768.0F;
            }

            mark(sampleRate, queuedBytes / (2L * channels));
         }
      }
   }

   static void feedSystem(byte[] b, int len, int channels, float sampleRate) {
      if (system) {
         push(b, len, channels, sampleRate, 0L);
      }
   }

   private static void push(byte[] b, int len, int channels, float sampleRate, long queuedFrames) {
      if (channels > 0) {
         int frame = 2 * channels;
         int right = 2 * Math.min(1, channels - 1);
         synchronized (LOCK) {
            for (int i = 0; i + frame <= len; i += frame) {
               int at = (int)(written++ & RING - 1);
               ringL[at] = (short)(b[i] & 255 | b[i + 1] << 8) / 32768.0F;
               ringR[at] = (short)(b[i + right] & 255 | b[i + right + 1] << 8) / 32768.0F;
            }

            mark(sampleRate, queuedFrames);
         }
      }
   }

   private static void mark(float sampleRate, long queuedFrames) {
      latency = queuedFrames;
      rate = sampleRate;
      feedAtNs = System.nanoTime();
      lastFeedNs = feedAtNs;
   }

   /**
    * Fills {@code out} with band levels (roughly 0..1 for loud music before gain) for log-spaced
    * bands between lowHz and highHz of one channel. Returns false when there is nothing to analyse.
    */
   public static boolean bands(float[] out, float lowHz, float highHz, int channel) {
      if (!live()) {
         return false;
      } else {
         float sr;
         synchronized (LOCK) {
            // players hand audio over in big chunks; slide the window with real time between them
            // so the bars follow what is audible now instead of jumping once per chunk
            long played = (long)((System.nanoTime() - feedAtNs) / 1.0E9 * rate);
            long end = written - Math.max(0L, latency - played);

            for (int i = 0; i < N; i++) {
               long idx = end - N + i;
               float v;
               if (idx < 0L) {
                  v = 0.0F;
               } else {
                  int at = (int)(idx & RING - 1);
                  v = channel == LEFT ? ringL[at] : (channel == RIGHT ? ringR[at] : (ringL[at] + ringR[at]) * 0.5F);
               }

               re[i] = v * window[i];
               im[i] = 0.0F;
            }

            sr = rate;
         }

         fft(re, im);
         float binHz = sr / N;
         float top = Math.min(highHz, sr / 2.0F - binHz);
         int n = out.length;
         double ratio = Math.pow(top / lowHz, 1.0 / n);

         for (int b = 0; b < n; b++) {
            double f0 = lowHz * Math.pow(ratio, b);
            double f1 = f0 * ratio;
            double k0 = f0 / binHz;
            double k1 = f1 / binHz;
            double power;
            if (k1 - k0 < 1.0) {
               float m = magAt((k0 + k1) / 2.0);
               power = m * m;
            } else {
               // average energy over the band: stable, unlike the max which flickers with every hi-hat
               power = 0.0;
               int count = 0;

               for (int k = (int)Math.ceil(k0); k <= (int)Math.floor(k1) && k < N / 2; k++) {
                  float m = mag(k);
                  power += m * m;
                  count++;
               }

               power /= Math.max(1, count);
            }

            double fc = Math.sqrt(f0 * f1);
            // music falls off at high frequencies; tilt so the treble bars still move
            out[b] = (float)(Math.pow(power, 0.25) * Math.pow(fc / 120.0, 0.3));
         }

         return true;
      }
   }

   private static float mag(int k) {
      return (float)Math.hypot(re[k], im[k]) * 4.0F / N;
   }

   private static float magAt(double k) {
      int a = Math.max(1, (int)Math.floor(k));
      float t = (float)(k - a);
      return mag(a) * (1.0F - t) + mag(Math.min(N / 2 - 1, a + 1)) * t;
   }

   private static void fft(float[] xr, float[] xi) {
      int n = xr.length;
      int j = 0;

      for (int i = 1; i < n; i++) {
         int bit = n >> 1;

         while ((j & bit) != 0) {
            j ^= bit;
            bit >>= 1;
         }

         j |= bit;
         if (i < j) {
            float t = xr[i];
            xr[i] = xr[j];
            xr[j] = t;
            t = xi[i];
            xi[i] = xi[j];
            xi[j] = t;
         }
      }

      for (int len = 2; len <= n; len <<= 1) {
         double ang = -2.0 * Math.PI / len;
         float wr = (float)Math.cos(ang);
         float wi = (float)Math.sin(ang);

         for (int i = 0; i < n; i += len) {
            float cr = 1.0F;
            float ci = 0.0F;

            for (int k = 0; k < len / 2; k++) {
               int p = i + k;
               int q = p + len / 2;
               float tr = xr[q] * cr - xi[q] * ci;
               float ti = xr[q] * ci + xi[q] * cr;
               xr[q] = xr[p] - tr;
               xi[q] = xi[p] - ti;
               xr[p] += tr;
               xi[p] += ti;
               float ncr = cr * wr - ci * wi;
               ci = cr * wi + ci * wr;
               cr = ncr;
            }
         }
      }
   }
}
