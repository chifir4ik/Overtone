package dev.musicplayer.share;

import dev.musicplayer.MusicPlayerMod;
import dev.musicplayer.compat.Game;
import dev.musicplayer.config.PlayerConfig;
import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.input.Hotkeys;
import dev.musicplayer.model.Platform;
import dev.musicplayer.model.Track;
import dev.musicplayer.provider.MusicProvider;
import dev.musicplayer.util.T;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * «Наушник» на стороне игрока: кто вокруг с Overtone, приглашения, раздача своего трека и прослушивание чужого.
 * Всё вызывается из потока игры.
 */
public final class ShareClient {
    public static final ShareClient I = new ShareClient();

    /** Сколько держать Shift + ПКМ на игроке, чтобы отправить приглашение. */
    public static final long HOLD_MS = 650;
    /** Кольцо появляется не сразу: случайный клик не должен мигать им. */
    public static final long HOLD_DELAY_MS = 120;
    /** Свой луч, а не прицел игры: тот ловит игроков только на расстоянии удара. */
    public static final double HOLD_RANGE = 6;
    public static final long INVITE_TTL_MS = 30_000;
    private static final long DRIFT_MS = 1500;

    public record Invite(UUID host, String name, String playing, long at) {}

    public record Aim(UUID id, String name) {}

    /** Сообщения для HUD: показываются плашкой сверху. */
    public record Note(String text, boolean good, long at) {}

    private Object connection;
    private boolean helloSent, available;
    /** Игроки с Overtone и цвет их значка. */
    private final Map<UUID, Integer> roster = new ConcurrentHashMap<>();
    private boolean sentVisible;
    private int sentRgb = -1;

    private volatile Invite invite;
    private volatile List<ShareProto.Listener> listeners = List.of();
    private final Map<UUID, Long> sentInvites = new ConcurrentHashMap<>();

    private volatile UUID host, joining;
    private volatile String hostName = "";
    private ShareProto.State remote = ShareProto.State.NONE;
    private long remoteAt;
    private String playingKey;
    /** Трек слушателя хотя бы раз заиграл: до этого его нельзя ни ставить на паузу, ни мотать. */
    private boolean started;
    private volatile boolean localPause;
    private long lastSeek;

    private String sentKey;
    private boolean sentPlaying;
    private long sentPos, sentAt;
    private boolean forceSend;

    private volatile Aim aim;
    private long aimSince, holdSince;
    private boolean holdDone, needRelease;
    private volatile long sentPulseAt, blockedAt;
    private volatile String sentPulseName = "", blockedReason = "";

    private final List<Note> notes = new ArrayList<>();

    private ShareClient() {
        ShareNet.onServerMessage(this::onMessage);
    }

    // --- что читает интерфейс ---

    /** На сервере есть плагин или Overtone: «наушник» работает. */
    public boolean available() { return available; }

    public boolean hasOvertone(UUID id) { return available && roster.containsKey(id); }

    /** Цвет, который выбрал себе сам игрок: у всех на сервере его значок одинаковый. */
    public int badgeColor(UUID id) {
        Integer c = roster.get(id);
        return c == null ? 0xFFFFFF : c;
    }

    /** Кроме нас на сервере есть кто-то с Overtone: иначе кнопке «Поделиться» нечего предложить. */
    public boolean anyoneElse() {
        Minecraft mc = Minecraft.getInstance();
        UUID me = mc.player == null ? null : mc.player.getUUID();
        for (UUID id : roster.keySet()) if (!id.equals(me)) return true;
        return false;
    }

    public boolean listening() { return host != null; }

    public String hostName() { return hostName; }

    public boolean localPaused() { return localPause; }

    public Invite invite() { return invite; }

    public List<ShareProto.Listener> listeners() { return listeners; }

    public boolean invitedRecently(UUID id) {
        Long at = sentInvites.get(id);
        return at != null && System.currentTimeMillis() - at < INVITE_TTL_MS;
    }

    public boolean isListener(UUID id) {
        for (ShareProto.Listener l : listeners) if (l.id().equals(id)) return true;
        return false;
    }

    public Aim aim() { return aim; }

