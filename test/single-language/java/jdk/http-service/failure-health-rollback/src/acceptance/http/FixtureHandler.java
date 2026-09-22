package acceptance.http;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import acceptance.config.FixtureConfiguration;
import acceptance.service.SummaryService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

public final class FixtureHandler implements HttpHandler {
    private final FixtureConfiguration configuration;

    private final SummaryService service = new SummaryService();
    public FixtureHandler(FixtureConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        int status = FixtureConfiguration.status();
        String type = "text/plain; charset=utf-8", body = configuration.label();
        boolean summary = exchange.getRequestURI().getPath().equals("/api/summary");
        if (status != 503 && (summary || FixtureConfiguration.mode().equals("json"))) {
            try {
                body = service.summarize(summary ? values(exchange.getRequestURI().getRawQuery()) : null).toJson();
                type = "application/json; charset=utf-8";
            } catch (IllegalArgumentException error) {
                status = 400;
                body = "invalid-values";
            }
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        } finally {
            exchange.close();
        }
    }

    private static String values(String query) {
        if (query == null)
            return null;
        for (String field : query.split("&")) {
            String[] pair = field.split("=", 2);
            if (URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals("values"))
                return pair.length == 1 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
        }
        return null;
    }
}
