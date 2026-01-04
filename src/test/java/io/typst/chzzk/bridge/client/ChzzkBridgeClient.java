package io.typst.chzzk.bridge.client;

import com.google.gson.Gson;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Lightweight client for chzzk-bridge API.
 * Requires: JDK 11+, Gson
 */
public final class ChzzkBridgeClient implements AutoCloseable {
    private static final Gson GSON = new Gson();

    private final String baseUrl;
    private final HttpClient http;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "chzzk-sse");
        t.setDaemon(true);
        return t;
    });

    public ChzzkBridgeClient(String baseUrl) {
        this(baseUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public ChzzkBridgeClient(String baseUrl, HttpClient http) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.http = http;
    }

    // ───────────────────────────── Subscribe ─────────────────────────────

    public SubscribeResult subscribe(UUID uuid) throws IOException, InterruptedException {
        var req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/subscribe?uuid=" + uuid))
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(30))
                .build();
        var res = http.send(req, HttpResponse.BodyHandlers.ofString());

        return switch (res.statusCode()) {
            case 200 -> new SubscribeResult(Status.SUCCESS, null, null);
            case 204 -> new SubscribeResult(Status.ALREADY_SUBSCRIBED, null, null);
            case 401 -> {
                var body = GSON.fromJson(res.body(), AuthRequired.class);
                yield new SubscribeResult(Status.AUTH_REQUIRED, body.state, body.path);
            }
            default -> new SubscribeResult(Status.ERROR, null, null);
        };
    }

    public CompletableFuture<SubscribeResult> subscribeAsync(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            try { return subscribe(uuid); }
            catch (Exception e) { throw new CompletionException(e); }
        }, executor);
    }

    // ───────────────────────────── Unsubscribe ─────────────────────────────

    public boolean unsubscribe(UUID uuid) throws IOException, InterruptedException {
        var req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/unsubscribe?uuid=" + uuid))
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(10))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
    }

    // ───────────────────────────── SSE Stream ─────────────────────────────

    public void streamEvents(UUID uuid, Consumer<ChatMessage> onMessage) throws IOException, InterruptedException {
        streamEvents(uuid, null, onMessage, null);
    }

    public void streamEvents(UUID uuid, Integer lastEventId, Consumer<ChatMessage> onMessage, Consumer<Throwable> onError)
            throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/sse?uuid=" + uuid))
                .GET()
                .header("Accept", "text/event-stream");
        if (lastEventId != null) builder.header("Last-Event-ID", lastEventId.toString());

        var res = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) throw new IOException("SSE failed: " + res.statusCode());

        try (var reader = new BufferedReader(new InputStreamReader(res.body(), StandardCharsets.UTF_8))) {
            parseSse(reader, onMessage, onError);
        }
    }

    public SseHandle streamEventsAsync(UUID uuid, Consumer<ChatMessage> onMessage, Consumer<Throwable> onError) {
        return streamEventsAsync(uuid, null, onMessage, onError);
    }

    public SseHandle streamEventsAsync(UUID uuid, Integer lastEventId, Consumer<ChatMessage> onMessage, Consumer<Throwable> onError) {
        var handle = new SseHandle();
        executor.submit(() -> {
            try {
                var builder = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/v1/sse?uuid=" + uuid))
                        .GET()
                        .header("Accept", "text/event-stream");
                if (lastEventId != null) builder.header("Last-Event-ID", lastEventId.toString());

                var res = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
                if (res.statusCode() != 200) throw new IOException("SSE failed: " + res.statusCode());

                handle.stream = res.body();
                try (var reader = new BufferedReader(new InputStreamReader(res.body(), StandardCharsets.UTF_8))) {
                    parseSse(reader, onMessage, onError);
                }
            } catch (Exception e) {
                if (!handle.closed && onError != null) onError.accept(e);
            }
        });
        return handle;
    }

    private void parseSse(BufferedReader reader, Consumer<ChatMessage> onMessage, Consumer<Throwable> onError) throws IOException {
        StringBuilder data = new StringBuilder();
        String event = null, id = null, line;

        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (!data.isEmpty() && !"heartbeat".equals(event)) {
                    try {
                        String json = data.toString().trim();
                        if (!json.equals("{}")) {
                            var msg = GSON.fromJson(json, ChatMessage.class);
                            if (id != null) msg.id = Integer.parseInt(id);
                            onMessage.accept(msg);
                        }
                    } catch (Exception e) {
                        if (onError != null) onError.accept(e);
                    }
                }
                data.setLength(0);
                event = id = null;
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append("\n");
                data.append(line.substring(5).trim());
            } else if (line.startsWith("event:")) {
                event = line.substring(6).trim();
            } else if (line.startsWith("id:")) {
                id = line.substring(3).trim();
            }
        }
    }

    @Override
    public void close() { executor.shutdownNow(); }

    // ───────────────────────────── Data Classes ─────────────────────────────

    public enum Status { SUCCESS, ALREADY_SUBSCRIBED, AUTH_REQUIRED, ERROR }

    public record SubscribeResult(Status status, String state, String authUrl) {
        public boolean requiresAuth() { return status == Status.AUTH_REQUIRED; }
        public boolean isSuccess() { return status == Status.SUCCESS || status == Status.ALREADY_SUBSCRIBED; }
    }

    private static class AuthRequired {
        String state;
        String path;
    }

    public static class ChatMessage {
        public int id = -1;
        public String channelId = "";
        public String senderId = "";
        public String senderName = "";
        public String message = "";
        public long messageTime = 0;
        public int payAmount = 0;

        public Instant getMessageTimeAsInstant() { return Instant.ofEpochMilli(messageTime); }
        public boolean isDonation() { return payAmount > 0; }
    }

    public static class SseHandle implements AutoCloseable {
        volatile InputStream stream;
        volatile boolean closed;

        @Override
        public void close() {
            closed = true;
            if (stream != null) try { stream.close(); } catch (IOException ignored) {}
        }
    }
}
