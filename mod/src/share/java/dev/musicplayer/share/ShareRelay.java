package dev.musicplayer.share;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Серверная часть «наушника»: пересылает сообщения между игроками с Overtone. Одна и та же для плагина Paper и для
 * серверов Fabric/NeoForge (и одиночной игры по LAN). Не знает про Minecraft: игроков ей даёт платформа через {@link Peer}.
 * <p>
 * Вызывать только из основного потока сервера.
 */
public final class ShareRelay {
    public interface Peer {
        UUID id();
        String name();
        /** Любой объект, одинаковый для игроков в одном мире. */
        Object world();
        double x();
        double y();
        double z();
        void send(byte[] data);
    }

    /** Дальше приглашать нельзя: «наушник» передают тому, кто рядом. */
    public static final double INVITE_RANGE = 16;
    private static final long INVITE_TTL_MS = 30_000;
    private static final long INVITE_GAP_MS = 1_500;
    private static final long DECLINE_COOLDOWN_MS = 30_000;
    private static final int STATES_PER_SECOND = 8;

    private static final class Member {
        boolean visible;
        int rgb;
        long lastInvite;
        long stateWindow;
        int statesInWindow;
        byte[] lastState;
    }

    private final Function<UUID, Peer> lookup;
    private final Map<UUID, Member> members = new HashMap<>();
    private final Map<UUID, UUID> hostOf = new HashMap<>();
    private final Map<UUID, LinkedHashSet<UUID>> listeners = new HashMap<>();
    /** host + target → когда протухает приглашение или кончается пауза после отказа. */
    private final Map<Pair, Long> pending = new HashMap<>(), cooldown = new HashMap<>();

    private record Pair(UUID host, UUID target) {}

    public ShareRelay(Function<UUID, Peer> lookup) { this.lookup = lookup; }

    public void receive(Peer from, byte[] data) {
        if (data == null || data.length > ShareProto.MAX_BYTES) return;
        ShareProto.Msg m = ShareProto.read(data);
        if (m == null) return;
        UUID me = from.id();
        if (m.type == ShareProto.HELLO) { hello(from, m.flag, m.rgb); return; }
        Member mine = members.get(me);
        if (mine == null) return;   // без приветствия ничего не принимаем
        switch (m.type) {
            case ShareProto.INVITE -> invite(from, mine, m.who);
            case ShareProto.ANSWER -> answer(from, m.who, m.flag);
            case ShareProto.STATE -> state(from, mine, m.state);
            case ShareProto.LEAVE -> leave(me, true);
            case ShareProto.KICK -> kick(me, m.who);
            default -> { }
        }
    }

    /** Игрок вышел с сервера. */
    public void quit(UUID id) {
        Member m = members.remove(id);
        if (m == null) return;
        leave(id, true);
        stopHosting(id);
        pending.keySet().removeIf(p -> p.host().equals(id) || p.target().equals(id));
        cooldown.keySet().removeIf(p -> p.host().equals(id) || p.target().equals(id));
        if (m.visible) broadcast(ShareProto.rosterRemove(id), id);
    }

    public int memberCount() { return members.size(); }

    /** Первое приветствие или повторное, когда игрок сменил цвет или скрылся. */
    private void hello(Peer from, boolean visible, int rgb) {
        UUID me = from.id();
        Member old = members.get(me);
        Member m = old != null ? old : new Member();
        boolean wasVisible = old != null && old.visible;
        boolean recolored = old != null && old.rgb != rgb;
        m.visible = visible;
        m.rgb = rgb;
        members.put(me, m);

        if (old == null) {
            List<ShareProto.Badge> roster = new ArrayList<>();
            for (Map.Entry<UUID, Member> e : members.entrySet())
                if (e.getValue().visible) roster.add(new ShareProto.Badge(e.getKey(), e.getValue().rgb));
            from.send(ShareProto.roster(roster));
        } else if (visible != wasVisible || recolored) {
            from.send(visible ? ShareProto.rosterAdd(me, rgb) : ShareProto.rosterRemove(me));
        }
        if (visible && (!wasVisible || recolored)) broadcast(ShareProto.rosterAdd(me, rgb), me);
        if (!visible && wasVisible) broadcast(ShareProto.rosterRemove(me), me);
    }

    private void invite(Peer from, Member mine, UUID targetId) {
        UUID me = from.id();
        long now = System.currentTimeMillis();
        if (now - mine.lastInvite < INVITE_GAP_MS) return;
        mine.lastInvite = now;
        if (pending.size() > 256) pending.values().removeIf(t -> t < now);
        if (cooldown.size() > 256) cooldown.values().removeIf(t -> t < now);

        Peer target = lookup.apply(targetId);
        Member theirs = members.get(targetId);
        // скрытого игрока не выдаём: для приглашающего он выглядит как игрок без мода
        if (target == null || theirs == null || !theirs.visible || targetId.equals(me)) { deny(from, targetId, ShareProto.DENY_UNAVAILABLE); return; }
        if (hostOf.containsKey(me)) { deny(from, targetId, ShareProto.DENY_LISTENING); return; }
        if (me.equals(hostOf.get(targetId))) { deny(from, targetId, ShareProto.DENY_ALREADY); return; }
        if (listenersOf(me).size() >= ShareProto.MAX_LISTENERS) { deny(from, targetId, ShareProto.DENY_FULL); return; }
        if (!near(from, target)) { deny(from, targetId, ShareProto.DENY_FAR); return; }
        Pair p = new Pair(me, targetId);
        Long until = cooldown.get(p);
        if (until != null && until > now) { deny(from, targetId, ShareProto.DENY_COOLDOWN); return; }
        Long open = pending.get(p);
        if (open != null && open > now) return;   // уже висит на экране у получателя

        pending.put(p, now + INVITE_TTL_MS);
        String playing = mine.lastState == null ? "" : ShareProto.describe(stateOf(mine.lastState));
        target.send(ShareProto.invited(me, from.name(), playing));
    }

