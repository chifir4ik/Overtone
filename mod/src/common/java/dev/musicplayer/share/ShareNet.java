package dev.musicplayer.share;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Сюда загрузчик подключает свою отправку пакетов. Без клиентских классов: грузится и на выделенном сервере. */
public final class ShareNet {
    private ShareNet() {}

    private static volatile Consumer<byte[]> toServer = data -> { };
    private static volatile BooleanSupplier serverListens = () -> false;
    private static volatile Consumer<byte[]> fromServer = data -> { };

    public static void connectClient(Consumer<byte[]> send, BooleanSupplier canSend) {
        toServer = send;
        serverListens = canSend;
    }

    /** Клиентская часть подписывается сама: на сервере её нет. */
    public static void onServerMessage(Consumer<byte[]> handler) { fromServer = handler; }

    /** Сервер объявил канал: значит, на нём стоит плагин или Overtone. */
    public static boolean canSend() {
        try {
            return serverListens.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static void send(byte[] data) {
        if (canSend()) toServer.accept(data);
    }

    public static void received(byte[] data) { fromServer.accept(data); }
}