    /** 0 — кольца нет, 1 — приглашение отправлено. */
    public float holdProgress() {
        if (holdSince == 0 || needRelease) return 0f;
        long held = System.currentTimeMillis() - holdSince - HOLD_DELAY_MS;
        return held <= 0 ? 0f : Math.min(1f, held / (float) (HOLD_MS - HOLD_DELAY_MS));
    }

    public long aimedFor() { return aim == null ? 0 : System.currentTimeMillis() - aimSince; }

    public long sentPulseAt() { return sentPulseAt; }

    public String sentPulseName() { return sentPulseName; }

    /** Когда последний раз зажали Shift + ПКМ, а поделиться было нечем, и почему. */
    public long blockedAt() { return blockedAt; }

    public String blockedReason() { return blockedReason; }

    /** null — можно делиться; иначе ключ подсказки, почему нельзя. */
    public String whyCantShare() {
        if (!available) return "share.no_server";
        if (listening()) return "share.denied.5";
        PlayerController ctl = PlayerController.I;
        return ctl.current == null || ctl.idle ? "share.need_track" : null;
    }

    /** Есть что раздавать: без играющего трека приглашать некуда. */
    public boolean canShare() { return whyCantShare() == null; }

    public synchronized List<Note> notes() {
        long now = System.currentTimeMillis();
        notes.removeIf(n -> now - n.at() > 4200);
        return new ArrayList<>(notes);
    }

    private synchronized void note(String text, boolean good) {
        notes.add(new Note(text, good, System.currentTimeMillis()));
        while (notes.size() > 3) notes.remove(0);
    }

