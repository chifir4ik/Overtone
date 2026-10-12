package dev.musicplayer.controller;

import dev.musicplayer.audio.GameVolume;
import dev.musicplayer.config.SessionState;
import dev.musicplayer.lyrics.LyricsService;
import dev.musicplayer.model.LyricLine;
import dev.musicplayer.model.Track;
import dev.musicplayer.provider.YandexMusicProvider;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/** Состояние плеера. Живёт, пока запущена игра: музыка продолжает играть, когда экран закрыт. */
public final class PlayerController {
    public static final PlayerController I = new PlayerController();

    public enum Repeat {
        OFF("выкл"), ALL("все"), ONE("один");
        public final String label;
        Repeat(String l) { label = l; }
    }

    public final YandexMusicProvider yandex = new YandexMusicProvider();

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

    private volatile List<Track> queue = List.of();
    private volatile int index = -1;
    private final List<Track> userQueue = new CopyOnWriteArrayList<>();
    private final ArrayDeque<Integer> history = new ArrayDeque<>();   // для «назад» в режиме шафла
    private final Random rnd = new Random();
    /** Заранее выбранные следующие треки в режиме «случайно»: нужны, чтобы знать, что скачивать наперёд. */
    private final ArrayDeque<Integer> shufflePlan = new ArrayDeque<>();
    private int prefetchTick;
    private float lastApplied = -1f;

