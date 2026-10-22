package dev.musicplayer.controller;

import dev.musicplayer.util.T;
import dev.musicplayer.audio.GameVolume;
import dev.musicplayer.config.SessionState;
import dev.musicplayer.lyrics.LyricsService;
import dev.musicplayer.model.LyricLine;
import dev.musicplayer.model.MusicEntity;
import dev.musicplayer.model.Page;
import dev.musicplayer.model.Platform;
import dev.musicplayer.model.Track;
import dev.musicplayer.provider.LocalMusicProvider;
import dev.musicplayer.provider.MusicProvider;
import dev.musicplayer.provider.SoundCloudProvider;
import dev.musicplayer.provider.SpotifyProvider;
import dev.musicplayer.provider.YandexMusicProvider;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/** Состояние плеера. Живёт, пока запущена игра: музыка продолжает играть, когда экран закрыт. */
public final class PlayerController {
    public static final PlayerController I = new PlayerController();

    public enum Repeat { OFF, ALL, ONE }

    public final YandexMusicProvider yandex = new YandexMusicProvider();
    public final LocalMusicProvider local = new LocalMusicProvider();
    public final SoundCloudProvider soundcloud = new SoundCloudProvider();
    public final SpotifyProvider spotify = new SpotifyProvider();
    private final Map<Platform, MusicProvider> providers = new EnumMap<>(Platform.class);

    /** Площадка, открытая в плеере. null — пользователь на экране выбора. Музыка при этом играет дальше. */
    public volatile Platform platform;

    public enum Tab { TRACKS, ARTISTS, ALBUMS, PLAYLISTS, STATIONS }

    /** Вкладка поиска (на открытой странице артиста: TRACKS — популярное, ALBUMS — альбомы). */
    public volatile Tab tab = Tab.TRACKS;
    public volatile List<MusicEntity> artists = List.of();
    public volatile List<MusicEntity> albums = List.of();
    public volatile List<MusicEntity> playlists = List.of(MusicEntity.MINE);
    private final List<Page> trail = new CopyOnWriteArrayList<>();

    public volatile List<Track> results = List.of();
    public volatile int resultsVersion = 0;
    public volatile String status = "";
    public volatile Track current;
    public volatile List<LyricLine> lyrics = List.of();
    public volatile boolean lyricsLoading;
    /** Громкость, которую выставил пользователь (0..1). Реальная = она × множитель игры, см. {@link GameVolume}. */
    public volatile float volume = 0.8f;
    /** true — трек выбран (восстановлен после перезапуска или очередь кончилась), но не играет. */
    public volatile boolean idle = true;
    public volatile Repeat repeat = Repeat.OFF;
    public volatile boolean shuffle;

    /** true — играет «Моя волна»: следующие треки подгружает Яндекс, а не список. */
    public volatile boolean waveActive;
    public volatile boolean waveLoading;
    public volatile boolean waveView;
    private volatile String waveSession, waveBatch;
    private final List<Track> waveQueue = new CopyOnWriteArrayList<>();
    private final ArrayDeque<Track> waveHistory = new ArrayDeque<>();
    private volatile boolean waveFetching, waveStalled;
    /** Станция запущенной волны: «user:onyourwave» — «Моя волна», остальные — жанры, настроения и т. д. */
    public volatile String waveSeed = YandexMusicProvider.WAVE_SEED;
    public volatile List<MusicEntity> stations = List.of();
    public volatile boolean stationsLoading;
    /** Недавно сыгранные треки волны: сервер иногда присылает уже слышанное, такие треки пропускаем. */
    private final ArrayDeque<String> waveSeen = new ArrayDeque<>();
    private static final int WAVE_SEEN_MAX = 80;

    private volatile List<Track> queue = List.of();
    private volatile int index = -1;
    private final List<Track> userQueue = new CopyOnWriteArrayList<>();
    private final ArrayDeque<Integer> history = new ArrayDeque<>();   // для «назад» в режиме шафла
    private final Random rnd = new Random();
    /** Заранее выбранные следующие треки в режиме «случайно»: нужны, чтобы знать, что скачивать наперёд. */
    private final ArrayDeque<Integer> shufflePlan = new ArrayDeque<>();
    private int prefetchTick;
    /** Результаты — это вся локальная библиотека: она обновляется сама, пока идёт индексация. */
    private volatile boolean showingLibrary;
    private int libraryVersion = -1;
    private float lastApplied = -1f;

