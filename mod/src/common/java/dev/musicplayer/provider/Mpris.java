package dev.musicplayer.provider;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Talks to the local Spotify app over D-Bus (MPRIS, like media keys do) through the {@code gdbus} tool.
 * Spotify's Web API accepts "play this track" but the desktop app then hangs with every action
 * disallowed, so on Linux playback goes through here; the Web API is kept for search and library.
 * In the Prism flatpak this needs {@code --talk-name=org.mpris.MediaPlayer2.spotify}.
 */
final class Mpris {
   private static final String DEST = "org.mpris.MediaPlayer2.spotify";
   private static final String PATH = "/org/mpris/MediaPlayer2";
   private static final String PLAYER = "org.mpris.MediaPlayer2.Player";
   private static final Pattern STATUS = Pattern.compile("'PlaybackStatus': <'(\\w+)'>");
   private static final Pattern POSITION = Pattern.compile("'Position': <int64 (-?\\d+)>");
   private static final Pattern TRACK = Pattern.compile("'mpris:trackid': <'[^']*/([A-Za-z0-9]+)'>");
   private static final Pattern LENGTH = Pattern.compile("'mpris:length': <u?int64 (\\d+)>");
   private static final boolean LINUX = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
   private static final boolean FLATPAK = java.nio.file.Files.exists(java.nio.file.Path.of("/.flatpak-info"));
   private static volatile long checkedAt;
   private static volatile boolean available;

   private Mpris() {
   }

   /** Whether the Spotify app is running and reachable; re-checked at most every few seconds. */
   static boolean available() {
      long now = System.currentTimeMillis();
      if (now - checkedAt > 4000L) {
         checkedAt = now;
         try {
            available = LINUX
               && gdbus(
                     "--dest", "org.freedesktop.DBus", "--object-path", "/org/freedesktop/DBus", "--method", "org.freedesktop.DBus.NameHasOwner", DEST
                  )
                  .contains("true");
         } catch (IOException var3) {
            available = false;
         }
      }

      return available;
   }

   static boolean linux() {
      return LINUX;
   }

   /** Inside a flatpak sandbox (Prism), where D-Bus access must be granted explicitly. */
   static boolean sandboxed() {
      return FLATPAK;
   }

   static void openUri(String uri) throws IOException {
      player("OpenUri", uri);
   }

   static void play() throws IOException {
      player("Play");
   }

   static void pause() throws IOException {
      player("Pause");
   }

   static void setPosition(String trackId, long ms) throws IOException {
      player("SetPosition", "/com/spotify/track/" + trackId, String.valueOf(ms * 1000L));
   }

   static void setVolume(double v) throws IOException {
      gdbus("--dest", DEST, "--object-path", PATH, "--method", "org.freedesktop.DBus.Properties.Set", PLAYER, "Volume", "<" + v + ">");
   }

   /** Current playback, or null when the app is gone. */
   static Mpris.State state() throws IOException {
      String out = gdbus("--dest", DEST, "--object-path", PATH, "--method", "org.freedesktop.DBus.Properties.GetAll", PLAYER);
      Matcher st = STATUS.matcher(out);
      if (!st.find()) {
         return null;
      } else {
         Matcher pos = POSITION.matcher(out);
         Matcher tr = TRACK.matcher(out);
         Matcher len = LENGTH.matcher(out);
         return new Mpris.State(
            st.group(1), pos.find() ? Long.parseLong(pos.group(1)) / 1000L : 0L, tr.find() ? tr.group(1) : "", len.find() ? Long.parseLong(len.group(1)) / 1000L : 0L
         );
      }
   }

   private static void player(String method, String... args) throws IOException {
      List<String> a = new ArrayList<>(List.of("--dest", DEST, "--object-path", PATH, "--method", PLAYER + "." + method));
      a.addAll(List.of(args));
      gdbus(a.toArray(new String[0]));
   }

   private static String gdbus(String... args) throws IOException {
      List<String> cmd = new ArrayList<>(List.of("gdbus", "call", "--session"));
      cmd.addAll(List.of(args));
      Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();

      try (InputStream in = p.getInputStream()) {
         String out = new String(in.readNBytes(65536), StandardCharsets.UTF_8);
         if (!p.waitFor(3L, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("gdbus timeout");
         } else if (p.exitValue() != 0) {
            throw new IOException(out.trim());
         } else {
            return out;
         }
      } catch (InterruptedException var7) {
         Thread.currentThread().interrupt();
         throw new IOException("interrupted");
      }
   }

   @FunctionalInterface
   interface Call {
      void run() throws Exception;
   }

   record State(String status, long positionMs, String trackId, long lengthMs) {
      boolean playing() {
         return "Playing".equals(this.status);
      }
   }
}