    /** Ближайшие игроки с Overtone для окна «Поделиться». */
    public List<Aim> nearby() {
        Minecraft mc = Minecraft.getInstance();
        List<Aim> out = new ArrayList<>();
        if (mc.player == null || mc.level == null || !available) return out;
        for (Player p : mc.level.players()) {
            if (p == mc.player || !roster.containsKey(p.getUUID())) continue;
            if (p.distanceTo(mc.player) > ShareRelay.INVITE_RANGE) continue;
            out.add(new Aim(p.getUUID(), p.getName().getString()));
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    // --- действия игрока ---

    public void sendInvite(UUID target, String name) {
        if (!canShare() || isListener(target)) return;
        sentInvites.put(target, System.currentTimeMillis());
        ShareNet.send(ShareProto.invite(target));
        sentPulseAt = System.currentTimeMillis();
        sentPulseName = name;
    }

    public void answer(boolean accept) {
        Invite inv = invite;
        if (inv == null) return;
        invite = null;
        ShareNet.send(ShareProto.answer(inv.host(), accept));
        if (accept) joining = inv.host();
    }

    public void leave() {
        if (host == null && joining == null) return;
        ShareNet.send(ShareProto.leave());
        stopListening();
    }

    public void kick(UUID listener) { ShareNet.send(ShareProto.kick(listener)); }

    public void stopSharing() { kick(ShareProto.NOBODY); }

    /** Пауза у слушателя глушит только его самого; плей возвращает туда, где сейчас раздающий. */
    public void toggleLocalPause() {
        if (host == null) return;
        localPause = !localPause;
        MusicProvider engine = PlayerController.I.engine();
        if (localPause) { if (engine.isPlaying()) engine.pause(); }
        else lastSeek = 0;
    }

    // --- такт игры ---

    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        Object conn = mc.getConnection();
        if (conn != connection) {
            reset();
            connection = conn;
        }
        if (conn == null || mc.player == null) return;
        // канал сервер объявляет чуть позже входа, поэтому пробуем, пока не получится
        PlayerConfig pc = PlayerConfig.get();
        int rgb = pc.accentRgb() & 0xFFFFFF;
        if (!helloSent && ShareNet.canSend()) {
            helloSent = true;
            sayHello(pc.shareVisible, rgb);
        }
        if (!available) return;
        // сменил цвет плеера или скрылся в настройках: остальные сразу видят новый значок
        if (pc.shareVisible != sentVisible || rgb != sentRgb) sayHello(pc.shareVisible, rgb);

        Invite inv = invite;
        if (inv != null && System.currentTimeMillis() - inv.at() > INVITE_TTL_MS) invite = null;
        tickAim(mc);
        if (!listeners.isEmpty()) tickHost();
        if (host != null) tickListen();
    }

    private void sayHello(boolean visible, int rgb) {
        sentVisible = visible;
        sentRgb = rgb;
        ShareNet.send(ShareProto.hello(visible, rgb));
    }

    private void tickAim(Minecraft mc) {
        Player target = Game.screen() == null ? lookedAt(mc) : null;
        Aim now = target == null ? null : new Aim(target.getUUID(), target.getName().getString());
        Aim was = aim;
        if (now == null || was == null || !now.id().equals(was.id())) {
            aim = now;
            aimSince = System.currentTimeMillis();
            holdSince = 0;
            holdDone = false;
        }
        boolean down = Hotkeys.shareHoldDown(mc);
        if (!down) needRelease = false;
        if (now != null && down && !needRelease && !canShare()) {
            // зажали, а делиться нечем: говорим почему, а не молчим
            needRelease = true;
            blockedAt = System.currentTimeMillis();
            blockedReason = T.get(whyCantShare());
        }
        boolean holding = now != null && down && !mc.player.isUsingItem() && canShare() && !isListener(now.id()) && !needRelease;
        if (!holding) { holdSince = 0; holdDone = false; return; }
        if (holdSince == 0) holdSince = System.currentTimeMillis();
        if (!holdDone && holdProgress() >= 1f) {
            holdDone = true;
            needRelease = true;
            sendInvite(now.id(), now.name());
        }
    }

    /** Ближайший игрок с Overtone на луче взгляда, если между нами нет блока. */
    private Player lookedAt(Minecraft mc) {
        if (mc.player == null || mc.level == null) return null;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 end = eye.add(mc.player.getViewVector(1f).scale(HOLD_RANGE));
        double wall = Double.MAX_VALUE;
        HitResult hr = mc.hitResult;
        if (hr != null && hr.getType() == HitResult.Type.BLOCK) wall = eye.distanceTo(hr.getLocation());
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player p : mc.level.players()) {
            if (p == mc.player || !roster.containsKey(p.getUUID()) || p.distanceTo(mc.player) > HOLD_RANGE + 2) continue;
            AABB box = p.getBoundingBox().inflate(0.3);
            double d;
            if (box.contains(eye)) d = 0;
            else {
                Optional<Vec3> hit = box.clip(eye, end);
                if (hit.isEmpty()) continue;
                d = eye.distanceTo(hit.get());
            }
            if (d < wall && d < bestDist) { best = p; bestDist = d; }
        }
        return best;
    }

    private ShareProto.State myState() {
        PlayerController ctl = PlayerController.I;
        Track t = ctl.current;
        if (t == null || ctl.idle) return ShareProto.State.NONE;
        Platform p = Platform.byId(t.provider());
        MusicProvider engine = ctl.engine();
        boolean local = p == Platform.LOCAL;
        // у своих файлов ID — это место на диске, а обложка — локальный файл: наружу не отдаём ни то, ни другое
        return new ShareProto.State(p.id, local ? "" : nz(t.id()), nz(t.title()), nz(t.artist()), nz(t.album()),
                t.durationMs(), local ? "" : nz(t.coverUrl()), engine.positionMs(), engine.isPlaying());
    }

    private void tickHost() {
        ShareProto.State s = myState();
        long now = System.currentTimeMillis();
        long expected = sentPos + (sentPlaying ? now - sentAt : 0);
        boolean changed = !s.key().equals(sentKey) || s.playing() != sentPlaying;
        boolean jumped = s.playing() && Math.abs(s.positionMs() - expected) > DRIFT_MS;
        if (!forceSend && !changed && !jumped && now - sentAt < 4000) return;
        forceSend = false;
        sentKey = s.key();
        sentPlaying = s.playing();
        sentPos = s.positionMs();
        sentAt = now;
        ShareNet.send(ShareProto.state(s));
    }

