package dev.musicplayer.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.musicplayer.MusicPlayerMod;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/**
 * Запасной путь, когда системный DNS не может разрешить имя сайта (или подсовывает чужой адрес), хотя в браузере
 * сайт открывается: браузеры ходят в DNS через HTTPS, а Java нет. Адрес спрашивается у Cloudflare и Google по их
 * IP-адресам (им не нужен DNS), после чего соединение идёт на этот адрес с настоящим именем сайта в TLS, поэтому
 * сертификат проверяется как обычно.
 */
final class DohConnector {
    private static final String[] SERVERS = {
            "https://1.1.1.1/dns-query?name=%s&type=A",
            "https://8.8.8.8/resolve?name=%s&type=A",
            "https://1.0.0.1/dns-query?name=%s&type=A",
            "https://8.8.4.4/resolve?name=%s&type=A",
    };
    private static final long MIN_TTL_MS = 5 * 60_000;
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int MAX_BODY_BYTES = 24 << 20;

    private static final HttpClient DNS = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    private record Cached(List<InetAddress> addresses, long until) {}

    private DohConnector() {}

    /** Выполняет запрос по адресу, найденному через DoH. Запрос уже проверен вызывающим на допустимый домен. */
    static HttpResponse<InputStream> send(HttpRequest req, String body) throws IOException {
        URI uri = req.uri();
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 443;
        List<InetAddress> addresses = resolve(host);
        if (addresses.isEmpty()) throw new IOException("DoH: no address for " + host);

        IOException last = null;
        for (InetAddress a : addresses) {
            try {
                return request(req, body, host, port, a);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    private static List<InetAddress> resolve(String host) throws IOException {
        Cached c = CACHE.get(host);
        if (c != null && c.until > System.currentTimeMillis()) return c.addresses;

        for (String server : SERVERS) {
            try {
                HttpRequest r = HttpRequest.newBuilder(URI.create(String.format(server, host)))
                        .timeout(Duration.ofSeconds(5)).header("Accept", "application/dns-json").GET().build();
                HttpResponse<String> resp = DNS.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() != 200) continue;
                List<InetAddress> found = new ArrayList<>();
                long ttl = MIN_TTL_MS;
                JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
                JsonArray answers = o.has("Answer") && o.get("Answer").isJsonArray() ? o.getAsJsonArray("Answer") : new JsonArray();
                for (JsonElement e : answers) {
                    JsonObject ans = e.getAsJsonObject();
                    if (ans.get("type").getAsInt() != 1) continue;   // только A-записи
                    found.add(InetAddress.getByName(ans.get("data").getAsString()));
                    ttl = Math.max(ttl, ans.get("TTL").getAsLong() * 1000);
                }
                if (found.isEmpty()) continue;
                CACHE.put(host, new Cached(found, System.currentTimeMillis() + Math.min(ttl, 30 * 60_000)));
                MusicPlayerMod.LOGGER.info("{}: system DNS failed, resolved over HTTPS to {}", host, found.get(0).getHostAddress());
                return found;
            } catch (IOException | RuntimeException e) {
                // пробуем следующий сервер
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
        }
        return List.of();
    }

    private static HttpResponse<InputStream> request(HttpRequest req, String body, String host, int port, InetAddress address)
            throws IOException {
        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(address, port), 8000);
            raw.setSoTimeout(20_000);
            SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
            SSLParameters p = ssl.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");
            p.setServerNames(List.of(new SNIHostName(host)));
            ssl.setSSLParameters(p);
            ssl.startHandshake();

            String path = req.uri().getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            if (req.uri().getRawQuery() != null) path += "?" + req.uri().getRawQuery();
            byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);

            StringBuilder head = new StringBuilder();
            head.append(req.method()).append(' ').append(path).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(host).append("\r\n");
            head.append("Connection: close\r\nAccept-Encoding: identity\r\n");
            req.headers().map().forEach((k, vs) -> {
                String lk = k.toLowerCase(Locale.ROOT);
                if (lk.equals("host") || lk.equals("connection") || lk.equals("content-length") || lk.equals("accept-encoding")) return;
                for (String v : vs) head.append(k).append(": ").append(v).append("\r\n");
            });
            if (payload.length > 0 || !req.method().equals("GET")) head.append("Content-Length: ").append(payload.length).append("\r\n");
            head.append("\r\n");
            ssl.getOutputStream().write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            ssl.getOutputStream().write(payload);
            ssl.getOutputStream().flush();

            return readResponse(req, ssl);
        } catch (IOException | RuntimeException e) {
            try { raw.close(); } catch (IOException ignored) { }
            throw e instanceof IOException io ? io : new IOException(e);
        }
    }

    private static HttpResponse<InputStream> readResponse(HttpRequest req, SSLSocket ssl) throws IOException {
        InputStream in = ssl.getInputStream();
        String status = line(in);
        String[] parts = status.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) throw new IOException("bad response: " + status);
        int code = Integer.parseInt(parts[1].trim());

        Map<String, List<String>> headers = new LinkedHashMap<>();
        int total = 0;
        for (String l = line(in); !l.isEmpty(); l = line(in)) {
            total += l.length();
            if (total > MAX_HEADER_BYTES) throw new IOException("headers too large");
            int colon = l.indexOf(':');
            if (colon > 0) headers.computeIfAbsent(l.substring(0, colon).trim().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                    .add(l.substring(colon + 1).trim());
        }

        byte[] data;
        if (req.method().equals("HEAD") || code == 204 || code == 304) {
            data = new byte[0];
        } else if (headers.getOrDefault("transfer-encoding", List.of()).stream().anyMatch(v -> v.toLowerCase(Locale.ROOT).contains("chunked"))) {
            data = chunked(in);
        } else if (headers.containsKey("content-length")) {
            int len = Integer.parseInt(headers.get("content-length").get(0));
            if (len > MAX_BODY_BYTES) throw new IOException("body too large");
            data = in.readNBytes(len);
            if (data.length < len) throw new EOFException("connection cut");
        } else {
            data = in.readNBytes(MAX_BODY_BYTES);
        }
        try { ssl.close(); } catch (IOException ignored) { }

        HttpHeaders hh = HttpHeaders.of(headers, (BiPredicate<String, String>) (a, b) -> true);
        InputStream body = new ByteArrayInputStream(data);
        return new HttpResponse<>() {
            @Override public int statusCode() { return code; }
            @Override public HttpRequest request() { return req; }
            @Override public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            @Override public HttpHeaders headers() { return hh; }
            @Override public InputStream body() { return body; }
            @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return req.uri(); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }

    private static byte[] chunked(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String size = line(in);
            int semi = size.indexOf(';');
            int n = Integer.parseInt((semi >= 0 ? size.substring(0, semi) : size).trim(), 16);
            if (n == 0) break;
            if (out.size() + n > MAX_BODY_BYTES) throw new IOException("body too large");
            byte[] chunk = in.readNBytes(n);
            if (chunk.length < n) throw new EOFException("connection cut");
            out.write(chunk);
            line(in);   // CRLF после куска
        }
        return out.toByteArray();
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') break;
            if (b != '\r') sb.append((char) b);
            if (sb.length() > MAX_HEADER_BYTES) throw new IOException("line too long");
        }
        if (b < 0 && sb.length() == 0) throw new EOFException("connection closed");
        return sb.toString();
    }
}
