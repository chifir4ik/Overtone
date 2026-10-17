package dev.musicplayer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.musicplayer.model.Track;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Что помнить между запусками: громкость, последний поиск, список треков и трек.
 * Запись отложенная (flushIfDirty), чтобы не писать на диск при каждом движении ползунка.
 */
public final class SessionState {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static SessionState instance;
    private static volatile boolean dirty;
    private static volatile long lastWrite;

    /** Трек в виде простого объекта (Gson надёжнее работает с обычными классами, чем с record). */
    public static final class TrackData {
        public String id, title, artist, album, coverUrl, provider;
        public long durationMs;

        public static TrackData of(Track t) {
            TrackData d = new TrackData();
            d.id = t.id(); d.title = t.title(); d.artist = t.artist(); d.album = t.album();
            d.coverUrl = t.coverUrl(); d.provider = t.provider(); d.durationMs = t.durationMs();
            return d;
        }

        public Track toTrack() { return new Track(id, title, artist, album, durationMs, coverUrl, provider == null ? "yandex" : provider); }
    }

    public float volume = 0.8f;
    /** Громкость до выключения звука кнопкой-динамиком. */
    public float preMuteVolume = 0.5f;
    public String query = "";
    public TrackData track;
    public String repeat = "OFF";
    public String platform;
    public boolean shuffle;
    /** Инструкция показывается один раз при первом открытии плеера. */
    public boolean helpSeen;
    public List<TrackData> userQueue = new ArrayList<>();
    public List<TrackData> results = new ArrayList<>();

    public static synchronized SessionState get() {
        if (instance == null) instance = load();
        return instance;
    }

    public List<Track> resultTracks() {
        List<Track> out = new ArrayList<>();
        if (results != null) for (TrackData d : results) if (d != null && d.id != null) out.add(d.toTrack());
        return out;
    }

    public Track currentTrack() { return track == null || track.id == null ? null : track.toTrack(); }

    public void setTrack(Track t) { track = t == null ? null : TrackData.of(t); }

    public List<Track> queueTracks() {
        List<Track> out = new ArrayList<>();
        if (userQueue != null) for (TrackData d : userQueue) if (d != null && d.id != null) out.add(d.toTrack());
        return out;
    }

    public void setQueue(List<Track> list) {
        List<TrackData> out = new ArrayList<>();
        for (Track t : list) out.add(TrackData.of(t));
        userQueue = out;
    }

    /** Библиотека из тысяч файлов целиком в сессию не нужна: после перезапуска достаточно начала списка. */
    private static final int MAX_SAVED_RESULTS = 300;

    public void setResults(List<Track> list) {
        List<TrackData> out = new ArrayList<>();
        for (Track t : list.subList(0, Math.min(list.size(), MAX_SAVED_RESULTS))) out.add(TrackData.of(t));
        results = out;
    }

    private static Path path() { return FabricLoader.getInstance().getConfigDir().resolve("musicplayer-session.json"); }

    private static SessionState load() {
        SessionState s = null;
        try {
            Path p = path();
            if (Files.exists(p)) s = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), SessionState.class);
        } catch (Exception ignored) {}
        if (s == null) s = new SessionState();
        if (s.query == null) s.query = "";
        if (s.results == null) s.results = new ArrayList<>();
        if (s.userQueue == null) s.userQueue = new ArrayList<>();
        if (s.repeat == null) s.repeat = "OFF";
        s.volume = Math.max(0f, Math.min(1f, s.volume));
        s.preMuteVolume = Math.max(0.05f, Math.min(1f, s.preMuteVolume));
        return s;
    }

    public static void markDirty() { dirty = true; }

    public static void flushIfDirty(long minIntervalMs) {
        if (!dirty || System.currentTimeMillis() - lastWrite < minIntervalMs) return;
        saveNow();
    }

    public static synchronized void saveNow() {
        dirty = false;
        lastWrite = System.currentTimeMillis();
        try {
            Path p = path();
            Files.createDirectories(p.getParent());
            Files.writeString(p, GSON.toJson(get()), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }
}
