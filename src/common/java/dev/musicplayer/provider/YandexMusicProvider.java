package dev.musicplayer.provider;

import dev.musicplayer.util.T;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.musicplayer.audio.Mp3Player;
import dev.musicplayer.config.TokenStore;
import dev.musicplayer.lyrics.LrcParser;
import dev.musicplayer.model.LyricLine;
import dev.musicplayer.model.MusicEntity;
import dev.musicplayer.model.Page;
import dev.musicplayer.model.Platform;
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
 * Вход: по коду на ya.ru/device или, по-старому, ссылкой с токеном из адресной строки после входа на oauth.yandex.ru.
 */
public final class YandexMusicProvider implements MusicProvider {
    public static final String OAUTH_URL =
            "https://oauth.yandex.ru/authorize?response_type=token&client_id=" + YandexMusicProvider.CLIENT_ID;
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

    @Override public Platform platform() { return Platform.YANDEX; }
    @Override public boolean isLoggedIn() { return token != null && verified; }

    @Override public String accountName() { return accountName; }

    @Override public boolean supportsLikes() { return true; }

    @Override public boolean isLiked(String trackId) { return trackId != null && likedIds.contains(trackId); }

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

    @Override public CompletableFuture<Void> setLiked(Track t, boolean like) {
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

    public CompletableFuture<Void> login() {
        Browser.open(OAUTH_URL);
        loginFuture = new CompletableFuture<>();
        return loginFuture;
    }

    public CompletableFuture<Void> submitToken(String input) {
        String t = extractToken(input);
        if (t.isEmpty()) return CompletableFuture.failedFuture(new IOException(T.get("err.empty_token")));
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

    /** Код для страницы ya.ru/device и до какого момента он действует. */
    public record DeviceCode(String userCode, String url, long expiresAt) {}

    private static final String CLIENT_ID = "23cabbbdc6cd418abb4b39c32c41195d";
    /** Секрет официального Android-приложения Яндекс Музыки, им пользуются все сторонние клиенты. */
    private static final String CLIENT_SECRET = "53bc75238f0c4d08a118e51fe9203300";
    private final AtomicInteger codeLogin = new AtomicInteger();

    /**
     * Вход по коду (OAuth device flow): игра показывает короткий код, человек вводит его на ya.ru/device и
     * нажимает «Разрешить», а токен игра забирает сама. Ничего не надо выковыривать из адресной строки, поэтому
     * не мешает и переадресация на music.yandex.kz, из-за которой токен в ссылке терялся.
     */
    public CompletableFuture<Void> loginWithCode(java.util.function.Consumer<DeviceCode> onCode) {
        int my = codeLogin.incrementAndGet();
        return async(() -> {
            String deviceId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            JsonObject c = oauth("/device/code", "client_id=" + CLIENT_ID + "&device_id=" + deviceId + "&device_name=" + enc("Minecraft (Overtone)"));
            String deviceCode = str(c, "device_code"), userCode = str(c, "user_code");
            if (deviceCode.isEmpty() || userCode.isEmpty()) throw new IOException(T.get("err.code_failed"));
            long expires = System.currentTimeMillis() + c.get("expires_in").getAsLong() * 1000;
            long interval = Math.max(2, c.has("interval") ? c.get("interval").getAsLong() : 5) * 1000;
            String url = str(c, "verification_url").isEmpty() ? "https://ya.ru/device" : str(c, "verification_url");
            onCode.accept(new DeviceCode(userCode, url, expires));

            String body = "grant_type=device_code&code=" + enc(deviceCode) + "&client_id=" + CLIENT_ID + "&client_secret=" + CLIENT_SECRET;
            while (System.currentTimeMillis() < expires) {
                Thread.sleep(interval);
                if (codeLogin.get() != my) throw new java.util.concurrent.CancellationException();
                JsonObject r = oauth("/token", body);
                String accessToken = str(r, "access_token");
                if (!accessToken.isEmpty()) {
                    submitToken(accessToken).join();
                    return null;
                }
                switch (str(r, "error")) {
                    case "authorization_pending" -> { }
                    case "slow_down" -> interval += 5000;
                    case "access_denied" -> throw new IOException(T.get("err.code_denied"));
                    case "expired_token" -> throw new IOException(T.get("err.code_expired"));
                    default -> throw new IOException(str(r, "error_description").isEmpty() ? str(r, "error") : str(r, "error_description"));
                }
            }
            throw new IOException(T.get("err.code_expired"));
        });
    }

    public void cancelCodeLogin() { codeLogin.incrementAndGet(); }

    /** Ответ OAuth-сервера; ошибки он отдаёт с кодом 400 и телом JSON, их разбирает вызывающий. */
    private static JsonObject oauth(String path, String form) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://oauth.yandex.ru" + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            return JsonParser.parseString(r.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("oauth.yandex.ru: HTTP " + r.statusCode());
        }
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

    public record SearchAll(List<Track> tracks, List<MusicEntity> artists, List<MusicEntity> albums, List<MusicEntity> playlists) {}

    /** Один запрос на все вкладки поиска: треки, артисты, альбомы, плейлисты. */
    public CompletableFuture<SearchAll> searchAll(String query) {
        return async(() -> {
            JsonObject r = getResult(API + "/search?text=" + enc(query) + "&nocorrect=false&type=all&page=0");
            List<Track> tracks = new ArrayList<>();
            for (JsonObject o : results(r, "tracks")) tracks.add(parseTrack(o));
            List<MusicEntity> artists = new ArrayList<>(), albums = new ArrayList<>();
            List<MusicEntity> playlists = new ArrayList<>(List.of(MusicEntity.MINE));
            for (JsonObject o : results(r, "artists")) artists.add(artistOf(o));
            for (JsonObject o : results(r, "albums")) albums.add(albumOf(o));
            for (JsonObject o : results(r, "playlists")) {
                MusicEntity p = playlistOf(o);
                if (p != null) playlists.add(p);
            }
            return new SearchAll(tracks, artists, albums, playlists);
        });
    }

    private static List<JsonObject> results(JsonObject r, String key) {
        List<JsonObject> out = new ArrayList<>();
        if (!r.has(key) || !r.get(key).isJsonObject()) return out;
        JsonObject block = r.getAsJsonObject(key);
        if (!block.has("results") || !block.get("results").isJsonArray()) return out;
        for (JsonElement e : block.getAsJsonArray("results")) if (e.isJsonObject()) out.add(e.getAsJsonObject());
        return out;
    }

    private static String imageUrl(JsonObject o) {
        String uri = "";
        if (o.has("coverUri") && !o.get("coverUri").isJsonNull()) uri = o.get("coverUri").getAsString();
        else if (o.has("cover") && o.get("cover").isJsonObject()) uri = str(o.getAsJsonObject("cover"), "uri");
        else if (o.has("ogImage") && !o.get("ogImage").isJsonNull()) uri = o.get("ogImage").getAsString();
        return uri.isEmpty() ? null : "https://" + uri.replace("%%", "200x200");
    }

    private static String artistNames(JsonObject o) {
        List<String> names = new ArrayList<>();
        if (o.has("artists") && o.get("artists").isJsonArray())
            for (JsonElement a : o.getAsJsonArray("artists")) if (a.isJsonObject()) names.add(str(a.getAsJsonObject(), "name"));
        return String.join(", ", names);
    }

    private static MusicEntity artistOf(JsonObject o) {
        return new MusicEntity(MusicEntity.Kind.ARTIST, str(o, "id"), str(o, "name"), T.get("entity.artist"), imageUrl(o));
    }

    private static MusicEntity albumOf(JsonObject o) {
        String by = artistNames(o), year = str(o, "year");
        String sub = T.get("entity.album") + (by.isEmpty() ? "" : " · " + by) + (year.isEmpty() ? "" : " · " + year);
        String title = str(o, "title");
        String version = str(o, "version");
        return new MusicEntity(MusicEntity.Kind.ALBUM, str(o, "id"), version.isEmpty() ? title : title + " (" + version + ")", sub, imageUrl(o));
    }

    private static MusicEntity playlistOf(JsonObject o) {
        String uid = str(o, "uid"), kind = str(o, "kind");
        if (uid.isEmpty() && o.has("owner") && o.get("owner").isJsonObject()) uid = str(o.getAsJsonObject("owner"), "uid");
        if (uid.isEmpty() || kind.isEmpty()) return null;
        String owner = o.has("owner") && o.get("owner").isJsonObject() ? str(o.getAsJsonObject("owner"), "name") : "";
        if (owner.isEmpty() && o.has("owner") && o.get("owner").isJsonObject()) owner = str(o.getAsJsonObject("owner"), "login");
        String sub = T.get("entity.playlist") + (owner.isEmpty() ? "" : " · " + owner)
                + (o.has("trackCount") ? " · " + T.tracks(o.get("trackCount").getAsInt()) : "");
        return new MusicEntity(MusicEntity.Kind.PLAYLIST, uid + ":" + kind, str(o, "title"), sub, imageUrl(o));
    }

    /** Открывает артиста, альбом или плейлист: что внутри, зависит от вида. Неофициальный API, поэтому разбор терпимый к пропущенным полям. */
    public CompletableFuture<Page> open(MusicEntity e) {
        return async(() -> switch (e.kind()) {
            case ARTIST -> openArtist(e);
            case ALBUM -> openAlbum(e);
            case PLAYLIST -> e.isMine() ? openMyPlaylists(e) : openPlaylist(e);
            case STATION -> throw new IllegalArgumentException("a station is started as a wave, not opened");
        });
    }

    private Page openArtist(MusicEntity e) throws Exception {
        List<Track> tracks = new ArrayList<>();
        List<MusicEntity> albums = new ArrayList<>();
        JsonObject brief = getResult(API + "/artists/" + e.id() + "/brief-info");
        if (brief.has("popularTracks") && brief.get("popularTracks").isJsonArray()) tracks.addAll(parseTracks(brief.getAsJsonArray("popularTracks")));
        if (tracks.isEmpty()) {
            JsonObject t = getResult(API + "/artists/" + e.id() + "/tracks?page=0&page-size=50");
            if (t.has("tracks") && t.get("tracks").isJsonArray()) tracks.addAll(parseTracks(t.getAsJsonArray("tracks")));
        }
        JsonArray raw = null;
        try {
            JsonObject a = getResult(API + "/artists/" + e.id() + "/direct-albums?page=0&page-size=50&sort-by=year");
            if (a.has("albums") && a.get("albums").isJsonArray()) raw = a.getAsJsonArray("albums");
        } catch (IOException ignored) {
            // запасной вариант ниже
        }
        if ((raw == null || raw.isEmpty()) && brief.has("albums") && brief.get("albums").isJsonArray()) raw = brief.getAsJsonArray("albums");
        if (raw != null) for (JsonElement a : raw) if (a.isJsonObject()) albums.add(albumOf(a.getAsJsonObject()));
        return new Page(e, tracks, albums);
    }

    private Page openAlbum(MusicEntity e) throws Exception {
        JsonObject r = getResult(API + "/albums/" + e.id() + "/with-tracks");
        List<Track> tracks = new ArrayList<>();
        if (r.has("volumes") && r.get("volumes").isJsonArray())
            for (JsonElement vol : r.getAsJsonArray("volumes")) if (vol.isJsonArray()) tracks.addAll(parseTracks(vol.getAsJsonArray()));
        return new Page(e, tracks, List.of());
    }

    private Page openPlaylist(MusicEntity e) throws Exception {
        int colon = e.id().indexOf(':');
        JsonObject r = getResult(API + "/users/" + e.id().substring(0, colon) + "/playlists/" + e.id().substring(colon + 1) + "?rich-tracks=true");
        List<Track> tracks = new ArrayList<>();
        List<String> bare = new ArrayList<>();
        if (r.has("tracks") && r.get("tracks").isJsonArray()) {
            for (JsonElement el : r.getAsJsonArray("tracks")) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                if (o.has("track") && o.get("track").isJsonObject()) { try { tracks.add(parseTrack(o.getAsJsonObject("track"))); } catch (RuntimeException ignored) { } }
                else if (o.has("id")) bare.add(str(o, "albumId").isEmpty() ? str(o, "id") : str(o, "id") + ":" + str(o, "albumId"));
            }
        }
        if (tracks.isEmpty() && !bare.isEmpty()) tracks.addAll(fetchTracks(bare));
        return new Page(e, tracks, List.of());
    }

