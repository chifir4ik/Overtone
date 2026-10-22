package dev.musicplayer.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.musicplayer.MusicPlayerMod;
import dev.musicplayer.config.TokenStore;
import dev.musicplayer.model.MusicEntity;
import dev.musicplayer.model.Page;
import dev.musicplayer.model.Platform;
import dev.musicplayer.model.Track;
import dev.musicplayer.net.SafeHttp;
import dev.musicplayer.util.Browser;
import dev.musicplayer.util.Json;
import dev.musicplayer.util.T;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Spotify as a remote control: the Web API cannot stream audio, so tracks play in the
 * user's own Spotify app (Spotify Connect) and this provider drives it and mirrors its state.
 */
public final class SpotifyProvider implements MusicProvider {
   public static final int PORT = 43821;
   public static final String REDIRECT = "http://127.0.0.1:43821/callback";
   private static final String API = "https://api.spotify.com/v1";
   private static final String ACCOUNTS = "https://accounts.spotify.com";
   private static final String SCOPES = "user-read-playback-state user-modify-playback-state user-library-read user-library-modify playlist-read-private playlist-read-collaborative";
   private static final Pattern CLIENT_ID = Pattern.compile("[0-9a-fA-F]{32}");
   private static final long LOGIN_TIMEOUT_MS = 180000L;
   private static final long END_WINDOW_MS = 3000L;
   private static final long SETTLE_MS = 3000L;
   private static final SafeHttp HTTP = new SafeHttp(Set.of("spotify.com"));
   private static final SecureRandom RANDOM = new SecureRandom();
   private static volatile ServerSocket pendingLogin;
   private final Set<String> likedIds = ConcurrentHashMap.newKeySet();
   private final AtomicInteger generation = new AtomicInteger();
   private volatile String clientId = TokenStore.get("spotifyClientId");
   private volatile String refreshToken = TokenStore.get("spotifyRefresh");
   private volatile String userName = orEmpty(TokenStore.get("spotifyName"));
   private volatile String accessToken;
   private volatile long accessExpires;
   private volatile String deviceId;
   private volatile Runnable finished;
   private volatile String playingId;
   private volatile long durationMs;
   private volatile long startedAt;
   private volatile boolean playing;
   private volatile long progressMs;
   private volatile long progressAt;
   private volatile float volume = -1.0F;
   private volatile int sentVolume = -1;
   private volatile boolean local;
   private volatile boolean confirmed;
   private volatile Track lastTrack;
   private volatile Consumer<String> errorSink;
   private Thread poller;

   @Override
   public Platform platform() {
      return Platform.SPOTIFY;
   }

   @Override
   public boolean isLoggedIn() {
      return this.refreshToken != null;
   }

   @Override
   public String accountName() {
      return this.isLoggedIn() ? this.userName : "";
   }

   public String savedClientId() {
      return orEmpty(this.clientId);
   }

   @Override
   public void logout() {
      this.stop();
      this.refreshToken = null;
      this.accessToken = null;
      this.userName = "";
      this.deviceId = null;
      this.likedIds.clear();
      TokenStore.set("spotifyRefresh", null);
      TokenStore.set("spotifyName", null);
   }

   public CompletableFuture<Void> login(String input) {
      String id = input == null ? "" : input.trim();
      if (!CLIENT_ID.matcher(id).matches()) {
         return CompletableFuture.failedFuture(new IOException(T.get("err.sp_bad_client")));
      } else {
         return Background.run(() -> {
            String verifier = randomToken(64);
            String state = randomToken(16);
            String challenge = Base64.getUrlEncoder()
               .withoutPadding()
               .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            String code;
            try (ServerSocket server = this.listen()) {
               Browser.open(
                  ACCOUNTS
                     + "/authorize?response_type=code&client_id="
                     + id
                     + "&scope="
                     + enc(SCOPES)
                     + "&redirect_uri="
                     + enc(REDIRECT)
                     + "&code_challenge_method=S256&code_challenge="
                     + challenge
                     + "&state="
                     + state
               );
               code = awaitCode(server, state);
            }

            JsonObject tok = tokenRequest(
               "grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(REDIRECT) + "&client_id=" + id + "&code_verifier=" + verifier
            );
            this.clientId = id;
            TokenStore.set("spotifyClientId", id);
            this.acceptTokens(tok);
            JsonObject me = this.api("GET", "/me", null);
            String name = Json.str(me, "display_name");
            this.userName = name.isBlank() ? Json.str(me, "id") : name;
            TokenStore.set("spotifyName", this.userName);
            this.refreshLikes();
            return null;
         });
      }
   }