    private void answer(Peer from, UUID hostId, boolean accept) {
        UUID me = from.id();
        Pair p = new Pair(hostId, me);
        Long until = pending.remove(p);
        if (until == null || until < System.currentTimeMillis()) return;
        Peer host = lookup.apply(hostId);
        if (host == null || !members.containsKey(hostId)) return;
        if (!accept) {
            cooldown.put(p, System.currentTimeMillis() + DECLINE_COOLDOWN_MS);
            host.send(ShareProto.answered(me, from.name(), false));
            return;
        }
        if (hostOf.containsKey(hostId)) return;   // пока ждали ответа, раздающий сам стал слушателем
        LinkedHashSet<UUID> set = listeners.computeIfAbsent(hostId, k -> new LinkedHashSet<>());
        if (set.size() >= ShareProto.MAX_LISTENERS) return;

        leave(me, true);
        stopHosting(me);   // слушать и раздавать одновременно нельзя: получилась бы цепочка
        set.add(me);
        hostOf.put(me, hostId);
        host.send(ShareProto.answered(me, from.name(), true));
        sendListeners(hostId);
        byte[] last = members.get(hostId).lastState;
        from.send(ShareProto.hostState(hostId, host.name(), last == null ? ShareProto.State.NONE : stateOf(last)));
    }

    private void state(Peer from, Member mine, ShareProto.State s) {
        long now = System.currentTimeMillis();
        if (now - mine.stateWindow >= 1000) { mine.stateWindow = now; mine.statesInWindow = 0; }
        if (++mine.statesInWindow > STATES_PER_SECOND) return;
        byte[] out = ShareProto.hostState(from.id(), from.name(), s);
        mine.lastState = out;
        for (UUID l : listenersOf(from.id())) {
            Peer p = lookup.apply(l);
            if (p != null) p.send(out);
        }
    }

    /** Слушатель отключается от того, кого слушал. */
    private void leave(UUID listener, boolean notifyHost) {
        UUID host = hostOf.remove(listener);
        if (host == null) return;
        Set<UUID> set = listeners.get(host);
        if (set != null) {
            set.remove(listener);
            if (set.isEmpty()) listeners.remove(host);
        }
        if (notifyHost) sendListeners(host);
    }

    private void kick(UUID host, UUID listener) {
        List<UUID> gone = new ArrayList<>();
        if (ShareProto.NOBODY.equals(listener)) gone.addAll(listenersOf(host));
        else if (host.equals(hostOf.get(listener))) gone.add(listener);
        for (UUID l : gone) {
            leave(l, false);
            Peer p = lookup.apply(l);
            if (p != null) p.send(ShareProto.ended(host));
        }
        if (!gone.isEmpty()) sendListeners(host);
    }

    private void stopHosting(UUID host) {
        for (UUID l : new ArrayList<>(listenersOf(host))) {
            hostOf.remove(l);
            Peer p = lookup.apply(l);
            if (p != null) p.send(ShareProto.ended(host));
        }
        if (listeners.remove(host) != null) {
            Peer p = lookup.apply(host);
            if (p != null) p.send(ShareProto.listeners(List.of()));
        }
    }

    private void sendListeners(UUID host) {
        Peer h = lookup.apply(host);
        if (h == null) return;
        List<ShareProto.Listener> list = new ArrayList<>();
        for (UUID l : listenersOf(host)) {
            Peer p = lookup.apply(l);
            list.add(new ShareProto.Listener(l, p == null ? "?" : p.name()));
        }
        h.send(ShareProto.listeners(list));
    }

    private Set<UUID> listenersOf(UUID host) {
        Set<UUID> s = listeners.get(host);
        return s == null ? Set.of() : s;
    }

    private void deny(Peer to, UUID target, int reason) { to.send(ShareProto.denied(target, reason)); }

    private void broadcast(byte[] data, UUID except) {
        for (UUID id : members.keySet()) {
            if (id.equals(except)) continue;
            Peer p = lookup.apply(id);
            if (p != null) p.send(data);
        }
    }

    private static boolean near(Peer a, Peer b) {
        if (!java.util.Objects.equals(a.world(), b.world())) return false;
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz <= INVITE_RANGE * INVITE_RANGE;
    }

    private static ShareProto.State stateOf(byte[] hostState) {
        ShareProto.Msg m = ShareProto.read(hostState);
        return m == null ? ShareProto.State.NONE : m.state;
    }
}
