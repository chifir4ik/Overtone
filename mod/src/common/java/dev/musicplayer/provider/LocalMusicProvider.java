package dev.musicplayer.provider;

import dev.musicplayer.util.T;
import dev.musicplayer.audio.AacPlayer;
import dev.musicplayer.audio.AudioEngine;
import dev.musicplayer.audio.FlacPlayer;
import dev.musicplayer.audio.Mp3Player;
import dev.musicplayer.audio.OggPlayer;
import dev.musicplayer.audio.PcmPlayer;
import dev.musicplayer.local.LocalLibrary;
import dev.musicplayer.lyrics.LrcParser;
import dev.musicplayer.model.LyricLine;
import dev.musicplayer.model.Platform;
import dev.musicplayer.model.Track;
import dev.musicplayer.util.Browser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Музыка с компьютера: mp3, flac, ogg (Vorbis), m4a (AAC) и wav/aiff/au. Аккаунт не нужен, ничего не уходит в сеть. */
public final class LocalMusicProvider implements MusicProvider {
    private static final long MAX_FILE_BYTES = 400L << 20;


    private final LocalLibrary library = new LocalLibrary();
    private final Mp3Player mp3 = new Mp3Player();
    private final PcmPlayer pcm = new PcmPlayer();
    private final FlacPlayer flac = new FlacPlayer();
    private final OggPlayer ogg = new OggPlayer();
    private final AacPlayer aac = new AacPlayer();
    private final List<AudioEngine> engines = List.of(mp3, pcm, flac, ogg, aac);
    private final AtomicInteger generation = new AtomicInteger();
    private volatile AudioEngine engine = mp3;
    private volatile Runnable finished;
    private volatile float volume = 0.8f;

    @Override public Platform platform() { return Platform.LOCAL; }
    @Override public boolean isLoggedIn() { return true; }
    @Override public String accountName() { return library.status(); }
    @Override public void logout() { }
    @Override public String likedLabel() { return T.get("liked.library"); }

    public void refresh() { library.rescan(); }

    public int libraryVersion() { return library.version(); }

    public boolean openFolder() {
        try {
            Path dir = LocalLibrary.defaultFolder();
            Files.createDirectories(dir);
            return Browser.openFolder(dir);
        } catch (IOException e) {
            return false;
        }
    }

    public int addPaths(List<Path> paths) { return library.addPaths(paths); }

    /** Убирает трек из библиотеки; сам файл остаётся на диске. */
    public boolean remove(String trackId) { return library.remove(trackId); }

    @Override public CompletableFuture<List<Track>> search(String query) { return Background.run(() -> library.search(query)); }

    @Override public CompletableFuture<List<Track>> likedTracks() { return Background.run(() -> library.search("")); }

    @Override public CompletableFuture<Void> play(Track t) {
        int my = generation.incrementAndGet();
        engine.stop();
        return Background.run(() -> {
            Path file = library.pathOf(t.id());
            if (file == null || !Files.isRegularFile(file))
                throw new IOException(T.get("err.file_missing"));
            if (Files.size(file) > MAX_FILE_BYTES) throw new IOException(T.get("err.file_too_big"));
            byte[] data = Files.readAllBytes(file);
            if (generation.get() != my) return null;
            LocalLibrary.Kind kind = LocalLibrary.kindOf(file);
            AudioEngine e = kind == null ? pcm : switch (kind) {
                case MP3 -> mp3;
                case FLAC -> flac;
                case OGG -> ogg;
                case MP4 -> aac;
                case PCM -> pcm;
            };
            engine = e;
            e.setVolume(volume);
            e.play(data, 0, () -> {
                Runnable r = finished;
                if (generation.get() == my && r != null) r.run();
            });
            return null;
        });
    }

    @Override public void stop() {
        generation.incrementAndGet();
        for (AudioEngine e : engines) e.stop();
    }

    @Override public void pause()  { engine.pause(); }
    @Override public void resume() { engine.resume(); }
    @Override public void seek(long ms) { engine.seek(ms); }
    @Override public long positionMs() { return engine.positionMs(); }
    @Override public boolean isPlaying() { return engine.isPlaying(); }

    @Override public void setVolume(float v) {
        volume = v;
        for (AudioEngine e : engines) e.setVolume(v);
    }

    @Override public void onFinished(Runnable r) { finished = r; }

    @Override public void onAudioError(Consumer<String> sink) {
        Consumer<Throwable> report = Background.errorsTo(sink);
        for (AudioEngine e : engines) e.onError(report);
    }

    /** synced=true — файл .lrc с таймкодами; synced=false — обычный текст (из .txt или тегов). */
    @Override public CompletableFuture<List<LyricLine>> lyrics(Track t, boolean synced) {
        return Background.run(() -> {
            Path file = library.pathOf(t.id());
            if (file == null) return List.<LyricLine>of();
            String text = library.lyricsText(file);
            if (text.isBlank()) return List.<LyricLine>of();
            List<LyricLine> timed = LrcParser.parse(text);
            if (synced) return timed;
            return timed.isEmpty() ? LrcParser.plain(text) : List.<LyricLine>of();
        }).exceptionally(e -> List.of());
    }

}