   private ServerSocket listen() throws IOException {
      ServerSocket old = pendingLogin;
      if (old != null) {
         old.close();
      }

      ServerSocket s = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"));
      s.setSoTimeout((int)LOGIN_TIMEOUT_MS);
      pendingLogin = s;
      return s;
   }

   private static String awaitCode(ServerSocket server, String state) throws IOException {
      long deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS;

      while (System.currentTimeMillis() < deadline) {
         try (Socket c = server.accept()) {
            c.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.US_ASCII));
            String line = in.readLine();

            String header;
            do {
               header = in.readLine();
            } while (header != null && !header.isEmpty());

            String[] parts = line == null ? new String[0] : line.split(" ");
            if (parts.length < 2 || !parts[1].startsWith("/callback")) {
               reply(c, 404, "");
            } else {
               Map<String, String> q = query(parts[1]);
               if (!state.equals(q.get("state"))) {
                  reply(c, 400, T.get("err.sp_denied"));
                  throw new IOException(T.get("err.sp_denied"));
               }

               if (q.containsKey("error") || !q.containsKey("code")) {
                  reply(c, 200, T.get("err.sp_denied"));
                  throw new IOException(T.get("err.sp_denied"));
               }

               reply(c, 200, T.get("login.sp_done"));
               return q.get("code");
            }
         } catch (SocketTimeoutException var11) {
            break;
         }
      }

      throw new IOException(T.get("err.sp_timeout"));
   }

   private static void reply(Socket c, int status, String text) throws IOException {
      String html = "<!doctype html><meta charset=utf-8><title>Overtone</title><body style=\"font:18px sans-serif;background:#121212;color:#fff;text-align:center;padding-top:20vh\">"
         + text.replace("<", "&lt;")
         + "</body>";
      byte[] body = html.getBytes(StandardCharsets.UTF_8);
      OutputStream out = c.getOutputStream();
      out.write(
         ("HTTP/1.1 " + status + " OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII)
      );
      out.write(body);
      out.flush();
   }

   private static Map<String, String> query(String path) {
      Map<String, String> out = new HashMap<>();
      int i = path.indexOf(63);
      if (i < 0) {
         return out;
      } else {
         for (String kv : path.substring(i + 1).split("&")) {
            int eq = kv.indexOf(61);
            if (eq > 0) {
               out.put(URLDecoder.decode(kv.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
            }
         }

         return out;
      }
   }

   private static JsonObject tokenRequest(String form) throws Exception {
      SafeHttp.Reply r = HTTP.send("POST", ACCOUNTS + "/api/token", Map.of("Content-Type", "application/x-www-form-urlencoded"), form);
      JsonObject o = parse(r.body());
      if (r.status() / 100 != 2) {
         String why = Json.str(o, "error_description");
         throw new IOException("Spotify: " + (why.isEmpty() ? "HTTP " + r.status() : why));
      } else {
         return o;
      }
   }

   private void acceptTokens(JsonObject tok) {
      this.accessToken = Json.str(tok, "access_token");
      long ttl = tok.has("expires_in") ? tok.get("expires_in").getAsLong() : 3600L;
      this.accessExpires = System.currentTimeMillis() + (ttl - 60L) * 1000L;
      String refresh = Json.str(tok, "refresh_token");
      if (!refresh.isEmpty()) {
         this.refreshToken = refresh;
         TokenStore.set("spotifyRefresh", refresh);
      }
   }

   private synchronized String token(boolean force) throws Exception {
      String rt = this.refreshToken;
      if (rt == null) {
         throw new IOException(T.get("err.sp_relogin"));
      } else if (!force && this.accessToken != null && System.currentTimeMillis() < this.accessExpires) {
         return this.accessToken;
      } else {
         try {
            this.acceptTokens(tokenRequest("grant_type=refresh_token&refresh_token=" + enc(rt) + "&client_id=" + this.clientId));
         } catch (IOException var4) {
            MusicPlayerMod.LOGGER.warn("Spotify token refresh failed: {}", var4.getMessage());
            throw new IOException(T.get("err.sp_relogin"));
         }

         return this.accessToken;
      }
   }

   private JsonObject api(String method, String path, String json) throws Exception {
      for (int attempt = 0; ; attempt++) {
         Map<String, String> h = new HashMap<>();
         h.put("Authorization", "Bearer " + this.token(attempt > 0));
         h.put("Accept", "application/json");
         if (json != null) {
            h.put("Content-Type", "application/json");
         }

         SafeHttp.Reply r = HTTP.send(method, API + path, h, json);
         if (r.status() != 401 || attempt != 0) {
            if (r.status() / 100 == 2) {
               return parse(r.body());
            }

            JsonObject err = Json.obj(parse(r.body()), "error");
            String reason = Json.str(err, "reason");
            if ("NO_ACTIVE_DEVICE".equals(reason) || r.status() == 404 && path.startsWith("/me/player")) {
               this.deviceId = null;
               throw new IOException(T.get("err.sp_no_device"));
            }

            if ("PREMIUM_REQUIRED".equals(reason)) {
               throw new IOException(T.get("err.sp_premium"));
            }

            String m = Json.str(err, "message");
            throw new SpotifyProvider.HttpError(r.status(), "Spotify: " + (m.isEmpty() ? "HTTP " + r.status() : m));
         }
      }
   }

   private static JsonObject parse(String body) {
      try {
         JsonElement e = body == null || body.isBlank() ? null : JsonParser.parseString(body);
         return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
      } catch (Exception var2) {
         return new JsonObject();
      }
   }

   @Override
   public CompletableFuture<List<Track>> search(String q) {
      return Background.run(() -> {
         List<Track> out = new ArrayList<>();

         for (int offset = 0; offset < 30; offset += 10) {
            JsonObject r = this.api("GET", "/search?type=track&limit=10&offset=" + offset + "&q=" + enc(q), null);
            JsonArray items = Json.array(Json.obj(r, "tracks"), "items");
            addTracks(out, items, false);
            if (items.size() < 10) {
               break;
            }
         }

         return out;
      });
   }

   @Override
   public CompletableFuture<List<Track>> likedTracks() {
      return Background.run(() -> {
         List<Track> out = new ArrayList<>();

         for (int offset = 0; offset < 500; offset += 50) {
            JsonArray items = Json.array(this.api("GET", "/me/tracks?limit=50&offset=" + offset, null), "items");
            addTracks(out, items, true);
            if (items.size() < 50) {
               break;
            }
         }

         for (Track t : out) {
            this.likedIds.add(t.id());
         }

         return out;
      });
   }

   private void refreshLikes() {
      this.likedTracks().exceptionally(e -> {
         MusicPlayerMod.LOGGER.debug("Spotify likes unavailable: {}", e.getMessage());
         return List.of();
      });
   }

   /** Tracks and playlists for one query, for the Tracks / Playlists tabs. */
   public CompletableFuture<SpotifyProvider.SearchAll> searchAll(String q) {
      return Background.run(() -> {
         List<Track> tracks = new ArrayList<>();
         List<MusicEntity> playlists = new ArrayList<>();

         for (int offset = 0; offset < 30; offset += 10) {
            String types = offset < 20 ? "track,playlist" : "track";
            JsonObject r = this.api("GET", "/search?type=" + enc(types) + "&limit=10&offset=" + offset + "&q=" + enc(q), null);
            JsonArray items = Json.array(Json.obj(r, "tracks"), "items");
            addTracks(tracks, items, false);
            addPlaylists(playlists, Json.array(Json.obj(r, "playlists"), "items"));
            if (items.size() < 10) {
               break;
            }
         }

         return new SpotifyProvider.SearchAll(tracks, playlists);
      });
   }

   /** Opens "my playlists" (MusicEntity.MINE) or one playlist's tracks. */
   public CompletableFuture<Page> open(MusicEntity e) {
      return Background.run(() -> {
         if (e.isMine()) {
            List<MusicEntity> mine = new ArrayList<>();

            for (int offset = 0; offset < 300; offset += 50) {
               JsonArray items;
               try {
                  items = Json.array(this.api("GET", "/me/playlists?limit=50&offset=" + offset, null), "items");
               } catch (SpotifyProvider.HttpError var7) {
                  // logged in before playlists were supported: the token lacks the playlist scopes
                  throw var7.status != 401 && var7.status != 403 ? var7 : new IOException(T.get("err.sp_rescope"));
               }

               addPlaylists(mine, items);
               if (items.size() < 50) {
                  break;
               }
            }

            return new Page(e, List.of(), mine);
         } else {
            List<Track> tracks = new ArrayList<>();
            String base = "/playlists/" + enc(e.id()) + "/items";

            for (int offset = 0; offset < 1000; offset += 50) {
               JsonArray items;
               try {
                  items = Json.array(this.api("GET", base + "?limit=50&additional_types=track&offset=" + offset, null), "items");
               } catch (SpotifyProvider.HttpError var8) {
                  if (var8.status == 403) {
                     throw new IOException(T.get("err.sp_foreign_playlist"));
                  }

                  if (var8.status != 404 || !base.endsWith("/items")) {
                     throw var8;
                  }

                  // older API shape, before the 2026 rename
                  base = "/playlists/" + enc(e.id()) + "/tracks";
                  offset -= 50;
                  continue;
               }

               addTracks(tracks, items, true);
               if (items.size() < 50) {
                  break;
               }
            }

            return new Page(e, tracks, List.of());
         }
      });
   }

   private static void addPlaylists(List<MusicEntity> out, JsonArray items) {
      for (JsonElement e : items) {
         if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            String id = Json.str(o, "id");
            if (!id.isEmpty()) {
               JsonObject count = o.has("items") && o.get("items").isJsonObject() ? Json.obj(o, "items") : Json.obj(o, "tracks");
               String owner = Json.str(Json.obj(o, "owner"), "display_name");
               String total = Json.str(count, "total");
               String sub = owner + (total.isEmpty() ? "" : (owner.isEmpty() ? "" : " · ") + T.tracks(Integer.parseInt(total)));
               JsonArray images = Json.array(o, "images");
               String cover = imageUrl(images);
               out.add(new MusicEntity(MusicEntity.Kind.PLAYLIST, id, Json.str(o, "name"), sub, cover.isEmpty() ? null : cover));
            }
         }
      }
   }

   private static void addTracks(List<Track> out, JsonArray items, boolean wrapped) {
      for (JsonElement e : items) {
         if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (wrapped) {
               // playlist rows wrap the track in "item" (2026) or "track" (before)
               o = o.has("item") && o.get("item").isJsonObject() ? Json.obj(o, "item") : Json.obj(o, "track");
            }

            String type = Json.str(o, "type");
            String id = Json.str(o, "id");
            if (!id.isEmpty() && (type.isEmpty() || type.equals("track")) && !"true".equals(Json.str(o, "is_local"))) {
               out.add(parseTrack(o));
            }
         }
      }
   }

   private static Track parseTrack(JsonObject o) {
      List<String> artists = new ArrayList<>();

      for (JsonElement a : Json.array(o, "artists")) {
         if (a.isJsonObject()) {
            String n = Json.str(a.getAsJsonObject(), "name");
            if (!n.isEmpty()) {
               artists.add(n);
            }
         }
      }

      JsonObject album = Json.obj(o, "album");
      JsonArray images = Json.array(album, "images");
      String cover = imageUrl(images);
      long duration = o.has("duration_ms") && !o.get("duration_ms").isJsonNull() ? o.get("duration_ms").getAsLong() : 0L;
      String albumName = Json.str(album, "name");
      return new Track(
         Json.str(o, "id"),
         Json.str(o, "name"),
         String.join(", ", artists),
         albumName.isEmpty() ? null : albumName,
         duration,
         cover.isEmpty() ? null : cover,
         Platform.SPOTIFY.id
      );
   }

   @Override
   public boolean supportsLikes() {
      return this.isLoggedIn();
   }

   @Override
   public boolean isLiked(String trackId) {
      return this.likedIds.contains(trackId);
   }

   @Override
   public CompletableFuture<Void> setLiked(Track t, boolean like) {
      return Background.run(() -> {
         String method = like ? "PUT" : "DELETE";

         try {
            this.api(method, "/me/library?uris=" + enc("spotify:track:" + t.id()), null);
         } catch (SpotifyProvider.HttpError var5) {
            if (var5.status != 404 && var5.status != 410) {
               throw var5;
            }

            this.api(method, "/me/tracks?ids=" + t.id(), null);
         }

         if (like) {
            this.likedIds.add(t.id());
         } else {
            this.likedIds.remove(t.id());
         }

         return null;
      });
   }

   private String device() throws Exception {
      String id = this.deviceId;
      if (id != null) {
         return id;
      } else {
         String fallback = null;

         for (JsonElement e : Json.array(this.api("GET", "/me/player/devices", null), "devices")) {
            JsonObject d = e.getAsJsonObject();
            if (!"true".equals(Json.str(d, "is_restricted"))) {
               if ("true".equals(Json.str(d, "is_active"))) {
                  fallback = Json.str(d, "id");
                  break;
               }

               if (fallback == null || "computer".equalsIgnoreCase(Json.str(d, "type"))) {
                  fallback = Json.str(d, "id");
               }
            }
         }

         if (fallback != null && !fallback.isEmpty()) {
            this.deviceId = fallback;
            return fallback;
         } else {
            throw new IOException(T.get("err.sp_no_device"));
         }
      }
   }

   private JsonObject player(String method, String path, String json) throws Exception {
      String sep = path.contains("?") ? "&" : "?";

      try {
         return this.api(method, "/me/player" + path + sep + "device_id=" + enc(this.device()), json);
      } catch (IOException var6) {
         if (this.deviceId != null) {
            throw var6;
         } else {
            return this.api(method, "/me/player" + path + sep + "device_id=" + enc(this.device()), json);
         }
      }
   }

   @Override
   public CompletableFuture<Void> play(Track t) {
      int my = this.generation.incrementAndGet();
      this.playingId = null;
      this.playing = false;
      this.lastTrack = t;
      return Background.run(() -> {
         // на Linux команда play из Web API вешает приложение, поэтому играем через него самого (MPRIS)
         this.local = Mpris.available();
         if (!this.local && Mpris.linux()) {
            throw new IOException(T.get(Mpris.sandboxed() ? "err.sp_flatpak" : "err.sp_open_app"));
         }

         if (this.local) {
            Mpris.openUri("spotify:track:" + t.id());
         } else {
            this.player("PUT", "/play", "{\"uris\":[\"spotify:track:" + t.id() + "\"],\"position_ms\":0}");
         }

         if (this.generation.get() == my) {
            this.confirmed = false;
            this.durationMs = t.durationMs();
            this.progressMs = 0L;
            this.progressAt = System.currentTimeMillis();
            this.startedAt = this.progressAt;
            this.playing = true;
            this.playingId = t.id();
            this.sentVolume = -1;
            this.ensurePoller();
         }

         if (this.local) {
            // через секунду после открытия трека Spotify прыгает на место, где его бросили: возвращаем на начало.
            // Считается только позиция дальше, чем реально прошло времени, иначе перезапустился бы честно начатый трек.
            long opened = System.currentTimeMillis();
            for (int i = 0; i < 30 && this.generation.get() == my; i++) {
               Thread.sleep(100L);
               Mpris.State st = Mpris.state();
               if (st != null && t.id().equals(st.trackId()) && st.positionMs() > System.currentTimeMillis() - opened + 1500L) {
                  Mpris.setPosition(t.id(), 0L);
                  this.progressMs = 0L;
                  this.progressAt = System.currentTimeMillis();
                  break;
               }
            }
         }

         return null;
      });
   }

   @Override
   public void stop() {
      this.generation.incrementAndGet();
      boolean was = this.playingId != null && this.playing;
      this.playingId = null;
      this.playing = false;
      if (was) {
         this.control("PUT", "/pause", Mpris::pause);
      }
   }

   @Override
   public void pause() {
      if (this.playingId != null) {
         this.progressMs = this.positionMs();
         this.progressAt = System.currentTimeMillis();
         this.playing = false;
         this.control("PUT", "/pause", Mpris::pause);
      }
   }

   @Override
   public void resume() {
      if (this.playingId != null) {
         this.progressAt = System.currentTimeMillis();
         this.playing = true;
         this.control("PUT", "/play", Mpris::play);
      } else if (this.lastTrack != null) {
         // трек потерян (Spotify переключили из приложения): запускаем заново, а не молчим
         this.play(this.lastTrack);
      }
   }

   @Override
   public void seek(long ms) {
      if (this.playingId != null) {
         this.progressMs = ms;
         this.progressAt = System.currentTimeMillis();
         String id = this.playingId;
         this.control("PUT", "/seek?position_ms=" + ms, () -> Mpris.setPosition(id, ms));
      }
   }

   private void control(String method, String path, Mpris.Call viaApp) {
      Background.run(() -> {
         if (this.local) {
            viaApp.run();
            return null;
         }

         return this.player(method, path, null);
      }).exceptionally(e -> {
         MusicPlayerMod.LOGGER.warn("Spotify {} failed: {}", path, e.getMessage());
         return null;
      });
   }

   @Override
   public long positionMs() {
      if (this.playingId == null) {
         return 0L;
      } else {
         long p = this.progressMs + (this.playing ? System.currentTimeMillis() - this.progressAt : 0L);
         return this.durationMs > 0L ? Math.min(p, this.durationMs) : p;
      }
   }

   @Override
   public boolean isPlaying() {
      return this.playingId != null && this.playing;
   }

   @Override
   public void setVolume(float v) {
      this.volume = v;
   }

   @Override
   public void onFinished(Runnable r) {
      this.finished = r;
   }

   @Override
   public void onAudioError(Consumer<String> sink) {
      this.errorSink = sink;
   }

   private synchronized void ensurePoller() {
      if (this.poller == null || !this.poller.isAlive()) {
         this.poller = new Thread(this::pollLoop, "musicplayer-spotify");
         this.poller.setDaemon(true);
         this.poller.start();
      }
   }

   private void pollLoop() {
      while (true) {
         long sleep = 1000L;

         try {
            if (this.playingId != null && this.isLoggedIn()) {
               this.pushVolume();
               this.poll();
               long left = this.durationMs - this.positionMs();
               if (this.playing && left < 2500L) {
                  sleep = 400L;
               }
            }
         } catch (Exception var5) {
            MusicPlayerMod.LOGGER.debug("Spotify poll failed: {}", var5.getMessage());
            sleep = 3000L;
         }

         try {
            Thread.sleep(sleep);
         } catch (InterruptedException var4) {
            return;
         }
      }
   }

   private void pushVolume() {
      float v = this.volume;
      int pct = spotifyPercent(v);
      if (v >= 0.0F && pct != this.sentVolume) {
         this.sentVolume = pct;

         try {
            if (this.local) {
               Mpris.setVolume(pct / 100.0);
            } else {
               this.player("PUT", "/volume?volume_percent=" + pct, null);
            }
         } catch (Exception var4) {
            MusicPlayerMod.LOGGER.debug("Spotify volume not applied: {}", var4.getMessage());
         }
      }
   }

   /** Громкость плеера мода линейная, а у Spotify она кубическая (громкость потока PipeWire): кубический корень уравнивает их. */
   static int spotifyPercent(float v) {
      float c = Math.max(0.0F, Math.min(1.0F, v));
      return c <= 0.0F ? 0 : Math.max(1, Math.round((float)Math.cbrt(c) * 100.0F));
   }

   private void poll() throws Exception {
      int gen = this.generation.get();
      String id = this.playingId;
      boolean present;
      String itemId;
      boolean isPlaying;
      long progress;
      if (this.local) {
         Mpris.State st = Mpris.state();
         present = st != null;
         itemId = present ? st.trackId() : "";
         isPlaying = present && st.playing();
         progress = present ? st.positionMs() : 0L;
      } else {
         JsonObject s = this.api("GET", "/me/player", null);
         present = s.size() != 0;
         itemId = Json.str(Json.obj(s, "item"), "id");
         isPlaying = "true".equals(Json.str(s, "is_playing"));
         progress = s.has("progress_ms") && !s.get("progress_ms").isJsonNull() ? s.get("progress_ms").getAsLong() : 0L;
      }

      if (id != null && gen == this.generation.get() && id.equals(this.playingId)) {
         long now = System.currentTimeMillis();
         boolean settling = now - this.startedAt < SETTLE_MS;
         boolean nearEnd = this.durationMs > 0L && this.positionMs() >= this.durationMs - END_WINDOW_MS;
         if (present && itemId.equals(id) && isPlaying) {
            this.confirmed = true;
         }

         if (present && itemId.equals(id)) {
            boolean wrapped = progress + 1000L < this.progressMs;
            boolean stoppedAtEnd = !isPlaying && (progress == 0L || this.durationMs > 0L && progress >= this.durationMs - 1000L);
            if (nearEnd && !settling && (wrapped || stoppedAtEnd)) {
               this.finish(gen);
            } else {
               this.progressMs = progress;
               this.progressAt = now;
               this.playing = isPlaying;
            }
         } else if (!settling) {
            if (nearEnd) {
               this.finish(gen);
            } else {
               this.playingId = null;
               this.playing = false;
               if (!this.confirmed) {
                  // так и не заиграл: говорим об этом, а не показываем молча остановленный плеер
                  Consumer<String> sink = this.errorSink;
                  if (sink != null) {
                     sink.accept(T.get("err.sp_not_started"));
                  }
               }
            }
         }
      }
   }

   private void finish(int gen) {
      if (this.generation.compareAndSet(gen, gen + 1)) {
         this.playingId = null;
         this.playing = false;
         Runnable r = this.finished;
         if (r != null) {
            r.run();
         }
      }
   }

   private static String randomToken(int bytes) {
      byte[] b = new byte[bytes];
      RANDOM.nextBytes(b);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
   }

   /** Spotify отдаёт картинки от большой к маленькой (640, 300, 64): для обложки 256 px хватает второй. */
   private static String imageUrl(JsonArray images) {
      int i = Math.min(1, images.size() - 1);
      return i >= 0 && images.get(i).isJsonObject() ? Json.str(images.get(i).getAsJsonObject(), "url") : "";
   }

   private static String orEmpty(String s) {
      return s == null ? "" : s;
   }

   private static String enc(String s) {
      return URLEncoder.encode(s, StandardCharsets.UTF_8);
   }

   public record SearchAll(List<Track> tracks, List<MusicEntity> playlists) {
   }

   static final class HttpError extends IOException {
      final int status;

      HttpError(int status, String message) {
         super(message);
         this.status = status;
      }
   }
}