    private Page openMyPlaylists(MusicEntity e) throws Exception {
        JsonArray arr = getResultArray(API + "/users/" + uid + "/playlists/list");
        List<MusicEntity> out = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            MusicEntity p = playlistOf(el.getAsJsonObject());
            if (p != null) out.add(p);
        }
        return new Page(e, List.of(), out);
    }

    /** /tracks отдаёт треки вперемешку, порядок восстанавливаем по ids. */
    private List<Track> fetchTracks(List<String> ids) throws Exception {
        java.util.Map<String, Track> byId = new java.util.HashMap<>();
        for (int i = 0; i < ids.size(); i += 200) {
            List<String> chunk = ids.subList(i, Math.min(ids.size(), i + 200));
            HttpRequest req = base(API + "/tracks")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("track-ids=" + enc(String.join(",", chunk))))
                    .build();
            for (Track t : parseTracks(send(req).getAsJsonArray("result"))) byId.putIfAbsent(t.id(), t);
        }
        List<Track> out = new ArrayList<>();
        for (String id : ids) {
            int colon = id.indexOf(':');
            Track t = byId.remove(colon < 0 ? id : id.substring(0, colon));
            if (t != null) out.add(t);
        }
        return out;
    }

    private static List<Track> parseTracks(JsonArray arr) {
        List<Track> out = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject() || !el.getAsJsonObject().has("id")) continue;
            try { out.add(parseTrack(el.getAsJsonObject())); } catch (RuntimeException ignored) { }
        }
        return out;
    }

    @Override public CompletableFuture<List<Track>> likedTracks() {
        return async(() -> {
            JsonObject r = getResult(API + "/users/" + uid + "/likes/tracks");
            JsonArray arr = r.getAsJsonObject("library").getAsJsonArray("tracks");
            List<JsonObject> liked = new ArrayList<>();
            for (JsonElement e : arr) if (e.isJsonObject() && e.getAsJsonObject().has("id")) liked.add(e.getAsJsonObject());
            boolean stamped = !liked.isEmpty() && liked.stream().allMatch(o -> !str(o, "timestamp").isEmpty());
            if (stamped) liked.sort((a, b) -> str(b, "timestamp").compareTo(str(a, "timestamp")));
            List<String> ids = new ArrayList<>();
            for (JsonObject o : liked) {
                String id = str(o, "id"), album = str(o, "albumId");
                ids.add(album.isEmpty() ? id : id + ":" + album);
            }
            return fetchTracks(ids);
        });
    }

    /**
     * Текст трека от самой Яндекс Музыки: synced=true — LRC с таймкодами, false — обычный текст.
     * Пустой список, если текста нет или запрос не удался (тогда вызывающий пробует другой источник).
     */
    @Override public CompletableFuture<List<LyricLine>> lyrics(Track t, boolean synced) {
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

    public static final String WAVE_SEED = "user:onyourwave";
    /** id трека → «id:albumId»: волне нужен именно такой формат в параметре queue. */
    private static final java.util.Map<String, String> FULL_ID = new ConcurrentHashMap<>();

    public CompletableFuture<WaveBatch> waveStart(String seed) {
        return async(() -> newWaveSession(seed, List.of()));
    }

    /** Станции волны: жанры, настроения, занятия, эпохи. Seed станции — «тип:тег». */
    public CompletableFuture<List<MusicEntity>> waveStations() {
        String lang = java.util.Locale.getDefault().getLanguage().equals("ru") ? "ru" : "en";
        return async(() -> {
            List<MusicEntity> out = new ArrayList<>();
            for (JsonElement el : getResultArray(API + "/rotor/stations/list?language=" + lang)) {
                JsonObject st = el.getAsJsonObject().has("station") ? el.getAsJsonObject().getAsJsonObject("station") : null;
                if (st == null || !st.has("id") || !st.get("id").isJsonObject()) continue;
                JsonObject id = st.getAsJsonObject("id");
                String type = str(id, "type"), tag = str(id, "tag"), name = str(st, "name");
                if (type.isEmpty() || tag.isEmpty() || name.isEmpty()) continue;
                out.add(new MusicEntity(MusicEntity.Kind.STATION, type + ":" + tag, name, stationType(type), null));
            }
            return out;
        });
    }

    private static String stationType(String type) {
        return switch (type) {
            case "genre" -> T.get("station.genre");
            case "mood" -> T.get("station.mood");
            case "activity" -> T.get("station.activity");
            case "epoch" -> T.get("station.epoch");
            case "user" -> T.get("station.user");
            default -> T.get("station.other");
        };
    }

    private WaveBatch newWaveSession(String seed, List<String> played) throws Exception {
        if (!played.isEmpty()) {
            try {
                return openSession(seed, queueOf(played));
            } catch (IOException e) {
                dev.musicplayer.MusicPlayerMod.LOGGER.warn("Wave session with history failed: {}", e.getMessage());
            }
        }
        return openSession(seed, null);
    }

    private WaveBatch openSession(String seed, JsonArray queue) throws Exception {
        JsonObject body = new JsonObject();
        JsonArray seeds = new JsonArray();
        seeds.add(seed);
        body.add("seeds", seeds);
        body.addProperty("includeTracksInResponse", true);
        if (queue != null) body.add("queue", queue);
        JsonObject r = postJson(API + "/rotor/session/new", body).getAsJsonObject("result");
        String sid = str(r, "radioSessionId");
        if (sid.isEmpty()) throw new IOException(T.get("err.wave_session"));
        return readBatch(sid, r);
    }

    /** played — id уже выданных сессии треков, старые первыми: по ним сервер понимает, что повторять нельзя. */
    public CompletableFuture<WaveBatch> waveMore(String sessionId, String seed, List<String> played) {
        return async(() -> {
            try {
                JsonObject body = new JsonObject();
                body.add("queue", queueOf(played));
                JsonObject r = postJson(API + "/rotor/session/" + sessionId + "/tracks", body).getAsJsonObject("result");
                WaveBatch b = readBatch(sessionId, r);
                if (!b.tracks().isEmpty()) return b;
            } catch (IOException e) {
                dev.musicplayer.MusicPlayerMod.LOGGER.warn("Wave /tracks failed, starting a fresh session: {}", e.getMessage());
            }
            return newWaveSession(seed, played);
        });
    }

    private static JsonArray queueOf(List<String> ids) {
        JsonArray queue = new JsonArray();
        for (String id : ids) queue.add(FULL_ID.getOrDefault(id, id));
        return queue;
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
            if (trackId != null) ev.addProperty("trackId", FULL_ID.getOrDefault(trackId, trackId));
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
            if (cancelled(id)) { partial.put(id, out.toByteArray()); throw new java.util.concurrent.CancellationException("cancelled"); }
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
                        if (out.size() > MAX_TRACK_BYTES) throw new IOException(T.get("err.track_too_big"));
                        if (cancelled(id)) { partial.put(id, out.toByteArray()); throw new java.util.concurrent.CancellationException("cancelled"); }
                    }
                } finally { wd.cancel(false); }
                if (total < 0 || out.size() >= total) return out.toByteArray();
                throw new IOException(T.get("err.connection_cut", out.size(), total));
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

    @Override public int loadPercent(String id) {
        long[] p = id == null ? null : progress.get(id);
        if (p == null || p[1] <= 0) return -1;
        return (int) Math.min(100, p[0] * 100 / p[1]);
    }

    @Override public boolean isCached(String id) { return id != null && cache.has(id); }

    private volatile long cacheMb;
    private volatile long cacheMbAt;

    /** Вызывается из окна настроек каждый кадр, поэтому папку кэша читаем не чаще раза в 3 с и не в потоке отрисовки. */
    public long cacheMegabytes() {
        long now = System.currentTimeMillis();
        if (now - cacheMbAt > 3000) {
            cacheMbAt = now;
            EXEC.execute(() -> cacheMb = cache.diskMegabytes());
        }
        return cacheMb;
    }

    public void clearCache() {
        cache.clear();
        cacheMb = 0;
    }

    /**
     * Что скачивать заранее. current — играющий трек, upcoming — ближайшие по порядку воспроизведения, keep — что
     * держать в кэше (например, весь альбом при повторе альбома). Скачивание идёт по одному треку, и только когда
     * играющий уже загружен; лишнее (после перемотки очереди) обрывается, а скачанное сохраняется.
     */
    @Override public void setPrefetch(String current, List<Track> upcoming, Set<String> keep) {
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
        if (best == null) throw new IOException(T.get("err.no_mp3"));

        JsonObject x = send(base(best.get("downloadInfoUrl").getAsString() + "&format=json").GET().build());
        String host = x.get("host").getAsString(), path = x.get("path").getAsString();
        String ts = x.get("ts").getAsString(), s = x.get("s").getAsString();
        String sign = md5(SIGN_SALT + path.substring(1) + s);
        return "https://" + host + "/get-mp3/" + sign + "/" + ts + path;
    }

    @Override public void stop() {
        generation.incrementAndGet();
        playLoading = false;
        player.stop();
    }

    @Override public void pause()  { player.pause(); }
    @Override public void resume() { player.resume(); }
    @Override public void seek(long ms) { player.seek(ms); }
    @Override public long positionMs() { return player.positionMs(); }
    @Override public boolean isPlaying() { return player.isPlaying(); }
    @Override public void setVolume(float v) { player.setVolume(v); }
    @Override public void onFinished(Runnable r) { this.finished = r; }

    @Override public void onAudioError(java.util.function.Consumer<String> c) {
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
        if (r.statusCode() == 401 || r.statusCode() == 403) throw new IOException(T.get("err.token_invalid", r.statusCode()));
        if (r.statusCode() / 100 != 2) {
            String b = r.body() == null ? "" : r.body().replaceAll("\\s+", " ");
            throw new IOException(T.get("err.yandex_http", r.statusCode()) + (b.isEmpty() ? "" : " " + b.substring(0, Math.min(160, b.length()))));
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
