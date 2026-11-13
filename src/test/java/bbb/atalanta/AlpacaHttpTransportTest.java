package bbb.atalanta;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlpacaHttpTransportTest {

    private static final URI LOCAL = URI.create("http://127.0.0.1:8765");
    private static final String KEY = "example-id-for-tests";
    private static final String SECRET = "example-secret-for-tests";

    private static final class StubSender {
        final AtomicReference<HttpRequest> lastRequest = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();
        final List<CompletableFuture<HttpResponse<String>>> replies = new ArrayList<>();

        AlpacaHttpTransport transport() {
            return new AlpacaHttpTransport(LOCAL, KEY, SECRET, Duration.ofSeconds(20),
                    (request, ignoredHandler) -> {
                        lastRequest.set(request);
                        calls.incrementAndGet();
                        return replies.get(0);
                    });
        }

        void reply(int status, String json) {
            replies.add(CompletableFuture.completedFuture(new Reply(status, json)));
        }

        void failed(Throwable failure) {
            replies.add(CompletableFuture.failedFuture(failure));
        }
    }

    @Test
    void authenticatedGetUsesOnlyExpectedHeadersAndMethod() {
        StubSender sender = new StubSender();
        sender.reply(200, "{\"currency\":\"USD\",\"cash\":\"123.45\"}");

        Result<JsonNode> result = sender.transport()
                .get(List.of("v2", "account"), Map.of())
                .toCompletableFuture().join();

        assertThat(result).isInstanceOf(Result.Ok.class);
        JsonNode json = ((Result.Ok<JsonNode>) result).value();
        assertThat(json.get("cash").asText()).isEqualTo("123.45");
        HttpRequest request = sender.lastRequest.get();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.uri()).isEqualTo(URI.create("http://127.0.0.1:8765/v2/account"));
        assertThat(request.timeout()).contains(Duration.ofSeconds(20));
        assertThat(request.headers().firstValue("APCA-API-KEY-ID")).contains(KEY);
        assertThat(request.headers().firstValue("APCA-API-SECRET-KEY")).contains(SECRET);
        assertThat(request.headers().firstValue("Accept")).contains("application/json");
        assertThat(request.headers().firstValue("Authorization")).isEmpty();
        assertThat(sender.calls).hasValue(1);
        assertThat(sender.transport().toString()).doesNotContain(KEY, SECRET);
    }

    @Test
    void queryAndPathSegmentsAreEncodedExactlyOnce() {
        StubSender sender = new StubSender();
        sender.reply(200, "[]");
        Map<String, String> query = new LinkedHashMap<>();
        query.put("symbols", "BTC/USD,ETH/USD");
        query.put("after", "2026-11-13T01:02:03Z");
        query.put("note", "a b&c");

        Result<JsonNode> result = sender.transport()
                .get(List.of("v2", "orders", "BTC/USD"), query)
                .toCompletableFuture().join();

        assertThat(result).isInstanceOf(Result.Ok.class);
        assertThat(((Result.Ok<JsonNode>) result).value().isArray()).isTrue();
        assertThat(sender.lastRequest.get().uri().toASCIIString())
                .isEqualTo("http://127.0.0.1:8765/v2/orders/BTC%2FUSD"
                        + "?symbols=BTC%2FUSD%2CETH%2FUSD"
                        + "&after=2026-11-13T01%3A02%3A03Z"
                        + "&note=a%20b%26c");
    }

    @Test
    void statusCodesBecomeTypedErrorsWithoutExposingProviderResponse() {
        Map<Integer, AlpacaError> expected = Map.of(
                401, new AlpacaError.Authentication("Alpaca authentication failed"),
                403, new AlpacaError.Forbidden("Alpaca access denied"),
                429, new AlpacaError.RateLimited("Alpaca rate limit exceeded"),
                404, new AlpacaError.HttpFailure(404, "Alpaca returned HTTP 404"),
                500, new AlpacaError.HttpFailure(500, "Alpaca returned HTTP 500"),
                302, new AlpacaError.HttpFailure(302, "Alpaca returned HTTP 302")
        );

        expected.forEach((status, error) -> {
            StubSender sender = new StubSender();
            sender.reply(status, "{\"message\":\"PRIVATE_TOKEN_AND_ACCOUNT\"}");
            Result<JsonNode> result = sender.transport()
                    .get(List.of("v2", "account"), Map.of())
                    .toCompletableFuture().join();

            assertThat(result).isEqualTo(Result.err(error));
            assertThat(result.toString()).doesNotContain("PRIVATE_TOKEN_AND_ACCOUNT", KEY, SECRET);
            assertThat(sender.calls).hasValue(1); // no retries or redirect requests
        });
    }

    @Test
    void emptyMalformedOrUnexpectedJsonBecomesInvalidResponse() {
        List<String> invalid = java.util.Arrays.asList("", "  ", "{broken", "null", "42", "\"string\"",
                "true", "{} {}", null);
        for (String body : invalid) {
            StubSender sender = new StubSender();
            sender.reply(200, body);
            Result<JsonNode> result = sender.transport()
                    .get(List.of("v2", "account"), Map.of())
                    .toCompletableFuture().join();

            assertThat(result).isInstanceOf(Result.Err.class);
            assertThat(((Result.Err<JsonNode>) result).error())
                    .isInstanceOf(AlpacaError.InvalidResponse.class);
            assertThat(result.toString()).doesNotContain("broken");
        }
    }

    @Test
    void financialJsonNumbersAreParsedAsExactDecimals() {
        StubSender sender = new StubSender();
        sender.reply(200, "{\"cash\":123456789.123456789,\"qty\":0.000000001}");

        Result<JsonNode> result = sender.transport()
                .get(List.of("v2", "account"), Map.of())
                .toCompletableFuture().join();

        JsonNode value = ((Result.Ok<JsonNode>) result).value();
        assertThat(value.get("cash").decimalValue())
                .isEqualByComparingTo(new BigDecimal("123456789.123456789"));
        assertThat(value.get("qty").decimalValue())
                .isEqualByComparingTo(new BigDecimal("0.000000001"));
    }

    @Test
    void realJdkClientSendsCredentialsAndDoesNotFollowRedirects() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger redirected = new AtomicInteger();
        AtomicReference<String> receivedKey = new AtomicReference<>();
        AtomicReference<String> receivedSecret = new AtomicReference<>();
        server.createContext("/v2/account", exchange -> {
            receivedKey.set(exchange.getRequestHeaders().getFirst("APCA-API-KEY-ID"));
            receivedSecret.set(exchange.getRequestHeaders().getFirst("APCA-API-SECRET-KEY"));
            exchange.getResponseHeaders().set("Location", "/unexpected-redirect");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/unexpected-redirect", exchange -> {
            redirected.incrementAndGet();
            byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            try (var body = exchange.getResponseBody()) {
                body.write(payload);
            }
        });
        server.start();
        try {
            URI testHost = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            AlpacaHttpTransport transport = new AlpacaHttpTransport(
                    testHost, KEY, SECRET, Duration.ofSeconds(5), client::sendAsync);

            Result<JsonNode> result = transport.get(List.of("v2", "account"), Map.of())
                    .toCompletableFuture().join();

            assertThat(result).isEqualTo(Result.err(
                    new AlpacaError.HttpFailure(302, "Alpaca returned HTTP 302")));
            assertThat(receivedKey).hasValue(KEY);
            assertThat(receivedSecret).hasValue(SECRET);
            assertThat(redirected).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anArrayIsValidForCollectionEndpoints() {
        StubSender sender = new StubSender();
        sender.reply(200, "[{\"symbol\":\"BTCUSD\"}]");

        Result<JsonNode> result = sender.transport()
                .get(List.of("v2", "positions"), Map.of())
                .toCompletableFuture().join();

        assertThat(((Result.Ok<JsonNode>) result).value().get(0).get("symbol").asText())
                .isEqualTo("BTCUSD");
    }

    @Test
    void requestRemainsAsynchronousUntilResponseArrives() {
        StubSender sender = new StubSender();
        CompletableFuture<HttpResponse<String>> pending = new CompletableFuture<>();
        sender.replies.add(pending);

        CompletableFuture<Result<JsonNode>> result = sender.transport()
                .get(List.of("v2", "account"), Map.of())
                .toCompletableFuture();

        assertThat(result).isNotDone();
        pending.complete(new Reply(200, "{}"));
        assertThat(result.join()).isEqualTo(Result.ok(new com.fasterxml.jackson.databind.ObjectMapper()
                .createObjectNode()));
    }

    @Test
    void networkAndTimeoutFailuresBecomeErrorsWithoutExceptionLeakage() {
        StubSender io = new StubSender();
        io.failed(new CompletionException(new IOException("SECRET_PRIVATE_RESPONSE")));
        Result<JsonNode> failure = io.transport()
                .get(List.of("v2", "account"), Map.of())
                .toCompletableFuture().join();
        assertThat(failure).isEqualTo(Result.err(
                new AlpacaError.TransportFailure("Could not contact Alpaca")));
        assertThat(failure.toString()).doesNotContain("SECRET_PRIVATE_RESPONSE");

        StubSender timeout = new StubSender();
        timeout.failed(new HttpTimeoutException("SECRET_PRIVATE_RESPONSE"));
        assertThat(timeout.transport().get(List.of("v2", "account"), Map.of())
                .toCompletableFuture().join())
                .isEqualTo(Result.err(new AlpacaError.Timeout()));
    }

    @Test
    void cancellationAndProgrammingDefectsRemainExceptional() {
        StubSender cancelled = new StubSender();
        cancelled.failed(new CancellationException("cancelled"));
        assertThatThrownBy(() -> cancelled.transport()
                .get(List.of("v2", "account"), Map.of())
                .toCompletableFuture().join())
                .isInstanceOfAny(CancellationException.class, CompletionException.class);

        StubSender bug = new StubSender();
        bug.failed(new IllegalStateException("programming defect"));
        assertThatThrownBy(() -> bug.transport()
                .get(List.of("v2", "account"), Map.of())
                .toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void factoryRejectsCredentialsAndArbitraryHosts() {
        StubSender sender = new StubSender();
        sender.reply(200, "{}");

        assertThatIllegalArgumentException().isThrownBy(() -> new AlpacaHttpTransport(
                URI.create("https://api.alpaca.markets"), KEY, SECRET, Duration.ofSeconds(5),
                (request, handler) -> sender.replies.get(0)));
        assertThatIllegalArgumentException().isThrownBy(() -> new AlpacaHttpTransport(
                URI.create("http://example.com"), KEY, SECRET, Duration.ofSeconds(5),
                (request, handler) -> sender.replies.get(0)));
        assertThatIllegalArgumentException().isThrownBy(() -> new AlpacaHttpTransport(
                LOCAL, "\r\n", SECRET, Duration.ofSeconds(5),
                (request, handler) -> sender.replies.get(0)));
        assertThatIllegalArgumentException().isThrownBy(() -> AlpacaHttpTransport.paper(
                KEY, "secret\nmalicious"));
        assertThatIllegalArgumentException().isThrownBy(() -> new AlpacaHttpTransport(
                LOCAL, KEY, SECRET, Duration.ZERO, (request, handler) -> sender.replies.get(0)));
    }

    @Test
    void invalidPathAndQueryAreRejectedBeforeMakingRequest() {
        StubSender sender = new StubSender();
        sender.reply(200, "{}");

        assertThatIllegalArgumentException().isThrownBy(() ->
                sender.transport().get(List.of("v1", "account"), Map.of()));
        assertThatIllegalArgumentException().isThrownBy(() ->
                sender.transport().get(List.of("v2", ".."), Map.of()));
        assertThatIllegalArgumentException().isThrownBy(() ->
                sender.transport().get(List.of(), Map.of()));
        assertThatNullPointerException().isThrownBy(() ->
                sender.transport().get(List.of("v2", "account"), null));
        assertThat(sender.calls).hasValue(0);
    }

    private record Reply(int statusCode, String body) implements HttpResponse<String> {

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (name, value) -> true);
        }

        @Override
        public URI uri() {
            return LOCAL;
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }
    }
}