    private PlayerController() {
        yandex.onFinished(this::onTrackEnd);
        yandex.onAudioError(m -> { status = "Ошибка звука: " + m; idle = true; });   // idle — чтобы кнопка play пробовала снова
        restoreSession();
        applyVolume(true);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { syncSession(); SessionState.saveNow(); }, "musicplayer-save"));
    }

    private void restoreSession() {
        SessionState st = SessionState.get();
        volume = st.volume;
        shuffle = st.shuffle;
        try { repeat = Repeat.valueOf(st.repeat); } catch (Exception e) { repeat = Repeat.OFF; }
        userQueue.addAll(st.queueTracks());
        List<Track> saved = st.resultTracks();
        Track last = st.currentTrack();
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
        st.setTrack(current);
        st.setResults(results);
        st.setQueue(new ArrayList<>(userQueue));
    }

    private void persist() { syncSession(); SessionState.markDirty(); }

    public void tickPersist() {
        applyVolume(false);
        SessionState.flushIfDirty(2000);
        if (++prefetchTick >= 100) { prefetchTick = 0; refreshPrefetch(); }   // раз в ~5 с: повтор после сбоев, смена настроек
    }

    public void submitToken(String text) {
        status = "Проверяю токен…";
        yandex.submitToken(text).whenComplete((v, e) -> status = e == null ? "" : "Вход не удался: " + msg(e));
    }

    public void loginSaved(dev.musicplayer.config.TokenStore.Account a) {
        if (a == null) return;
        status = "Вхожу как " + a.displayName() + "…";
        yandex.submitToken(a.token()).whenComplete((v, e) -> status = e == null ? "" : "Вход не удался: " + msg(e));
    }

    public synchronized void logout() {
        yandex.logout();
        results = List.of();
        resultsVersion++;
        current = null;
        lyrics = List.of();
        lyricsLoading = false;
        queue = List.of();
        index = -1;
        userQueue.clear();
        history.clear();
        stopWave();
        idle = true;
        status = "";
        persist();
    }

    public void search(String q) {
        if (q == null || q.isBlank()) return;
        waveView = false;
        status = "Ищу…";
        yandex.search(q.trim()).whenComplete((r, e) -> {
            if (e != null) { status = "Ошибка поиска: " + msg(e); return; }
            setResults(r);
            status = r.isEmpty() ? "Ничего не найдено" : "";
        });
    }

    public void loadLiked() {
        waveView = false;
        status = "Загружаю любимые треки…";
        yandex.likedTracks().whenComplete((r, e) -> {
            if (e != null) { status = "Ошибка: " + msg(e); return; }
            setResults(r);
            status = r.isEmpty() ? "В «Любимом» пока пусто" : "";
        });
    }

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
        status = "Загружаю трек…";
        yandex.play(t).whenComplete((v, e) -> { if (current == t) status = e == null ? "" : "Не удалось воспроизвести: " + msg(e); });
        refreshPrefetch();
        LyricsService.fetch(t, yandex).thenAccept(l -> { if (current == t) { lyrics = l; lyricsLoading = false; } });
    }

    private void onTrackEnd() {
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
        if (!idle && yandex.positionMs() > 3000) { yandex.seek(0); return; }
        if (waveActive) { wavePrev(); return; }
        shufflePlan.clear();
        if (shuffle && !history.isEmpty()) index = history.pop();
        else index = (index - 1 + q.size()) % q.size();
        playTrack(q.get(index));
    }

    public void togglePause() {
        if (current == null) return;
        if (idle) { playTrack(current); return; }   // восстановленный трек или конец очереди
        if (yandex.isPlaying()) yandex.pause(); else yandex.resume();
    }

    public void seekBy(long deltaMs) {
        if (current == null || idle) return;
        seekTo(yandex.positionMs() + deltaMs);
    }

    public void seekTo(long ms) {
        Track t = current;
        if (t == null || idle) return;
        long dur = t.durationMs() > 0 ? t.durationMs() : Long.MAX_VALUE;
        yandex.seek(Math.max(0, Math.min(dur - 500, ms)));
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

    public void clearQueue() { userQueue.clear(); persist(); refreshPrefetch(); }

    public synchronized void startWave() {
        waveView = true;
        if (waveActive || waveLoading) return;
        waveLoading = true;
        status = "Запускаю «Мою волну»…";
        yandex.waveStart().whenComplete((b, e) -> {
            waveLoading = false;
            if (e != null) { status = "Волна не запустилась: " + msg(e); waveView = false; return; }
            if (b.tracks().isEmpty()) { status = "Волна пока ничего не предложила"; waveView = false; return; }
            synchronized (this) {
                userQueue.clear();
                waveSession = b.sessionId();
                waveBatch = b.batchId();
                waveQueue.clear();
                waveHistory.clear();
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

    private void playWaveTrack(Track t) {
        playTrack(t);
        yandex.waveFeedback(waveSession, waveBatch, "trackStarted", t.id(), 0);
        topUpWave(t);
    }

    private void topUpWave(Track justStarted) {
        if (!waveActive || waveFetching || waveQueue.size() >= 4) return;
        waveFetching = true;
        String sid = waveSession;
        Track last = waveQueue.isEmpty() ? justStarted : waveQueue.get(waveQueue.size() - 1);
        yandex.waveMore(sid, last.id()).whenComplete((b, e) -> {
            waveFetching = false;
            if (e != null) { status = "Волна не ответила: " + msg(e); waveStalled = false; idle = true; return; }
            if (!waveActive || !sid.equals(waveSession)) return;
            waveSession = b.sessionId();           // при сбое сервер мог открыть новую сессию
            waveBatch = b.batchId();
            for (Track t : b.tracks()) if (t.id() != null && !t.id().equals(last.id())) waveQueue.add(t);
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
        if (waveFetching) { waveStalled = true; status = "Подбираю следующий трек…"; return; }
        waveFetching = true;
        waveLoading = true;
        String sid = waveSession;
        Track last = prev;
        yandex.waveMore(sid, last == null ? "" : last.id()).whenComplete((b, e) -> {
            waveFetching = false;
            waveLoading = false;
            if (e != null) { status = "Волна не ответила: " + msg(e); idle = true; return; }
            synchronized (this) {
                if (!waveActive || !sid.equals(waveSession) || b.tracks().isEmpty()) return;
                waveSession = b.sessionId();
                waveBatch = b.batchId();
                waveQueue.addAll(b.tracks());
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
        yandex.dislike(t).whenComplete((v, e) -> { if (e != null) status = "Дизлайк не удался: " + msg(e); });
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
        return t == null ? -1 : yandex.loadPercent(t.id());
    }

    /**
     * Пересчитывает, что скачивать наперёд: очередь пользователя, затем то, что играет следом (по порядку, при
     * «случайно» — по заранее выбранному плану, у волны — её очередь). При повторе трека играющий трек просто остаётся
     * в кэше, при повторе списка в кэше держится весь список — повтор не требует новой загрузки.
     */
    public synchronized void refreshPrefetch() {
        Track cur = current;
        if (cur == null || idle) { yandex.setPrefetch(null, List.of(), java.util.Set.of()); return; }
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
        yandex.setPrefetch(cur.id(), ahead, keep);
    }

    public boolean isLiked(Track t) { return t != null && yandex.isLiked(t.id()); }

    public void toggleLike() {
        Track t = current;
        if (t == null) return;
        boolean like = !yandex.isLiked(t.id());
        yandex.setLiked(t, like).whenComplete((v, e) -> {
            if (e != null) status = "Лайк не удался: " + msg(e);
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
        yandex.setVolume(eff);
    }

    private static String msg(Throwable e) {
        dev.musicplayer.MusicPlayerMod.LOGGER.warn("Overtone error", e);
        Throwable c = e;
        while (c.getCause() != null && (c instanceof java.util.concurrent.CompletionException || c instanceof java.util.concurrent.ExecutionException)) c = c.getCause();
        return c.getMessage() != null ? c.getMessage() : c.getClass().getSimpleName();
    }
}
