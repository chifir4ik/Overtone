package dev.musicplayer.provider;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.musicplayer.audio.AacPlayer;
import dev.musicplayer.audio.Mp4Audio;
import dev.musicplayer.model.Platform;
import dev.musicplayer.model.Track;
import dev.musicplayer.util.T;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.musicplayer.util.Json.str;

/** YouTube через внешний yt-dlp; мод принимает от него только AAC в MP4. */
public final class YtDlpProvider implements MusicProvider {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern VIDEO_PARAM = Pattern.compile("(?:^|&)v=([A-Za-z0-9_-]{11})(?:&|$)");
    private static final Pattern LIST_PARAM = Pattern.compile("(?:^|&)list=([A-Za-z0-9_-]{10,64})(?:&|$)");
    private static final long MAX_TRACK_BYTES = 150L << 20;

    private final AacPlayer aac = new AacPlayer();
    private final AudioCache cache = AudioCache.shared();
    private final AtomicInteger generation = new AtomicInteger();
    private final Object playback = new Object();
    private volatile Runnable finished;

    @Override public Platform platform() { return Platform.YOUTUBE; }
    @Override public boolean isLoggedIn() { return true; }
    @Override public String accountName() { return ""; }
    @Override public void logout() {}
    @Override public CompletableFuture<List<Track>> likedTracks() { return CompletableFuture.completedFuture(List.of()); }
    @Override public boolean supportsLikes() { return false; }

    private record Link(String value, boolean playlist) {}

    @Override public CompletableFuture<List<Track>> search(String query) {
        return Background.run(() -> {
            Link link = link(query);
            List<String> args;
            if (link != null && !link.playlist()) {
                args = List.of("--dump-single-json", "--no-playlist", "--", video(link.value()));
            } else if (link != null) {
                args = List.of("--flat-playlist", "--playlist-end", "200", "--dump-single-json", "--",
                        "https://www.youtube.com/playlist?list=" + link.value());
            } else {
                args = List.of("--flat-playlist", "--dump-single-json", "--", "ytsearch25:" + query);
            }
            byte[] raw = YtDlp.run(args, 8L << 20, Duration.ofSeconds(40), () -> false);
            JsonObject root = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
            List<Track> tracks = new ArrayList<>();
            if (root.has("entries") && root.get("entries").isJsonArray()) {
                for (JsonElement e : root.getAsJsonArray("entries")) {
                    if (!e.isJsonObject()) continue;
                    Track t = parse(e.getAsJsonObject());
                    if (t != null) tracks.add(t);
                    if (tracks.size() == 200) break;
                }
            } else {
                Track t = parse(root);
                if (t != null) tracks.add(t);
            }
            return tracks;
        });
    }

    private static Link link(String input) {
        String value = input == null ? "" : input.trim();
        if (value.matches("(?i)^(?:www\\.)?(?:youtube\\.com|youtu\\.be)/.*") || value.matches("(?i)^music\\.youtube\\.com/.*"))
            value = "https://" + value;
        URI uri;
        try { uri = URI.create(value); } catch (IllegalArgumentException e) { return null; }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) return null;
        String host = uri.getHost();
        if (host == null) return null;
        host = host.toLowerCase(Locale.ROOT);
        String path = uri.getPath();
        String params = uri.getRawQuery() == null ? "" : uri.getRawQuery();
        boolean youtube = host.equals("youtube.com") || host.equals("www.youtube.com") || host.equals("music.youtube.com");
        if (host.equals("youtu.be") || host.equals("www.youtu.be")) {
            Matcher m = Pattern.compile("^/([A-Za-z0-9_-]{11})(?:/|$)").matcher(path);
            return m.find() ? new Link(m.group(1), false) : null;
        }
        if (!youtube) return null;
        if (path.startsWith("/shorts/")) {
            Matcher m = Pattern.compile("^/shorts/([A-Za-z0-9_-]{11})(?:/|$)").matcher(path);
            if (m.find()) return new Link(m.group(1), false);
        }
        if (path.equals("/watch")) {
            Matcher m = VIDEO_PARAM.matcher(params);
            if (m.find()) return new Link(m.group(1), false);
        }
        if (path.equals("/playlist") || path.equals("/watch")) {
            Matcher m = LIST_PARAM.matcher(params);
            if (m.find()) return new Link(m.group(1), true);
        }
        return null;
    }

    private static Track parse(JsonObject o) {
        String id = str(o, "id");
        if (!ID.matcher(id).matches()) return null;
        String artist = str(o, "channel");
        if (artist.isBlank()) artist = str(o, "uploader");
        JsonElement length = o.get("duration");
        long duration = 0;
        if (length != null && length.isJsonPrimitive() && length.getAsJsonPrimitive().isNumber()) {
            double seconds = length.getAsDouble();
            if (Double.isFinite(seconds) && seconds > 0) duration = Math.round(seconds * 1000);
        }
        return new Track(id, str(o, "title"), artist, "", duration, cover(id), Platform.YOUTUBE.id);
    }

    private static String video(String id) { return "https://www.youtube.com/watch?v=" + id; }
    private static String cover(String id) { return "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg"; }

    @Override public CompletableFuture<Void> play(Track t) {
        int my;
        synchronized (playback) {
            my = generation.incrementAndGet();
            aac.stop();
        }
        return Background.run(() -> {
            String id = t == null ? null : t.id();
            if (id == null || !ID.matcher(id).matches()) throw new IOException(T.get("err.ytdlp_failed", "invalid video id"));
            String key = "yt_" + id;
            byte[] data = cache.get(key);
            if (data == null) {
                data = YtDlp.run(List.of("-f", "bestaudio[ext=m4a]/bestaudio[acodec^=mp4a]", "--no-playlist",
                        "-o", "-", "--", video(id)), MAX_TRACK_BYTES, Duration.ofSeconds(180), () -> generation.get() != my);
                if (!Mp4Audio.looksLikeMp4(data)) throw new IOException(T.get("err.ytdlp_format"));
                if (generation.get() == my) cache.put(key, data);
            }
            if (!Mp4Audio.looksLikeMp4(data)) throw new IOException(T.get("err.ytdlp_format"));
            synchronized (playback) {
                if (generation.get() != my) return null;
                aac.play(data, 0, () -> {
                    Runnable r = finished;
                    if (generation.get() == my && r != null) r.run();
                });
            }
            return null;
        });
    }

    @Override public void stop() {
        synchronized (playback) {
            generation.incrementAndGet();
            aac.stop();
        }
    }
    @Override public void pause() { aac.pause(); }
    @Override public void resume() { aac.resume(); }
    @Override public void seek(long ms) { aac.seek(ms); }
    @Override public long positionMs() { return aac.positionMs(); }
    @Override public boolean isPlaying() { return aac.isPlaying(); }
    @Override public void setVolume(float v) { aac.setVolume(v); }
    @Override public void onFinished(Runnable r) { finished = r; }
    @Override public void onAudioError(Consumer<String> sink) { aac.onError(Background.errorsTo(sink)); }
    @Override public boolean isCached(String id) { return id != null && cache.has("yt_" + id); }
    @Override public int loadPercent(String id) { return -1; }
}