    private void tickListen() {
        PlayerController ctl = PlayerController.I;
        ShareProto.State r = remote;
        if (r.empty() || ctl.current == null || ctl.idle) return;
        MusicProvider engine = ctl.engine();
        if (engine.isPlaying()) started = true;
        if (!started) return;

        long now = System.currentTimeMillis();
        boolean shouldPlay = r.playing() && !localPause;
        if (!shouldPlay) {
            if (engine.isPlaying()) engine.pause();
            return;
        }
        long expected = r.positionMs() + (now - remoteAt);
        if (r.durationMs() > 0) expected = Math.min(expected, r.durationMs() - 500);
        if (!engine.isPlaying()) {
            engine.resume();
            engine.seek(Math.max(0, expected));
            lastSeek = now;
            return;
        }
        if (Math.abs(engine.positionMs() - expected) > DRIFT_MS && now - lastSeek > 3000) {
            engine.seek(Math.max(0, expected));
            lastSeek = now;
        }
    }

    // --- сообщения сервера ---

    private void onMessage(byte[] data) {
        Minecraft.getInstance().execute(() -> handle(ShareProto.read(data)));
    }

    private void handle(ShareProto.Msg m) {
        if (m == null) return;
        switch (m.type) {
            case ShareProto.ROSTER -> {
                available = true;
                roster.clear();
                for (ShareProto.Badge b : m.badges) roster.put(b.id(), b.rgb());
            }
            case ShareProto.ROSTER_ADD -> roster.put(m.who, m.rgb);
            case ShareProto.ROSTER_REMOVE -> roster.remove(m.who);
            case ShareProto.INVITED -> {
                if (m.who.equals(host)) return;
                invite = new Invite(m.who, m.name, m.state.title(), System.currentTimeMillis());
            }
            case ShareProto.ANSWERED -> {
                sentInvites.remove(m.who);
                note(T.get(m.flag ? "share.accepted" : "share.declined", m.name), m.flag);
            }
            case ShareProto.DENIED -> {
                sentInvites.remove(m.who);
                note(T.get("share.denied." + m.reason), false);
            }
            case ShareProto.LISTENERS -> {
                boolean grew = m.listeners.size() > listeners.size();
                listeners = List.copyOf(m.listeners);
                // новичку сразу нужна точная позиция, а не та, что сервер запомнил несколько секунд назад
                if (grew) forceSend = true;
            }
            case ShareProto.HOST_STATE -> onHostState(m);
            case ShareProto.ENDED -> {
                if (!m.who.equals(host) && !m.who.equals(joining)) return;
                String name = hostName;
                stopListening();
                note(T.get("share.ended", name), false);
            }
            default -> { }
        }
    }

    private void onHostState(ShareProto.Msg m) {
        if (m.who.equals(joining)) {
            host = joining;
            joining = null;
            hostName = m.name;
            localPause = false;
            playingKey = null;
            PlayerController.I.enterShared();
            note(T.get("share.joined", m.name), true);
        }
        if (!m.who.equals(host)) return;
        remote = m.state;
        remoteAt = System.currentTimeMillis();
        if (m.state.empty()) return;
        String key = m.state.key();
        if (key.equals(playingKey)) return;
        playingKey = key;
        started = false;
        lastSeek = 0;
        ShareProto.State s = m.state;
        ShareResolver.resolve(s).whenComplete((track, err) -> Minecraft.getInstance().execute(() -> {
            if (host == null || !key.equals(playingKey)) return;
            Track shown = ShareResolver.shown(s);
            if (err != null) MusicPlayerMod.LOGGER.debug("Shared track not resolved: {}", err.getMessage());
            PlayerController.I.playShared(shown, err == null ? track : null);
        }));
    }

    private void stopListening() {
        boolean was = host != null;
        host = null;
        joining = null;
        remote = ShareProto.State.NONE;
        playingKey = null;
        localPause = false;
        if (was) PlayerController.I.leaveShared();
    }

    private void reset() {
        if (host != null) stopListening();
        helloSent = false;
        available = false;
        sentRgb = -1;
        roster.clear();
        invite = null;
        listeners = List.of();
        sentInvites.clear();
        aim = null;
        sentKey = null;
        forceSend = false;
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
