package dev.musicplayer.viz;

import dev.musicplayer.MusicPlayerMod;
import java.io.InputStream;
import java.util.Locale;

/**
 * Listens to what the computer is playing (like cava does) through PulseAudio/PipeWire's
 * {@code parec}. Needed for Spotify, whose audio never passes through the mod.
 */
public final class SystemCapture {
   private static final int RATE = 48000;
   private static Process process;
   private static boolean unavailable = !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");

   private SystemCapture() {
   }

   public static boolean available() {
      return !unavailable;
   }

   static synchronized void setRunning(boolean on) {
      Spectrum.useSystem(on && !unavailable);
      if (on && !unavailable) {
         if (process == null || !process.isAlive()) {
            start();
         }
      } else if (process != null) {
         process.destroy();
         process = null;
      }
   }

   private static void start() {
      try {
         Process p = new ProcessBuilder(
               "parec", "-d", "@DEFAULT_MONITOR@", "--format=s16le", "--rate=" + RATE, "--channels=2", "--latency-msec=20", "--client-name=Overtone"
            )
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
         process = p;
         Thread t = new Thread(() -> pump(p), "musicplayer-capture");
         t.setDaemon(true);
         t.start();
         MusicPlayerMod.LOGGER.info("Visualizer: capturing system audio via parec");
      } catch (Exception var2) {
         unavailable = true;
         Spectrum.useSystem(false);
         MusicPlayerMod.LOGGER.warn("Visualizer: system audio capture unavailable ({}), install pulseaudio-utils / pipewire-pulse", var2.getMessage());
      }
   }

   private static void pump(Process p) {
      byte[] buf = new byte[1920];

      try (InputStream in = p.getInputStream()) {
         // always whole 4-byte frames: a short read cut mid-sample would shift every later sample
         // by a byte and turn the music into noise
         while (in.readNBytes(buf, 0, buf.length) == buf.length) {
            Spectrum.feedSystem(buf, buf.length, 2, RATE);
         }
      } catch (Exception var6) {
      }
   }
}