    private PlayerController() {
        providers.put(Platform.YANDEX, yandex);
        providers.put(Platform.LOCAL, local);
        providers.put(Platform.SOUNDCLOUD, soundcloud);
        providers.put(Platform.SPOTIFY, spotify);
        for (MusicProvider p : providers.values()) {
            p.onFinished(() -> onTrackEnd(p));
            p.onAudioError(m -> { status = T.get("status.audio_error", m); idle = true; });   // idle — чтобы кнопка play пробовала снова
        }
        restoreSession();
        applyVolume(true);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { syncSession(); SessionState.saveNow(); }, "musicplayer-save"));
    }

    /** В сессии могут остаться треки площадок, которых в моде больше нет: играть их нечем. */
    private static List<Track> known(List<Track> tracks) {
        return tracks.stream().filter(PlayerController::isKnown).toList();
    }

    private static boolean isKnown(Track t) {
        return t.provider() == null || Platform.byIdOrNull(t.provider()) != null;
    }

    private void restoreSession() {
        SessionState st = SessionState.get();
        volume = st.volume;
        shuffle = st.shuffle;
        Platform lastPlatform = Platform.byIdOrNull(st.platform);
        platform = lastPlatform != null && providers.containsKey(lastPlatform) ? lastPlatform : null;
        try { repeat = Repeat.valueOf(st.repeat); } catch (Exception e) { repeat = Repeat.OFF; }
        userQueue.addAll(known(st.queueTracks()));
        List<Track> saved = known(st.resultTracks());
        Track last = st.currentTrack();
        if (last != null && !isKnown(last)) last = null;
        results = saved;
        if (last != null) {
            current = last;
            idle = true;
            int i = indexOf(saved, last.id());
            if (i >= 0) { queue = saved; index = i; } else { queue = List.of(last); index = 0; }
        }
    }

    private static int indexOf(List<Track> list, String id) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).id() != null && list.get(i).id().equals(id)) return i;
        return -1;
    }

    private void syncSession() {
        SessionState st = SessionState.get();
        st.volume = volume;
        st.shuffle = shuffle;
        st.repeat = repeat.name();
        st.platform = platform == null ? null : platform.id;
        st.setTrack(current);
        st.setResults(results);
        st.setQueue(new ArrayList<>(userQueue));
    }

    private void persist() { syncSession(); SessionState.markDirty(); }

    public void tickPersist() {
        applyVolume(false);
        if (showingLibrary && platform == Platform.LOCAL && local.libraryVersion() != libraryVersion) {
            libraryVersion = local.libraryVersion();
            local.likedTracks().thenAccept(this::setResults);
        }
        SessionState.flushIfDirty(2000);
        if (++prefetchTick >= 100) { prefetchTick = 0; refreshPrefetch(); }   // раз в ~5 с: повтор после сбоев, смена настроек
    }

    public boolean has(Platform p) { return providers.containsKey(p); }

    public MusicProvider provider(Platform p) { return providers.get(p); }

    public MusicProvider active() { return platform == null ? null : providers.get(platform); }

    /** Провайдер, которому принадлежит трек. Треки без метки — из Яндекс Музыки (старые сохранения). */
    public MusicProvider provider(Track t) {
        MusicProvider p = t == null ? null : providers.get(Platform.byId(t.provider()));
        return p != null ? p : yandex;
    }

    /** Тот, кто сейчас играет (или сыграл бы): позиция, пауза и громкость читаются у него. */
    public MusicProvider engine() {
        Track t = current;
        MusicProvider p = t != null ? provider(t) : active();
        return p != null ? p : yandex;
    }

    /** Выбранная площадка готова: для Яндекса это значит «вошли в аккаунт». */
    public boolean ready() {
        MusicProvider p = active();
        return p != null && p.isLoggedIn();
    }

    public synchronized void selectPlatform(Platform p) {
        if (p == null || !providers.containsKey(p)) return;
        boolean changed = platform != p;
        platform = p;
        if (changed) {
            waveView = false;
            trail.clear();
            tab = Tab.TRACKS;
            results = List.of();
            resultsVersion++;
        }
        persist();
        if (p == Platform.LOCAL) {
            local.refresh();
            if (results.isEmpty()) loadLiked();
        }
    }

    public void leavePlatform() {
        platform = null;
        persist();
    }

    /** Файлы, перетащенные в окно плеера, всегда попадают в «Мои файлы». */
    public boolean addLocalPaths(List<Path> paths) {
        if (local.addPaths(paths) == 0) { status = T.get("status.no_audio_files"); return false; }
        selectPlatform(Platform.LOCAL);
        loadLiked();
        return true;
    }

    public void submitToken(String text) {
        status = T.get("status.checking_token");
        yandex.submitToken(text).whenComplete((v, e) -> status = e == null ? "" : T.get("status.login_failed", msg(e)));
    }

    /** Код для входа в Яндекс, пока его ждут; null — вход по коду не идёт. */
    public volatile YandexMusicProvider.DeviceCode yandexCode;

    public void loginYandexByCode() {
        status = T.get("status.code_getting");
        yandex.loginWithCode(code -> {
            yandexCode = code;
            status = "";
            copyToClipboard(code.userCode());
            dev.musicplayer.util.Browser.open(code.url());
        }).whenComplete((v, e) -> {
            yandexCode = null;
            if (e == null) { status = ""; return; }
            if (e instanceof java.util.concurrent.CancellationException || e.getCause() instanceof java.util.concurrent.CancellationException) return;
            status = T.get("status.login_failed", msg(e));
        });
    }

    public void cancelYandexCode() {
        yandex.cancelCodeLogin();
        yandexCode = null;
        status = "";
    }

    /** Буфер обмена трогаем только из потока игры, иначе GLFW падает. */
    public static void copyToClipboard(String text) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        mc.execute(() -> mc.keyboardHandler.setClipboard(text));
    }

    public void linkSoundCloud(String text) {
        status = T.get("status.checking_token");
        soundcloud.link(text).whenComplete((v, e) -> status = e == null ? "" : T.get("status.login_failed", msg(e)));
    }

    public void loginSpotify(String clientId) {
        status = T.get("status.sp_browser");
        spotify.login(clientId).whenComplete((v, e) -> status = e == null ? "" : T.get("status.login_failed", msg(e)));
    }

    public void openLocalFolder() {
        if (!local.openFolder()) status = T.get("status.folder_failed", dev.musicplayer.local.LocalLibrary.defaultFolder().toAbsolutePath().normalize());
    }

    public void loginSaved(dev.musicplayer.config.TokenStore.Account a) {
        if (a == null) return;
        status = T.get("status.logging_in", a.displayName());
        yandex.submitToken(a.token()).whenComplete((v, e) -> status = e == null ? "" : T.get("status.login_failed", msg(e)));
    }

    public synchronized void logout() {
        MusicProvider p = active();
        if (p == soundcloud) {
            // SoundCloud играет и без аккаунта: отвязываем профиль, музыку не трогаем
            soundcloud.logout();
            status = "";
            persist();
            return;
        }
        if (p == null || !p.platform().needsLogin) return;
        boolean playingHere = current != null && provider(current) == p;
        p.logout();
        trail.clear();
        results = List.of();
        resultsVersion++;
        userQueue.removeIf(t -> provider(t) == p);
        if (playingHere) {
            current = null;
            lyrics = List.of();
            lyricsLoading = false;
            queue = List.of();
            index = -1;
            history.clear();
            idle = true;
        }
        if (p == yandex) stopWave();
        status = "";
        persist();
    }

    public void search(String q) {
        if (q == null || q.isBlank()) return;
        waveView = false;
        MusicProvider p = active();
        if (p == null) return;
        showingLibrary = false;
        status = T.get("status.searching");
        trail.clear();
        if (p == yandex) {
            yandex.searchAll(q.trim()).whenComplete((all, e) -> {
                if (e != null) { status = T.get("status.search_error", msg(e)); return; }
                artists = all.artists();
                albums = all.albums();
                playlists = all.playlists();
                setResults(all.tracks());
                tab = !all.tracks().isEmpty() ? Tab.TRACKS : !all.artists().isEmpty() ? Tab.ARTISTS
                        : !all.albums().isEmpty() ? Tab.ALBUMS : Tab.TRACKS;
                status = all.tracks().isEmpty() && all.artists().isEmpty() && all.albums().isEmpty() ? T.get("status.nothing_found") : "";
            });
            return;
        }
        if (p == spotify) {
            spotify.searchAll(q.trim()).whenComplete((all, e) -> {
                if (e != null) { status = T.get("status.search_error", msg(e)); return; }
                artists = List.of();
                albums = List.of();
                List<MusicEntity> pls = new java.util.ArrayList<>();
                pls.add(MusicEntity.MINE);
                pls.addAll(all.playlists());
                playlists = pls;
                setResults(all.tracks());
                tab = !all.tracks().isEmpty() || all.playlists().isEmpty() ? Tab.TRACKS : Tab.PLAYLISTS;
                status = all.tracks().isEmpty() && all.playlists().isEmpty() ? T.get("status.nothing_found") : "";
            });
            return;
        }
        p.search(q.trim()).whenComplete((r, e) -> {
            if (e != null) { status = T.get("status.search_error", msg(e)); return; }
            setResults(r);
            status = r.isEmpty() ? T.get("status.nothing_found") : "";
        });
    }

    public void loadLiked() {
        waveView = false;
        trail.clear();
        tab = Tab.TRACKS;
        MusicProvider p = active();
        if (p == null) return;
        showingLibrary = p == local;
        libraryVersion = local.libraryVersion();
        status = T.get("status.loading");
        p.likedTracks().whenComplete((r, e) -> {
            if (e != null) { status = T.get("status.error", msg(e)); return; }
            setResults(r);
            status = r.isEmpty() ? T.get("status.empty_list", p.likedLabel()) : "";
        });
    }


    /** Открытая сейчас страница артиста, альбома или плейлиста, либо null. */
    public Page page() { return trail.isEmpty() ? null : trail.get(trail.size() - 1); }

    public void open(MusicEntity e) {
        if (e == null) return;
        status = T.get("status.opening");
        (platform == Platform.SPOTIFY ? spotify.open(e) : yandex.open(e)).whenComplete((pg, ex) -> {
            if (ex != null) { status = T.get("status.error", msg(ex)); return; }
            waveView = false;
            trail.add(pg);
            tab = Tab.TRACKS;
            status = pg.tracks().isEmpty() && pg.entities().isEmpty() ? T.get("status.nothing_found") : "";
        });
    }

    public void back() {
        if (!trail.isEmpty()) trail.remove(trail.size() - 1);
        tab = Tab.TRACKS;
    }

    public void playAll(Page pg) {
        if (pg != null && !pg.tracks().isEmpty()) playFrom(pg.tracks(), 0);
    }

    /** Клик по имени артиста в плеере: у трека хранится только имя, поэтому находим страницу поиском. */
    public void openArtistOf(Track t) {
        if (t == null || t.artist() == null || t.artist().isBlank() || !isYandex(t)) return;
        String name = t.artist().split(",")[0].trim();
        status = T.get("status.opening");
        yandex.searchAll(name).whenComplete((all, ex) -> {
            if (ex != null) { status = T.get("status.error", msg(ex)); return; }
            MusicEntity best = all.artists().stream().filter(a -> a.title().equalsIgnoreCase(name)).findFirst()
                    .orElse(all.artists().isEmpty() ? null : all.artists().get(0));
            if (best == null) { status = T.get("status.nothing_found"); return; }
            open(best);
        });
    }

    public void openAlbumOf(Track t) {
        if (t == null || t.album() == null || t.album().isBlank() || !isYandex(t)) return;
        String album = t.album();
        status = T.get("status.opening");
        yandex.searchAll(t.artist().split(",")[0].trim() + " " + album).whenComplete((all, ex) -> {
            if (ex != null) { status = T.get("status.error", msg(ex)); return; }
            MusicEntity best = all.albums().stream().filter(a -> a.title().equalsIgnoreCase(album)).findFirst()
                    .orElse(all.albums().isEmpty() ? null : all.albums().get(0));
            if (best == null) { status = T.get("status.nothing_found"); return; }
            open(best);
        });
    }

    private static boolean isYandex(Track t) { return t.provider() == null || "yandex".equals(t.provider()); }

    private void setResults(List<Track> r) { results = r; resultsVersion++; persist(); }

    public synchronized void playFrom(List<Track> list, int i) {
        if (i < 0 || i >= list.size()) return;
        stopWave();
        queue = list; index = i;
        history.clear();
        shufflePlan.clear();
        playTrack(list.get(i));
    }

    private void playTrack(Track t) {
        current = t;
        idle = false;
        persist();
        lyrics = List.of();
        lyricsLoading = true;
        status = T.get("status.loading_track");
        MusicProvider p = provider(t);
        for (MusicProvider other : providers.values()) if (other != p) other.stop();
        p.play(t).whenComplete((v, e) -> {
            if (current != t) return;
            if (e != null) status = T.get("status.play_failed", msg(e));
            else status = SoundCloudProvider.isPreviewOnly(t.id()) && p == soundcloud ? T.get("status.sc_preview") : "";
        });
        refreshPrefetch();
        fetchLyrics(t, p, 0);
    }

    /** Текст часто не находится из-за сбоя сети или VPN, поэтому ищем ещё несколько раз. */
    private static final long[] LYRICS_RETRY_MS = {6_000, 20_000, 60_000};
    private static final java.util.concurrent.ScheduledExecutorService LYRICS_RETRY =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread th = new Thread(r, "musicplayer-lyrics-retry");
                th.setDaemon(true);
                return th;
            });

    private void fetchLyrics(Track t, MusicProvider p, int attempt) {
        LyricsService.fetch(t, p).whenComplete((l, e) -> {
            if (current != t) return;
            List<LyricLine> got = e == null && l != null ? l : List.of();
            if (!got.isEmpty()) { lyrics = got; lyricsLoading = false; return; }
            if (attempt >= LYRICS_RETRY_MS.length) { lyricsLoading = false; return; }
            LYRICS_RETRY.schedule(() -> { if (current == t) fetchLyrics(t, p, attempt + 1); },
                    LYRICS_RETRY_MS[attempt], java.util.concurrent.TimeUnit.MILLISECONDS);
        });
    }

    private void onTrackEnd(MusicProvider source) {
        if (source != engine()) return;
        Track t = current;
        if (repeat == Repeat.ONE && t != null) { playTrack(t); return; }
        advance(false);
    }

    public void next() { advance(true); }

    /** manual=false — трек закончился сам: при выключенном повторе очередь на последнем треке останавливается. */
    private synchronized void advance(boolean manual) {
        if (!userQueue.isEmpty()) {
            Track t = userQueue.remove(0);
            playTrack(t);
            return;
        }
        if (waveActive) { waveAdvance(manual); return; }
        List<Track> q = queue;
        int n = q.size();
        if (n == 0) return;
        int ni;
        if (shuffle && n > 1) {
            history.push(index);
            while (history.size() > 50) history.removeLast();
            fillShufflePlan(n);
            ni = shufflePlan.pollFirst();
        } else {
            ni = index + 1;
            if (ni >= n) {
                if (repeat == Repeat.ALL || manual) ni = 0;
                else { idle = true; status = ""; return; }
            }
        }
        index = ni;
        playTrack(q.get(ni));
    }

    public synchronized void prev() {
        List<Track> q = queue;
        if (q.isEmpty()) return;
        if (!idle && engine().positionMs() > 3000) { engine().seek(0); return; }
        if (waveActive) { wavePrev(); return; }
        shufflePlan.clear();
        if (shuffle && !history.isEmpty()) index = history.pop();
        else index = (index - 1 + q.size()) % q.size();
        playTrack(q.get(index));
    }

    /** После смены устройства вывода: трек продолжается с того же места на новом устройстве. */
    public void reopenAudio() {
        if (current == null || idle) return;
        MusicProvider p = engine();
        p.seek(p.positionMs());
    }

    public void togglePause() {
        if (current == null) return;
        if (idle) { playTrack(current); return; }   // восстановленный трек или конец очереди
        MusicProvider p = engine();
        if (p.isPlaying()) p.pause(); else p.resume();
    }

    public void seekBy(long deltaMs) {
        if (current == null || idle) return;
        seekTo(engine().positionMs() + deltaMs);
    }

    public void seekTo(long ms) {
        Track t = current;
        if (t == null || idle) return;
        long dur = t.durationMs() > 0 ? t.durationMs() : Long.MAX_VALUE;
        engine().seek(Math.max(0, Math.min(dur - 500, ms)));
    }

    public synchronized void toggleShuffle() { shuffle = !shuffle; history.clear(); shufflePlan.clear(); persist(); refreshPrefetch(); }

    public void cycleRepeat() { repeat = Repeat.values()[(repeat.ordinal() + 1) % Repeat.values().length]; persist(); refreshPrefetch(); }

    public List<Track> queueSnapshot() { return new ArrayList<>(userQueue); }

    public void addToQueue(Track t) { if (t != null) { userQueue.add(t); persist(); refreshPrefetch(); } }

    public void playNext(Track t) { if (t != null) { userQueue.add(0, t); persist(); refreshPrefetch(); } }

    public void queueRemove(int i) { if (i >= 0 && i < userQueue.size()) { userQueue.remove(i); persist(); refreshPrefetch(); } }

    public synchronized void queueMoveUp(int i) {
        if (i <= 0 || i >= userQueue.size()) return;
        Track t = userQueue.remove(i);
        userQueue.add(i - 1, t);
        persist();
        refreshPrefetch();
    }

    public void playQueued(int i) {
        if (i < 0 || i >= userQueue.size()) return;
        Track t = userQueue.remove(i);
        playTrack(t);
    }

    /** Убирает локальный трек из библиотеки; файл на диске не удаляется. */
    public void removeLocal(Track t) {
        if (local.remove(t.id())) status = T.get("status.local_removed", t.title());
    }

    public void clearQueue() { userQueue.clear(); persist(); refreshPrefetch(); }

    public synchronized void startWave() { startWave(YandexMusicProvider.WAVE_SEED); }

    /** Клик по станции: если играла другая волна, она заменяется. */
    public synchronized void startStation(MusicEntity station) {
        if (station.id().equals(waveSeed) && (waveActive || waveLoading)) { waveView = true; return; }
        stopWave();
        startWave(station.id());
    }

    private synchronized void startWave(String seed) {
        waveView = true;
        if (waveActive || waveLoading) return;
        waveLoading = true;
        waveSeed = seed;
        status = T.get("status.wave_starting");
        yandex.waveStart(seed).whenComplete((b, e) -> {
            waveLoading = false;
            if (!seed.equals(waveSeed)) return;
            if (e != null) { status = T.get("status.wave_failed", msg(e)); waveView = false; return; }
            if (b.tracks().isEmpty()) { status = T.get("status.wave_empty"); waveView = false; return; }
            synchronized (this) {
                userQueue.clear();
                waveSession = b.sessionId();
                waveBatch = b.batchId();
                waveQueue.clear();
                waveHistory.clear();
                waveSeen.clear();
                waveQueue.addAll(b.tracks());
                waveActive = true;
                status = "";
                yandex.waveFeedback(waveSession, waveBatch, "radioStarted", null, 0);
                playWaveTrack(waveQueue.remove(0));
            }
        });
    }

    /** Останавливает режим волны (музыка при этом играет дальше, как обычный трек). */
    public synchronized void stopWave() {
        waveActive = false;
        waveLoading = false;
        waveView = false;
        waveSession = null; waveBatch = null;
        waveQueue.clear();
        waveHistory.clear();
        waveSeen.clear();
    }

    public void toggleWave() {
        if (waveActive && waveView) stopWave();
        else startWave();
    }

    public List<Track> waveSnapshot() {
        List<Track> out = new ArrayList<>();
        Track c = current;
        if (c != null) out.add(c);
        out.addAll(waveQueue);
        return out;
    }

    public void loadStations() {
        if (stationsLoading || !stations.isEmpty()) return;
        stationsLoading = true;
        yandex.waveStations().whenComplete((list, e) -> {
            stationsLoading = false;
            if (e != null) { status = T.get("status.error", msg(e)); return; }
            stations = list;
        });
    }

    /** Выбрасывает треки, которые волна уже играла или которые уже стоят в очереди; если не осталось ничего, берёт всё как есть. */
    private List<Track> fresh(List<Track> in) {
        List<Track> out = new ArrayList<>();
        for (Track t : in) {
            if (t.id() == null || waveSeen.contains(t.id())) continue;
            if (waveQueue.stream().anyMatch(q -> t.id().equals(q.id()))) continue;
            out.add(t);
        }
        return out.isEmpty() ? in : out;
    }

    private List<String> waveHistoryIds() {
        List<String> ids = new ArrayList<>(waveSeen);
        ids = ids.subList(Math.max(0, ids.size() - 10), ids.size());
        ids = new ArrayList<>(ids);
        for (Track t : waveQueue) if (t.id() != null && !ids.contains(t.id())) ids.add(t.id());
        return ids;
    }

    private void playWaveTrack(Track t) {
        if (t.id() != null) {
            waveSeen.add(t.id());
            while (waveSeen.size() > WAVE_SEEN_MAX) waveSeen.removeFirst();
        }
        playTrack(t);
        yandex.waveFeedback(waveSession, waveBatch, "trackStarted", t.id(), 0);
        topUpWave(t);
    }

    private void topUpWave(Track justStarted) {
        if (!waveActive || waveFetching || waveQueue.size() >= 4) return;
        waveFetching = true;
        String sid = waveSession;
        Track last = waveQueue.isEmpty() ? justStarted : waveQueue.get(waveQueue.size() - 1);
        yandex.waveMore(sid, waveSeed, waveHistoryIds()).whenComplete((b, e) -> {
            waveFetching = false;
            if (e != null) { status = T.get("status.wave_no_answer", msg(e)); waveStalled = false; idle = true; return; }
            if (!waveActive || !sid.equals(waveSession)) return;
            waveSession = b.sessionId();           // при сбое сервер мог открыть новую сессию
            waveBatch = b.batchId();
            for (Track t : fresh(b.tracks())) if (t.id() != null && !t.id().equals(last.id())) waveQueue.add(t);
            refreshPrefetch();
            if (waveStalled && !waveQueue.isEmpty()) {          // трек кончился раньше, чем пришла пачка
                waveStalled = false;
                synchronized (this) { status = ""; playWaveTrack(waveQueue.remove(0)); }
            }
        });
    }

    private synchronized void waveAdvance(boolean skipped) {
        Track prev = current;
        if (prev != null) {
            yandex.waveFeedback(waveSession, waveBatch, skipped ? "skip" : "trackFinished", prev.id(),
                    yandex.positionMs() / 1000.0);
            waveHistory.push(prev);
            while (waveHistory.size() > 20) waveHistory.removeLast();
        }
        if (!waveQueue.isEmpty()) { playWaveTrack(waveQueue.remove(0)); return; }
        if (waveFetching) { waveStalled = true; status = T.get("status.wave_next"); return; }
        waveFetching = true;
        waveLoading = true;
        String sid = waveSession;
        yandex.waveMore(sid, waveSeed, waveHistoryIds()).whenComplete((b, e) -> {
            waveFetching = false;
            waveLoading = false;
            if (e != null) { status = T.get("status.wave_no_answer", msg(e)); idle = true; return; }
            synchronized (this) {
                if (!waveActive || !sid.equals(waveSession) || b.tracks().isEmpty()) return;
                waveSession = b.sessionId();
                waveBatch = b.batchId();
                waveQueue.addAll(fresh(b.tracks()));
                status = "";
                playWaveTrack(waveQueue.remove(0));
            }
        });
    }

    private void wavePrev() {
        Track back = waveHistory.poll();
        if (back == null) { yandex.seek(0); return; }
        Track cur = current;
        if (cur != null) waveQueue.add(0, cur);
        playTrack(back);
    }

    /** Клик по треку в списке волны: i = 0 — текущий, дальше — ближайшие. Пропущенные считаются пропущенными. */
    public synchronized void playWaveIndex(int i) {
        if (!waveActive || i <= 0 || i > waveQueue.size()) return;
        Track cur = current;
        if (cur != null) yandex.waveFeedback(waveSession, waveBatch, "skip", cur.id(), yandex.positionMs() / 1000.0);
        for (int k = 0; k < i - 1; k++) waveQueue.remove(0);
        playWaveTrack(waveQueue.remove(0));
    }

    public void waveDislike() {
        Track t = current;
        if (t == null || !waveActive) return;
        yandex.dislike(t).whenComplete((v, e) -> { if (e != null) status = T.get("status.dislike_failed", msg(e)); });
        next();
    }

    private void fillShufflePlan(int n) {
        int last = shufflePlan.isEmpty() ? index : shufflePlan.peekLast();
        while (shufflePlan.size() < 3) {
            int c;
            do { c = rnd.nextInt(n); } while (c == last && n > 1);
            shufflePlan.addLast(c);
            last = c;
        }
    }

    public int loadingPercent() {
        Track t = current;
        return t == null ? -1 : provider(t).loadPercent(t.id());
    }

    /**
     * Пересчитывает, что скачивать наперёд: очередь пользователя, затем то, что играет следом (по порядку, при
     * «случайно» — по заранее выбранному плану, у волны — её очередь). При повторе трека играющий трек просто остаётся
     * в кэше, при повторе списка в кэше держится весь список — повтор не требует новой загрузки.
     */
    public synchronized void refreshPrefetch() {
        Track cur = current;
        if (cur == null || idle) { for (MusicProvider p : providers.values()) p.setPrefetch(null, List.of(), java.util.Set.of()); return; }
        int count = dev.musicplayer.config.PlayerConfig.get().prefetchCount;
        List<Track> ahead = new ArrayList<>();
        java.util.Set<String> keep = new java.util.HashSet<>();
        keep.add(cur.id());

        for (Track t : userQueue) { if (ahead.size() >= count) break; ahead.add(t); }

        if (waveActive) {
            for (Track t : waveQueue) { if (ahead.size() >= count) break; ahead.add(t); }
        } else if (repeat != Repeat.ONE) {
            List<Track> q = queue;
            int n = q.size();
            if (n > 0) {
                if (shuffle && n > 1) {
                    fillShufflePlan(n);
                    for (int idx : shufflePlan) { if (ahead.size() >= count) break; if (idx < n) ahead.add(q.get(idx)); }
                } else {
                    for (int k = 1; k < n && ahead.size() < count; k++) {
                        int ni = index + k;
                        if (ni >= n) { if (repeat == Repeat.ALL) ni %= n; else break; }
                        ahead.add(q.get(ni));
                    }
                }
                if (repeat == Repeat.ALL) for (int k = 0; k < Math.min(n, 40); k++) keep.add(q.get(k).id());
            }
        }
        MusicProvider owner = provider(cur);
        ahead.removeIf(t -> provider(t) != owner);   // очередь может быть смешанной, а качать наперёд умеет не каждая площадка
        owner.setPrefetch(cur.id(), ahead, keep);
    }

    public boolean canLike(Track t) { return t != null && provider(t).supportsLikes(); }

    public boolean isLiked(Track t) { return canLike(t) && provider(t).isLiked(t.id()); }

    public void toggleLike() {
        Track t = current;
        if (!canLike(t)) return;
        MusicProvider p = provider(t);
        boolean like = !p.isLiked(t.id());
        p.setLiked(t, like).whenComplete((v, e) -> {
            if (e != null) status = T.get("status.like_failed", msg(e));
        });
    }

    public void changeVolume(float delta) { setVolume(volume + delta); }

    public void setVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
        SessionState.get().volume = volume;
        SessionState.markDirty();
        applyVolume(true);
    }

    public void toggleMute() {
        SessionState st = SessionState.get();
        if (volume > 0f) { st.preMuteVolume = volume; setVolume(0f); }
        else setVolume(st.preMuteVolume);
    }

    private void applyVolume(boolean force) {
        float eff = volume * GameVolume.factor();
        if (!force && Math.abs(eff - lastApplied) < 0.002f) return;
        lastApplied = eff;
        // Громкость Spotify — это громкость его приложения: не глушим её, когда игра теряет фокус
        for (MusicProvider p : providers.values()) p.setVolume(p == spotify ? volume : eff);
    }

    private static String msg(Throwable e) {
        dev.musicplayer.MusicPlayerMod.LOGGER.warn("Overtone error", e);
        Throwable c = e;
        while (c.getCause() != null && (c instanceof java.util.concurrent.CompletionException || c instanceof java.util.concurrent.ExecutionException)) c = c.getCause();
        return c.getMessage() != null ? c.getMessage() : c.getClass().getSimpleName();
    }
}
