package org.quartzlang.embed.kits;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.quartzlang.embed.Kit;
import org.quartzlang.embed.KitContext;
import org.quartzlang.jvm.Result;
import org.quartzlang.jvm.Struct;

/** Answers the library's {@code org.quartzlang.http.send}, which fails without a host, with Java's HTTP client. */
public final class HttpKit implements Kit {
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30)).build();

    @Override
    public String name() {
        return "http";
    }

    @Override
    public void bind(KitContext k) {
        k.bind("org.quartzlang.http.send", c -> send((Struct) c.arg(0)));
    }

    private Object send(Struct request) throws InterruptedException {
        try {
            Object body = request.get("body");
            HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray((byte[]) body);
            long timeout = ((Number) request.get("timeoutMillis")).longValue();
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create((String) request.get("url")))
                    .method((String) request.get("method"), publisher)
                    .timeout(Duration.ofMillis(Math.max(1, timeout)));
            for (Object header : (List<?>) request.get("headers")) {
                List<?> pair = (List<?>) header;
                builder.header((String) pair.get(0), (String) pair.get(1));
            }
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            List<List<Object>> headers = new ArrayList<>();
            response.headers().map().forEach((name, values) -> values.forEach(v -> headers.add(List.of(name, v))));
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("status", (long) response.statusCode());
            fields.put("headers", headers);
            fields.put("body", response.body());
            return Result.ok(Struct.of("Response", fields));
        } catch (IOException | IllegalArgumentException e) {
            return Result.err(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }
}
