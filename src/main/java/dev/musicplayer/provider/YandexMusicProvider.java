package dev.musicplayer.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.musicplayer.audio.Mp3Player;
import dev.musicplayer.config.TokenStore;
import dev.musicplayer.lyrics.LrcParser;
import dev.musicplayer.model.LyricLine;
import dev.musicplayer.model.Track;
import dev.musicplayer.util.Browser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Яндекс Музыка через НЕОФИЦИАЛЬНЫЙ API (api.music.yandex.net) — официального публичного API у сервиса нет.
 * Вход: открываем страницу oauth.yandex.ru, пользователь копирует токен из адресной строки и вставляет в игру.
 */
public final class YandexMusicProvider implements MusicProvider {
    public static final String OAUTH_URL =
            "https://oauth.yandex.ru/authorize?response_type=token&client_id=23cabbbdc6cd418abb4b39c32c41195d";
    private static final int MAX_TRACK_BYTES = 120 * 1024 * 1024;
    private static final String API = "https://api.music.yandex.net";
    private static final String SIGN_SALT = "XGRlBW9FXlekgbPrRHuSiA";
    private static final String LYRICS_SIGN_KEY = "p93jhgh689SBReK6ghtw62";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final ExecutorService EXEC = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "musicplayer-net"); t.setDaemon(true); return t;
    });

    private volatile String token = TokenStore.get("yandexToken");
    private volatile String uid;
    private volatile String accountName = "";
    private final Set<String> likedIds = ConcurrentHashMap.newKeySet();
    private volatile boolean verified;
    private volatile CompletableFuture<Void> loginFuture;

    private final Mp3Player player = new Mp3Player();
    private final AtomicInteger generation = new AtomicInteger();
    private volatile Runnable finished;

    public YandexMusicProvider() {
        if (token != null) verifyAsync();
    }

    @Override public String name() { return "Яндекс Музыка"; }
    @Override public boolean isLoggedIn() { return token != null && verified; }

    public String accountName() { return accountName; }

    public boolean isLiked(String trackId) { return trackId != null && likedIds.contains(trackId); }

    private void readAccount(JsonObject result) {
        JsonObject acc = result.getAsJsonObject("account");
        uid = acc.get("uid").getAsString();
        String n = str(acc, "displayName");
        if (n.isEmpty()) n = str(acc, "fullName");
        if (n.isEmpty()) n = str(acc, "login");
        accountName = n;
    }

    private void refreshLikes() {
        async(() -> {
            JsonObject r = getResult(API + "/users/" + uid + "/likes/tracks");
            Set<String> ids = new java.util.HashSet<>();
            for (JsonElement e : r.getAsJsonObject("library").getAsJsonArray("tracks"))
                ids.add(e.getAsJsonObject().get("id").getAsString());
            likedIds.clear();
            likedIds.addAll(ids);
            return null;
        }).exceptionally(e -> null);
    }

    public CompletableFuture<Void> setLiked(Track t, boolean like) {
        return async(() -> {
            String path = like ? "/likes/tracks/add-multiple" : "/likes/tracks/remove";
            HttpRequest req = base(API + "/users/" + uid + path)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("track-ids=" + enc(t.id())))
                    .build();
            send(req);
            if (like) likedIds.add(t.id()); else likedIds.remove(t.id());
            return null;
        });
    }

    @Override public CompletableFuture<Void> login() {
        Browser.open(OAUTH_URL);
        loginFuture = new CompletableFuture<>();
        return loginFuture;
    }

    public CompletableFuture<Void> submitToken(String input) {
        String t = extractToken(input);
        if (t.isEmpty()) return CompletableFuture.failedFuture(new IOException("Пустой токен"));
        String old = token;
        token = t;
        return async(() -> {
            try {
                readAccount(getResult(API + "/account/status"));
            } catch (Exception e) {
                token = old;
                String m = String.valueOf(e.getMessage());
                // Яндекс отверг токен — в списке аккаунтов он больше не нужен
                if (m.contains("HTTP 401") || m.contains("HTTP 403")) TokenStore.removeAccountByToken(t);
                throw e;
            }
            verified = true;
            refreshLikes();
            TokenStore.set("yandexToken", token);
            TokenStore.putAccount(uid, accountName, token);
            CompletableFuture<Void> f = loginFuture;
            if (f != null) f.complete(null);
            return null;
        });
    }

    private void verifyAsync() {
        async(() -> {
            readAccount(getResult(API + "/account/status"));
            verified = true;
            refreshLikes();
            // заодно переносим токен из старых версий в список аккаунтов
            TokenStore.putAccount(uid, accountName, token);
            return null;
        }).exceptionally(e -> {
            dev.musicplayer.MusicPlayerMod.LOGGER.warn("Yandex token check failed", e);
            // токен стираем только если Яндекс его реально отверг; сбой сети не должен разлогинивать
            String m = String.valueOf(e.getMessage());
            if (m.contains("HTTP 401") || m.contains("HTTP 403")) {
                TokenStore.removeAccountByToken(token);
                token = null;
                TokenStore.set("yandexToken", null);
            }
            return null;
        });
    }

    private static String extractToken(String s) {
        if (s == null) return "";
        s = s.trim();
        int i = s.indexOf("access_token=");
        if (i >= 0) {
            s = s.substring(i + "access_token=".length());
            int amp = s.indexOf('&');
            if (amp >= 0) s = s.substring(0, amp);
        }
        return s.trim();
    }

    /** Токен остаётся в списке сохранённых аккаунтов — его можно выбрать на экране входа. */
    @Override public void logout() {
        generation.incrementAndGet();
        desired = Set.of(); currentId = null; prefetchList = List.of(); playLoading = false;
        player.stop();
        token = null; uid = null; verified = false; accountName = "";
        likedIds.clear();
        TokenStore.set("yandexToken", null);
    }

    public List<TokenStore.Account> savedAccounts() { return TokenStore.accounts(); }

    public void forgetAccount(TokenStore.Account a) {
        if (a == null) return;
        if (a.uid() != null) TokenStore.removeAccountByUid(a.uid());
        TokenStore.removeAccountByToken(a.token());
    }

    @Override public CompletableFuture<List<Track>> search(String query) {
        return async(() -> {
            JsonObject r = getResult(API + "/search?text=" + enc(query) + "&nocorrect=false&type=track&page=0");
            List<Track> out = new ArrayList<>();
            JsonObject tracks = r.getAsJsonObject("tracks");
            if (tracks == null || !tracks.has("results")) return out;
            for (JsonElement e : tracks.getAsJsonArray("results")) out.add(parseTrack(e.getAsJsonObject()));
            return out;
        });
    }

    @Override public CompletableFuture<List<Track>> likedTracks() {
        return async(() -> {
            JsonObject r = getResult(API + "/users/" + uid + "/likes/tracks");
            JsonArray arr = r.getAsJsonObject("library").getAsJsonArray("tracks");
            List<String> ids = new ArrayList<>();
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                String id = o.get("id").getAsString();
                ids.add(o.has("albumId") && !o.get("albumId").isJsonNull() ? id + ":" + o.get("albumId").getAsString() : id);
            }
            Collections.reverse(ids);
            if (ids.size() > 100) ids = ids.subList(0, 100);
            if (ids.isEmpty()) return new ArrayList<Track>();

            HttpRequest req = base(API + "/tracks")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("track-ids=" + enc(String.join(",", ids))))
                    .build();
            JsonArray full = send(req).getAsJsonArray("result");
            List<Track> out = new ArrayList<>();
            for (JsonElement e : full) out.add(parseTrack(e.getAsJsonObject()));
            return out;
        });
    }

    /**
     * Текст трека от самой Яндекс Музыки: synced=true — LRC с таймкодами, false — обычный текст.
     * Пустой список, если текста нет или запрос не удался (тогда вызывающий пробует другой источник).
     */
    public CompletableFuture<List<LyricLine>> lyrics(Track t, boolean synced) {
        return async(() -> {
            if (token == null || !verified) return List.<LyricLine>of();
            String ts = String.valueOf(System.currentTimeMillis() / 1000);
            JsonObject r = getResult(API + "/tracks/" + t.id() + "/lyrics?format=" + (synced ? "LRC" : "TEXT")
                    + "&timeStamp=" + ts + "&sign=" + enc(lyricsSign(t.id() + ts)));
            String link = str(r, "downloadUrl");
            if (link.isEmpty()) return List.<LyricLine>of();
            HttpResponse<String> body = HTTP.send(HttpRequest.newBuilder(URI.create(link))
                    .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (body.statusCode() != 200) return List.<LyricLine>of();
            return synced ? LrcParser.parse(body.body()) : LrcParser.plain(body.body());
        }).exceptionally(e -> {
            dev.musicplayer.MusicPlayerMod.LOGGER.debug("Yandex lyrics unavailable for {}: {}", t.id(), e.getMessage());
            return List.of();
        });
    }

    private static String lyricsSign(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(LYRICS_SIGN_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    public record WaveBatch(String sessionId, String batchId, List<Track> tracks) {}

    private static final String WAVE_SEED = "user:onyourwave";
    /** id трека → «id:albumId»: волне нужен именно такой формат в параметре queue. */
    private static final java.util.Map<String, String> FULL_ID = new ConcurrentHashMap<>();

    public CompletableFuture<WaveBatch> waveStart() {
        return async(this::newWaveSession);
    }

    private WaveBatch newWaveSession() throws Exception {
        JsonObject body = new JsonObject();
        JsonArray seeds = new JsonArray();
        seeds.add(WAVE_SEED);
        body.add("seeds", seeds);
        body.addProperty("includeTracksInResponse", true);
        JsonObject r = postJson(API + "/rotor/session/new", body).getAsJsonObject("result");
        String sid = str(r, "radioSessionId");
        if (sid.isEmpty()) throw new IOException("Яндекс не вернул сессию волны");
        return readBatch(sid, r);
    }

    public CompletableFuture<WaveBatch> waveMore(String sessionId, String lastTrackId) {
        return async(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("queue", FULL_ID.getOrDefault(lastTrackId, lastTrackId));
                JsonObject r = postJson(API + "/rotor/session/" + sessionId + "/tracks", body).getAsJsonObject("result");
                WaveBatch b = readBatch(sessionId, r);
                if (!b.tracks().isEmpty()) return b;
            } catch (IOException e) {
                dev.musicplayer.MusicPlayerMod.LOGGER.warn("Wave /tracks failed, starting a fresh session: {}", e.getMessage());
            }
            // сессия протухла или формат не подошёл — просто открываем новую: волна бесконечная
            return newWaveSession();
        });
    }

    private static WaveBatch readBatch(String sid, JsonObject r) {
        List<Track> out = new ArrayList<>();
        if (r.has("sequence") && r.get("sequence").isJsonArray()) {
            for (JsonElement e : r.getAsJsonArray("sequence")) {
                JsonObject o = e.getAsJsonObject();
                if (o.has("track") && o.get("track").isJsonObject()) out.add(parseTrack(o.getAsJsonObject("track")));
            }
        }
        return new WaveBatch(sid, str(r, "batchId"), out);
    }

    /** Сообщает волне, что случилось с треком: radioStarted / trackStarted / trackFinished / skip. Ошибки молча игнорируются. */
    public void waveFeedback(String sessionId, String batchId, String type, String trackId, double playedSeconds) {
        if (sessionId == null || token == null) return;
        async(() -> {
            JsonObject ev = new JsonObject();
            ev.addProperty("type", type);
            ev.addProperty("timestamp", java.time.Instant.now().toString());
            if (trackId != null) ev.addProperty("trackId", trackId);
            ev.addProperty("totalPlayedSeconds", Math.max(0, playedSeconds));
            JsonObject body = new JsonObject();
            body.add("event", ev);
            if (batchId != null && !batchId.isEmpty()) body.addProperty("batchId", batchId);
            postJson(API + "/rotor/session/" + sessionId + "/feedback", body);
            return null;
        }).exceptionally(e -> null);
    }

    public CompletableFuture<Void> dislike(Track t) {
        return async(() -> {
            HttpRequest req = base(API + "/users/" + uid + "/dislikes/tracks/add-multiple")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("track-ids=" + enc(t.id())))
                    .build();
            send(req);
            likedIds.remove(t.id());
            return null;
        });
    }

    private JsonObject postJson(String url, JsonObject body) throws IOException, InterruptedException {
        return send(base(url).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build());
    }

    private static Track parseTrack(JsonObject t) {
        String title = str(t, "title");
        String version = str(t, "version");
        if (!version.isEmpty()) title += " (" + version + ")";
        List<String> artists = new ArrayList<>();
        if (t.has("artists")) for (JsonElement a : t.getAsJsonArray("artists")) artists.add(str(a.getAsJsonObject(), "name"));
        String album = null;
        if (t.has("albums") && t.getAsJsonArray("albums").size() > 0 && t.has("id")
                && t.getAsJsonArray("albums").get(0).getAsJsonObject().has("id"))
            FULL_ID.put(t.get("id").getAsString(), t.get("id").getAsString() + ":"
                    + t.getAsJsonArray("albums").get(0).getAsJsonObject().get("id").getAsString());
        if (t.has("albums") && t.getAsJsonArray("albums").size() > 0)
            album = str(t.getAsJsonArray("albums").get(0).getAsJsonObject(), "title");
        long dur = t.has("durationMs") ? t.get("durationMs").getAsLong() : 0;
        String cover = t.has("coverUri") && !t.get("coverUri").isJsonNull()
                ? "https://" + t.get("coverUri").getAsString().replace("%%", "400x400") : null;
        return new Track(t.get("id").getAsString(), title, String.join(", ", artists), album, dur, cover, "yandex");
    }

    private final AudioCache cache = new AudioCache();
    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> inflight = new ConcurrentHashMap<>();
    /** Недокачанные куски: если загрузку пришлось прервать (пропустили трек), её можно продолжить с того же места. */
    private final ConcurrentHashMap<String, byte[]> partial = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, long[]> progress = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> failures = new ConcurrentHashMap<>();
    private volatile Set<String> desired = Set.of();
    private volatile String currentId;
    private volatile List<Track> prefetchList = List.of();
    private volatile boolean playLoading;                 // играющий трек ещё качается — предзагрузка ждёт
    private final Object prefetchLock = new Object();
    private Thread prefetchThread;

    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "musicplayer-net-wd"); t.setDaemon(true); return t; });

    @Override public CompletableFuture<Void> play(Track t) {
        int my = generation.incrementAndGet();
        player.stop();
        currentId = t.id();
        Set<String> d = new java.util.HashSet<>(desired);
        d.add(t.id());
        desired = d;
        playLoading = true;
        return async(() -> {
            try {
                byte[] data = audio(t.id()).join();
                dev.musicplayer.MusicPlayerMod.LOGGER.info("Track {}: {} bytes ready", t.id(), data.length);
                if (generation.get() != my) return null;
                player.play(data, 0, () -> {
                    Runnable r = finished;
                    if (generation.get() == my && r != null) r.run();
                });
                return null;
            } finally {
                if (generation.get() == my) { playLoading = false; wakePrefetch(); }
            }
        });
    }

    private CompletableFuture<byte[]> audio(String id) {
        byte[] hit = cache.get(id);
        if (hit != null) return CompletableFuture.completedFuture(hit);
        CompletableFuture<byte[]> f = new CompletableFuture<>();
        CompletableFuture<byte[]> existing = inflight.putIfAbsent(id, f);
        if (existing != null) return existing;
        EXEC.execute(() -> {
            try {
                byte[] data = downloadResumable(id);
                cache.put(id, data);
                failures.remove(id);
                f.complete(data);
            } catch (Throwable e) {
                f.completeExceptionally(e);
            } finally {
                inflight.remove(id);
                progress.remove(id);
            }
        });
        return f;
    }

    private boolean cancelled(String id) { return !id.equals(currentId) && !desired.contains(id); }

    /**
     * Скачивание, устойчивое к плохому интернету: потоково, с докачкой с места обрыва (Range), повтором при ошибках
     * и сторожем, который обрывает «зависшее» соединение, если 20 секунд не приходит ни байта.
     */
    private byte[] downloadResumable(String id) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] prev = partial.remove(id);
        if (prev != null) out.write(prev);
        long[] prog = {out.size(), -1};
        progress.put(id, prog);
        int stalls = 0;
        while (true) {
            if (cancelled(id)) { partial.put(id, out.toByteArray()); throw new java.util.concurrent.CancellationException("загрузка отменена"); }
            int before = out.size();
            try {
                String url = resolveStreamUrl(id);        // подпись короткоживущая — на каждую попытку берём новую
                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET();
                if (before > 0) rb.header("Range", "bytes=" + before + "-");
                HttpResponse<java.io.InputStream> r = HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
                int sc = r.statusCode();
                if (sc == 200 && before > 0) out.reset();                 // сервер не умеет докачку — начинаем сначала
                else if (sc != 200 && sc != 206) { r.body().close(); throw new IOException("HTTP " + sc); }
                long len = r.headers().firstValueAsLong("Content-Length").orElse(-1);
                long total = len < 0 ? -1 : (sc == 206 ? out.size() + len : len);
                prog[1] = total;
                java.io.InputStream in = r.body();
                long[] last = {System.nanoTime()};
                var wd = WATCHDOG.scheduleAtFixedRate(() -> {
                    if (System.nanoTime() - last[0] > 20_000_000_000L) { try { in.close(); } catch (IOException ignored) {} }
                }, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
                try (in) {
                    byte[] buf = new byte[32 * 1024];
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        out.write(buf, 0, n);
                        prog[0] = out.size();
                        last[0] = System.nanoTime();
                        if (out.size() > MAX_TRACK_BYTES) throw new IOException("Файл трека слишком большой");
                        if (cancelled(id)) { partial.put(id, out.toByteArray()); throw new java.util.concurrent.CancellationException("загрузка отменена"); }
                    }
                } finally { wd.cancel(false); }
                if (total < 0 || out.size() >= total) return out.toByteArray();
                throw new IOException("соединение оборвалось на " + out.size() + " из " + total + " байт");
            } catch (java.util.concurrent.CancellationException e) {
                throw e;
            } catch (IOException e) {
                // с прогрессом — это не «застревание», считаем заново; без прогресса — не больше 6 попыток подряд
                stalls = out.size() > before ? 0 : stalls + 1;
                if (stalls > 6) { partial.put(id, out.toByteArray()); throw e; }
                dev.musicplayer.MusicPlayerMod.LOGGER.warn("Download {} interrupted ({}), retry {}", id, e.getMessage(), stalls);
                Thread.sleep(Math.min(5000, 600L * (stalls + 1)));
            }
        }
    }

    public int loadPercent(String id) {
        long[] p = id == null ? null : progress.get(id);
        if (p == null || p[1] <= 0) return -1;
        return (int) Math.min(100, p[0] * 100 / p[1]);
    }

    public boolean isCached(String id) { return id != null && cache.has(id); }

    public long cacheMegabytes() { return cache.diskMegabytes(); }

    public void clearCache() { cache.clear(); }

    /**
     * Что скачивать заранее. current — играющий трек, upcoming — ближайшие по порядку воспроизведения, keep — что
     * держать в кэше (например, весь альбом при повторе альбома). Скачивание идёт по одному треку, и только когда
     * играющий уже загружен; лишнее (после перемотки очереди) обрывается, а скачанное сохраняется.
     */
    public void setPrefetch(String current, List<Track> upcoming, Set<String> keep) {
        Set<String> d = new java.util.HashSet<>();
        if (current != null) d.add(current);
        for (Track t : upcoming) if (t.id() != null) d.add(t.id());
        desired = d;
        currentId = current;
        Set<String> k = new java.util.HashSet<>(keep);
        k.addAll(d);
        cache.setKeep(k, current);
        partial.keySet().retainAll(k);
        prefetchList = List.copyOf(upcoming);
        wakePrefetch();
        synchronized (this) {
            if (prefetchThread == null) {
                prefetchThread = new Thread(this::prefetchLoop, "musicplayer-prefetch");
                prefetchThread.setDaemon(true);
                prefetchThread.start();
            }
        }
    }

    private void wakePrefetch() { synchronized (prefetchLock) { prefetchLock.notifyAll(); } }

    private void prefetchLoop() {
        while (true) {
            Track next = null;
            try {
                synchronized (prefetchLock) {
                    while (true) {
                        next = pickPrefetch();
                        if (next != null) break;
                        prefetchLock.wait(1500);
                    }
                }
                audio(next.id()).join();
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                Throwable c = e;
                while (c.getCause() != null) c = c.getCause();
                if (!(c instanceof java.util.concurrent.CancellationException) && next != null) {
                    failures.merge(next.id(), 1, Integer::sum);
                    try { Thread.sleep(3000); } catch (InterruptedException ie) { return; }
                }
            }
        }
    }

    private Track pickPrefetch() {
        if (!dev.musicplayer.config.PlayerConfig.get().prefetch || token == null || !verified || playLoading) return null;
        for (Track t : prefetchList) {
            if (t.id() == null || cache.has(t.id()) || inflight.containsKey(t.id())) continue;
            if (failures.getOrDefault(t.id(), 0) >= 3) continue;
            return t;
        }
        return null;
    }

    private String resolveStreamUrl(String trackId) throws Exception {
        JsonArray variants = getResultArray(API + "/tracks/" + trackId + "/download-info");
        JsonObject best = null;
        for (JsonElement e : variants) {
            JsonObject o = e.getAsJsonObject();
            if (!"mp3".equals(str(o, "codec"))) continue;
            if (best == null || o.get("bitrateInKbps").getAsInt() > best.get("bitrateInKbps").getAsInt()) best = o;
        }
        if (best == null) throw new IOException("Нет mp3-потока (трек недоступен или нужна подписка Плюс)");

        JsonObject x = send(base(best.get("downloadInfoUrl").getAsString() + "&format=json").GET().build());
        String host = x.get("host").getAsString(), path = x.get("path").getAsString();
        String ts = x.get("ts").getAsString(), s = x.get("s").getAsString();
        String sign = md5(SIGN_SALT + path.substring(1) + s);
        return "https://" + host + "/get-mp3/" + sign + "/" + ts + path;
    }

    @Override public void pause()  { player.pause(); }
    @Override public void resume() { player.resume(); }
    @Override public void seek(long ms) { player.seek(ms); }
    @Override public long positionMs() { return player.positionMs(); }
    @Override public boolean isPlaying() { return player.isPlaying(); }
    @Override public void setVolume(float v) { player.setVolume(v); }
    @Override public void onFinished(Runnable r) { this.finished = r; }

    public void onAudioError(java.util.function.Consumer<String> c) {
        player.onError(e -> {
            Throwable t = e;
            while (t.getCause() != null) t = t.getCause();
            String m = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            c.accept(m);
        });
    }

    private HttpRequest.Builder base(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("Authorization", "OAuth " + token)
                .header("X-Yandex-Music-Client", "YandexMusicAndroid/24023231")
                .header("User-Agent", "Yandex-Music-API");
    }

    private JsonObject send(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() == 401 || r.statusCode() == 403) throw new IOException("Токен недействителен или нет доступа (HTTP " + r.statusCode() + ")");
        if (r.statusCode() / 100 != 2) {
            String b = r.body() == null ? "" : r.body().replaceAll("\\s+", " ");
            throw new IOException("Ошибка Яндекс Музыки: HTTP " + r.statusCode() + (b.isEmpty() ? "" : " " + b.substring(0, Math.min(160, b.length()))));
        }
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private JsonObject getResult(String url) throws Exception { return send(base(url).GET().build()).getAsJsonObject("result"); }
    private JsonArray getResultArray(String url) throws Exception { return send(base(url).GET().build()).getAsJsonArray("result"); }

    private static <T> CompletableFuture<T> async(Callable<T> c) {
        return CompletableFuture.supplyAsync(() -> {
            try { return c.call(); } catch (Exception e) { throw new CompletionException(e); }
        }, EXEC);
    }

    private static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static String md5(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
