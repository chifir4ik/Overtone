package dev.musicplayer.provider;

import dev.musicplayer.util.T;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.musicplayer.MusicPlayerMod;
import dev.musicplayer.audio.AacPlayer;
import dev.musicplayer.audio.AudioEngine;
import dev.musicplayer.audio.Mp3Player;
import dev.musicplayer.audio.Mp4Audio;
import dev.musicplayer.config.TokenStore;
import dev.musicplayer.model.Platform;
import dev.musicplayer.model.Track;
import dev.musicplayer.net.SafeHttp;
import dev.musicplayer.util.TrackNames;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.musicplayer.util.Json.obj;
import static dev.musicplayer.util.Json.str;

/**
 * SoundCloud через api-v2, которым пользуется сам сайт. Официальной регистрации приложений больше нет, поэтому
 * client_id берётся со страницы soundcloud.com. Аккаунт привязывается ссылкой на профиль (видны лайки) или
 * oauth_token (можно ещё и ставить лайки); поиск и прослушивание работают без входа.
 */
public final class SoundCloudProvider implements MusicProvider {
    private static final String API = "https://api-v2.soundcloud.com";
    private static final String CLIENT_ID_KEY = "soundcloudClientId2";
    private static final long MAX_TRACK_BYTES = 150L << 20;
    private static final Pattern SCRIPT = Pattern.compile("<script[^>]+src=\"(https://[^\"]+\\.js)\"");
    private static final Pattern CLIENT_ID = Pattern.compile("client_id\\s*:\\s*\"([0-9a-zA-Z]{32})\"");
    private static final Pattern TOKEN = Pattern.compile("\\b\\d-\\d+-\\d+-[0-9A-Za-z]+\\b");
    /** Сейчас сайт выдаёт oauth_token в виде JWT, а прежние токены имели вид «2-123-456-abc». */
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}");
    private static final Pattern PROFILE = Pattern.compile("[A-Za-z0-9_-]{2,60}");

    private static final SafeHttp HTTP = new SafeHttp(Set.of("soundcloud.com", "sndcdn.com", "soundcloud.cloud"));

    private final Mp3Player mp3 = new Mp3Player();
    private final AacPlayer aac = new AacPlayer();
    private final AudioCache cache = AudioCache.shared();
    private final AtomicInteger generation = new AtomicInteger();
    private final Set<String> likedIds = ConcurrentHashMap.newKeySet();
    private final Map<String, long[]> progress = new ConcurrentHashMap<>();
    private volatile AudioEngine engine = mp3;
    private volatile String clientId = TokenStore.get(CLIENT_ID_KEY);
    private volatile String token = TokenStore.get("soundcloudToken");
    private volatile String userId = TokenStore.get("soundcloudUserId");
    private volatile String userName = orEmpty(TokenStore.get("soundcloudName"));
    private volatile Runnable finished;

    @Override public Platform platform() { return Platform.SOUNDCLOUD; }
    @Override public boolean isLoggedIn() { return true; }
    @Override public String accountName() { return isLinked() ? userName : ""; }
    @Override public String likedLabel() { return T.get("liked.likes"); }

    public boolean isLinked() { return userId != null; }

    /** Ставить лайки можно только с oauth_token; по ссылке на профиль лайки доступны для просмотра. */
    public boolean canLike() { return token != null && userId != null && !tokenExpired(); }

    private record Jwt(String clientId, String userId, long expiresAtMs) {}

    private volatile String jwtFor;
    private volatile Jwt jwtInfo;

    /** Что известно из самого токена; null — это не JWT. Подпись не проверяется: она нужна серверу, а не нам. */
    private Jwt jwt() {
        String t = token;
        if (t == null || !JWT.matcher(t).matches()) return null;
        if (!t.equals(jwtFor)) {
            try {
                String payload = new String(Base64.getUrlDecoder().decode(t.split("\\.")[1]), StandardCharsets.UTF_8);
                JsonObject o = JsonParser.parseString(payload).getAsJsonObject();
                String sub = str(o, "sub");
                String id = sub.startsWith("soundcloud:users:") ? sub.substring("soundcloud:users:".length()) : null;
                long exp = o.has("exp") ? o.get("exp").getAsLong() * 1000 : 0;
                jwtInfo = new Jwt(str(o, "client_id"), id, exp);
            } catch (RuntimeException e) {
                jwtInfo = null;
            }
            jwtFor = t;
        }
        return jwtInfo;
    }

    /** JWT с сайта живёт сутки, потом его надо скопировать заново. */
    public boolean tokenExpired() {
        Jwt j = jwt();
        return j != null && j.expiresAtMs > 0 && j.expiresAtMs < System.currentTimeMillis();
    }

    @Override public boolean supportsLikes() { return canLike(); }
    @Override public boolean isLiked(String trackId) { return likedIds.contains(trackId); }

    /** Принимает ссылку на профиль, имя пользователя или oauth_token (значение, кука или заголовок целиком). */
    public CompletableFuture<Void> link(String input) {
        String text = input == null ? "" : input.replaceAll("[\\p{Cf}\\r\\n]", "").trim();
        if (text.contains("%")) text = URLDecoder.decode(text, StandardCharsets.UTF_8);
        if (text.isEmpty()) return CompletableFuture.failedFuture(new IOException(T.get("err.empty_field")));
        return looksLikeToken(text) ? linkToken(extractToken(text)) : linkProfile(text);
    }

    private static boolean looksLikeToken(String s) {
        if (JWT.matcher(s).find() || TOKEN.matcher(s).find()) return true;
        if (s.contains("oauth_token") || s.regionMatches(true, 0, "OAuth ", 0, 6) || s.regionMatches(true, 0, "Bearer ", 0, 7)) return true;
        // длинная строка без пробелов и слэшей — это не ссылка и не имя, а токен в неизвестном виде
        return s.length() >= 40 && s.chars().noneMatch(c -> c == '/' || c == ' ' || c == '.' && s.contains("soundcloud"));
    }

    private CompletableFuture<Void> linkProfile(String text) {
        return Background.run(() -> {
            String name = profileName(expandShortLink(text));
            if (!PROFILE.matcher(name).matches()) throw new IOException(T.get("err.not_profile"));
            JsonObject user = api("GET", "/resolve?url=" + enc("https://soundcloud.com/" + name));
            if (!"user".equals(str(user, "kind"))) throw new IOException(T.get("err.no_profile"));
            store(String.valueOf(user.get("id").getAsLong()), str(user, "username"), token);
            return null;
        });
    }

    private CompletableFuture<Void> linkToken(String t) {
        String previous = token;
        token = t;
        return Background.run(() -> {
            try {
                if (tokenExpired()) throw new IOException(T.get("err.token_expired"));
                JsonObject me = apiAuth("GET", "/me");
                store(String.valueOf(me.get("id").getAsLong()), str(me, "username"), t);
            } catch (Exception e) {
                token = previous;
                throw e instanceof IOException io && io.getMessage() != null && io.getMessage().equals(T.get("err.token_expired"))
                        ? io : new IOException(T.get("err.token_rejected", e.getMessage()));
            }
            refreshLikes();
            return null;
        });
    }

    private void store(String id, String name, String oauth) {
        userId = id;
        userName = name;
        token = oauth;
        TokenStore.set("soundcloudUserId", id);
        TokenStore.set("soundcloudName", name);
        TokenStore.set("soundcloudToken", oauth);
    }

    @Override public void logout() {
        userId = null; userName = ""; token = null;
        likedIds.clear();
        TokenStore.set("soundcloudUserId", null);
        TokenStore.set("soundcloudName", null);
        TokenStore.set("soundcloudToken", null);
    }

    private void refreshLikes() {
        try {
            JsonObject r = apiAuth("GET", "/me/track_likes/ids?limit=5000");
            Set<String> ids = new HashSet<>();
            for (JsonElement e : r.getAsJsonArray("collection"))
                ids.add(e.isJsonObject() ? String.valueOf(e.getAsJsonObject().get("id").getAsLong()) : e.getAsString());
            likedIds.clear();
            likedIds.addAll(ids);
        } catch (Exception e) {
            MusicPlayerMod.LOGGER.debug("SoundCloud likes list unavailable: {}", e.getMessage());
        }
    }

    @Override public CompletableFuture<List<Track>> search(String query) {
        return Background.run(() -> tracks(api("GET", "/search/tracks?q=" + enc(query) + "&limit=40")));
    }

    @Override public CompletableFuture<List<Track>> likedTracks() {
        return Background.run(() -> {
            if (!isLinked()) throw new IOException(T.get("err.likes_need_login"));
            List<Track> list = tracks(api("GET", "/users/" + userId + "/track_likes?limit=200"));
            if (canLike()) for (Track t : list) likedIds.add(t.id());
            return list;
        });
    }

    @Override public CompletableFuture<Void> setLiked(Track t, boolean like) {
        return Background.run(() -> {
            if (tokenExpired()) throw new IOException(T.get("err.token_expired"));
            if (!canLike()) throw new IOException(T.get("err.likes_need_token"));
            apiAuth(like ? "PUT" : "DELETE", "/users/" + userId + "/track_likes/" + t.id());
            if (like) likedIds.add(t.id()); else likedIds.remove(t.id());
            return null;
        });
    }

    private static List<Track> tracks(JsonObject page) {
        List<Track> out = new ArrayList<>();
        if (!page.has("collection") || !page.get("collection").isJsonArray()) return out;
        for (JsonElement e : page.getAsJsonArray("collection")) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            if (o.has("track") && o.get("track").isJsonObject()) o = o.getAsJsonObject("track");
            if (!o.has("id") || str(o, "title").isBlank() || "BLOCK".equals(str(o, "policy"))) continue;
            if ("SNIP".equals(str(o, "policy"))) PREVIEW_ONLY.add(String.valueOf(o.get("id").getAsLong()));
            out.add(parseTrack(o));
        }
        return out;
    }

    private static Track parseTrack(JsonObject o) {
        JsonObject user = obj(o, "user");
        String title = str(o, "title").trim();
        String artist = str(obj(o, "publisher_metadata"), "artist").trim();
        if (artist.isEmpty()) {
            TrackNames.Parsed p = TrackNames.split(title, str(user, "username"));
            artist = p.artist();
            title = p.title();
        } else {
            String prefix = artist + " - ";
            if (title.regionMatches(true, 0, prefix, 0, prefix.length())) title = title.substring(prefix.length()).trim();
        }
        String art = str(o, "artwork_url");
        if (art.isEmpty()) art = str(user, "avatar_url");
        String cover = art.isEmpty() ? null : art.replace("-large.", "-t500x500.");
        long duration = o.has("duration") && !o.get("duration").isJsonNull() ? o.get("duration").getAsLong() : 0;
        return new Track(String.valueOf(o.get("id").getAsLong()), title, artist, null, duration, cover, Platform.SOUNDCLOUD.id);
    }

    @Override public CompletableFuture<Void> play(Track t) {
        int my = generation.incrementAndGet();
        engine.stop();
        return Background.run(() -> {
            byte[] data = audio(t.id(), my);
            if (generation.get() != my) return null;
            AudioEngine e = Mp4Audio.looksLikeMp4(data) ? aac : mp3;
            engine = e;
            e.play(data, 0, () -> {
                Runnable r = finished;
                if (generation.get() == my && r != null) r.run();
            });
            return null;
        });
    }

    private static final java.util.Set<String> PREVIEW_ONLY = ConcurrentHashMap.newKeySet();

    /** SoundCloud отдаёт этот трек только 30-секундным фрагментом: полная версия для подписчиков Go+. */
    public static boolean isPreviewOnly(String id) { return id != null && PREVIEW_ONLY.contains(id); }

    private byte[] audio(String id, int gen) throws Exception {
        String key = "sc_" + id;
        byte[] hit = cache.get(key);
        if (hit != null) return hit;
        progress.put(id, new long[]{0, -1});
        try {
            byte[] data = download(id, gen);
            cache.put(key, data);
            return data;
        } finally {
            progress.remove(id);
        }
    }

    /**
     * У SoundCloud бывает так, что прогрессивный поток в списке есть, а по ссылке отдаёт 404. Поэтому варианты
     * перебираются от самого простого к запасным, и ошибкой считается только провал всех.
     */
    private byte[] download(String id, int gen) throws Exception {
        JsonObject info = api("GET", "/tracks/" + id);
        List<JsonObject> variants = variants(info);
        if (variants.isEmpty()) {
            MusicPlayerMod.LOGGER.warn("SoundCloud track {} has no usable stream; transcodings: {}", id, describe(info));
            throw new IOException(T.get("err.sc_no_stream"));
        }

        IOException failure = null;
        for (int round = 0; round < 2; round++) {
            if (round == 1) refreshClientId();
            for (JsonObject v : variants) {
                try {
                    byte[] data = fetchVariant(v, id, gen);
                    if (score(v) >= 10) PREVIEW_ONLY.add(id);
                    return data;
                } catch (CancellationException e) {
                    throw e;
                } catch (IOException | RuntimeException e) {
                    failure = e instanceof IOException io ? io : new IOException(e.toString());
                    MusicPlayerMod.LOGGER.warn("SoundCloud stream {} {} failed: {}",
                            str(v, "preset"), str(obj(v, "format"), "protocol"), e.getMessage());
                }
            }
        }
        throw new IOException(T.get("err.stream_unavailable", failure.getMessage()));
    }

    private static String describe(JsonObject info) {
        StringBuilder sb = new StringBuilder();
        JsonObject media = obj(info, "media");
        if (media.has("transcodings"))
            for (JsonElement e : media.getAsJsonArray("transcodings"))
                sb.append(str(e.getAsJsonObject(), "preset")).append('/').append(str(obj(e.getAsJsonObject(), "format"), "protocol")).append(' ');
        return sb.length() == 0 ? "none" : sb.toString().trim();
    }

    private JsonObject resolveStream(String transcodingUrl) throws Exception {
        String base = transcodingUrl + (transcodingUrl.contains("?") ? "&" : "?") + "client_id=";
        if (token != null) {
            Jwt j = jwt();
            try {
                return HTTP.getJson(base + (j != null && !j.clientId().isEmpty() ? j.clientId() : clientId()), headers(true));
            } catch (IOException e) {
                if (e.getMessage() == null || !e.getMessage().contains("HTTP 40")) throw e;
            }
        }
        return HTTP.getJson(base + clientId(), headers(false));
    }

    private byte[] fetchVariant(JsonObject variant, String id, int gen) throws Exception {
        String stream = str(resolveStream(variant.get("url").getAsString()), "url");
        if (stream.isEmpty()) throw new IOException(T.get("err.empty_stream"));
        return "hls".equals(str(obj(variant, "format"), "protocol")) ? downloadHls(stream, id, gen) : downloadFile(stream, id, gen);
    }

    /** Подходят mp3 и AAC. Сначала проще и быстрее (прогрессивный файл), потом HLS; обрезанные превью в самом конце. */
    private static List<JsonObject> variants(JsonObject info) {
        JsonObject media = obj(info, "media");
        JsonArray list = media.has("transcodings") ? media.getAsJsonArray("transcodings") : new JsonArray();
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : list) {
            JsonObject t = e.getAsJsonObject();
            if (score(t) >= 0) out.add(t);
        }
        out.sort(Comparator.comparingInt(SoundCloudProvider::score));
        return out;
    }

    private static int score(JsonObject t) {
        JsonObject f = obj(t, "format");
        String mime = str(f, "mime_type"), protocol = str(f, "protocol");
        boolean progressive = protocol.equals("progressive"), hls = protocol.equals("hls");
        boolean mp3 = mime.startsWith("audio/mpeg"), aacCodec = mime.startsWith("audio/mp4") && mime.contains("mp4a");
        int base;
        if (mp3 && progressive) base = 0;
        else if (mp3 && hls) base = 1;
        else if (aacCodec && progressive) base = 2;
        else if (aacCodec && hls) base = 3;
        else return -1;
        boolean preview = (t.has("snipped") && t.get("snipped").getAsBoolean()) || str(t, "url").contains("/preview/");
        return base + (preview ? 10 : 0);
    }

    private byte[] downloadFile(String url, String id, int gen) throws Exception {
        HttpResponse<InputStream> r = HTTP.open("GET", url, cdnHeaders());
        if (r.statusCode() / 100 != 2) { r.body().close(); throw new IOException("HTTP " + r.statusCode()); }
        long[] prog = progress.get(id);
        prog[1] = r.headers().firstValueAsLong("Content-Length").orElse(-1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = r.body()) {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (generation.get() != gen) throw new CancellationException("cancelled");
                out.write(buf, 0, n);
                prog[0] = out.size();
                if (out.size() > MAX_TRACK_BYTES) throw new IOException(T.get("err.track_too_big"));
            }
        }
        return out.toByteArray();
    }

    /** HLS без шифрования: init-сегмент (если есть) и сегменты просто склеиваются — получается обычный mp3 или фрагментированный MP4. */
    private byte[] downloadHls(String playlistUrl, String id, int gen) throws Exception {
        String text = HTTP.get(playlistUrl, cdnHeaders());
        if (text.contains("#EXT-X-KEY") && !text.contains("METHOD=NONE")) throw new IOException(T.get("err.encrypted"));
        URI base = URI.create(playlistUrl);

        List<String> parts = new ArrayList<>();
        Matcher init = Pattern.compile("#EXT-X-MAP:[^\\n]*URI=\"([^\"]+)\"").matcher(text);
        if (init.find()) parts.add(base.resolve(init.group(1)).toString());
        for (String line : text.split("\\R")) if (!line.isBlank() && !line.startsWith("#")) parts.add(base.resolve(line.trim()).toString());
        if (parts.isEmpty()) throw new IOException(T.get("err.empty_playlist"));

        long[] prog = progress.get(id);
        prog[1] = parts.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String part : parts) {
            if (generation.get() != gen) throw new CancellationException("cancelled");
            HttpResponse<InputStream> r = HTTP.open("GET", part, cdnHeaders());
            try (InputStream in = r.body()) {
                if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode());
                in.transferTo(out);
            }
            prog[0]++;
            if (out.size() > MAX_TRACK_BYTES) throw new IOException(T.get("err.track_too_big"));
        }
        return out.toByteArray();
    }

    @Override public int loadPercent(String id) {
        long[] p = id == null ? null : progress.get(id);
        return p == null || p[1] <= 0 ? -1 : (int) Math.min(100, p[0] * 100 / p[1]);
    }

    @Override public boolean isCached(String id) { return id != null && cache.has("sc_" + id); }

    /** Запрос, которому токен не обязателен: если токен протух или отвергнут, пробуем без него. */
    private JsonObject api(String method, String path) throws Exception {
        try {
            return request(method, path, token != null);
        } catch (IOException e) {
            if (token == null || e.getMessage() == null || !e.getMessage().matches(".*HTTP (401|403)")) throw e;
            return request(method, path, false);
        }
    }

    /** Запрос, который работает только с токеном (аккаунт, лайки): без него ответ бессмыслен. */
    private JsonObject apiAuth(String method, String path) throws Exception {
        if (token == null) throw new IOException(T.get("err.likes_need_token"));
        return request(method, path, true);
    }

    private JsonObject request(String method, String path, boolean auth) throws Exception {
        for (int attempt = 0; ; attempt++) {
            // токен выдан конкретному client_id и с другим не принимается; для обычного токена берём общий
            Jwt j = auth ? jwt() : null;
            String id = j != null && !j.clientId().isEmpty() && attempt == 0 ? j.clientId() : clientId();
            String url = API + path + (path.contains("?") ? "&" : "?") + "client_id=" + id;
            SafeHttp.Reply r = HTTP.send(method, url, headers(auth));
            if ((r.status() == 401 || r.status() == 403) && attempt == 0) { if (!auth) refreshClientId(); continue; }
            if (r.status() / 100 != 2) throw new IOException("SoundCloud: HTTP " + r.status());
            try {
                JsonElement e = JsonParser.parseString(r.body());
                return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
            } catch (Exception e) {
                return new JsonObject();
            }
        }
    }

    private Map<String, String> headers(boolean auth) {
        Map<String, String> h = new HashMap<>(cdnHeaders());
        h.put("Accept", "application/json");
        String t = token;
        if (auth && t != null) h.put("Authorization", "OAuth " + t);
        return h;
    }

    /** Файлы с CDN запрашиваются без токена: ему там делать нечего. */
    private static Map<String, String> cdnHeaders() { return Map.of("User-Agent", SafeHttp.BROWSER_UA); }

    private String clientId() throws Exception {
        String id = clientId;
        return id != null ? id : refreshClientId();
    }

    private synchronized String refreshClientId() throws Exception {
        String html = HTTP.get("https://soundcloud.com/", cdnHeaders());
        List<String> scripts = new ArrayList<>();
        Matcher m = SCRIPT.matcher(html);
        while (m.find()) scripts.add(m.group(1));
        for (int i = scripts.size() - 1; i >= 0; i--) {
            Matcher c = CLIENT_ID.matcher(HTTP.get(scripts.get(i), cdnHeaders()));
            if (c.find()) {
                clientId = c.group(1);
                TokenStore.set(CLIENT_ID_KEY, clientId);
                return clientId;
            }
        }
        throw new IOException(T.get("err.sc_changed"));
    }

    /** Короткие ссылки из «Поделиться» (on.soundcloud.com/...) ведут на настоящий адрес через редирект. */
    private static String expandShortLink(String text) throws Exception {
        String s = text.trim().replaceAll("^[<\"'(\\[]+|[>\"')\\]]+$", "");
        if (!s.matches("(?i)^(https?://)?(on|soundcloud\\.app)\\.?(goo\\.gl|soundcloud\\.com)?/.*") && !s.matches("(?i)^(https?://)?on\\.soundcloud\\.com/.*")) return s;
        String url = s.matches("(?i)^https?://.*") ? s : "https://" + s;
        try {
            return HTTP.open("GET", url, cdnHeaders()).uri().toString();
        } catch (IOException e) {
            return s;
        }
    }

    private static String profileName(String input) {
        String s = input.trim().replaceAll("^[<\"'(\\[]+|[>\"')\\]]+$", "")
                .replaceFirst("(?i)^https?://", "").replaceFirst("(?i)^(www\\.|m\\.|mobile\\.)?soundcloud\\.com/", "").replaceFirst("^@", "");
        int cut = 0;
        while (cut < s.length() && "/?#".indexOf(s.charAt(cut)) < 0) cut++;
        return s.substring(0, cut);
    }

    private static String extractToken(String s) {
        Matcher jwt = JWT.matcher(s);
        if (jwt.find()) return jwt.group();
        Matcher m = TOKEN.matcher(s);
        if (m.find()) return m.group();
        int i = s.indexOf("oauth_token=");
        if (i >= 0) {
            s = s.substring(i + "oauth_token=".length());
            int end = 0;
            while (end < s.length() && ";& \"".indexOf(s.charAt(end)) < 0) end++;
            s = s.substring(0, end);
        }
        return s.replaceFirst("(?i)^(oauth|bearer)\\s+", "").replace("\"", "").trim();
    }

    @Override public void stop() {
        generation.incrementAndGet();
        mp3.stop();
        aac.stop();
    }

    @Override public void pause()  { engine.pause(); }
    @Override public void resume() { engine.resume(); }
    @Override public void seek(long ms) { engine.seek(ms); }
    @Override public long positionMs() { return engine.positionMs(); }
    @Override public boolean isPlaying() { return engine.isPlaying(); }

    @Override public void setVolume(float v) {
        mp3.setVolume(v);
        aac.setVolume(v);
    }

    @Override public void onFinished(Runnable r) { finished = r; }

    @Override public void onAudioError(Consumer<String> sink) {
        Consumer<Throwable> report = Background.errorsTo(sink);
        mp3.onError(report);
        aac.onError(report);
    }

    private static String orEmpty(String s) { return s == null ? "" : s; }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
