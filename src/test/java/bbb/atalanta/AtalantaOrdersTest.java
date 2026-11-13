package bbb.atalanta;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtalantaOrdersTest {

    private static final URI LOOPBACK = URI.create("http://127.0.0.1:8765");
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<HttpRequest> request = new AtomicReference<>();
    private final CompletableFuture<HttpResponse<String>> remote = new CompletableFuture<>();

    private Atalanta client() {
        return new Atalanta(new AlpacaHttpTransport(
                LOOPBACK, "TEST-KEY", "TEST-SECRET", Duration.ofSeconds(5),
                (req, handler) -> {
                    request.set(req);
                    calls.incrementAndGet();
                    return remote;
                }));
    }

    private String fixture(String name) throws IOException {
        try (var stream = getClass().getResourceAsStream("/fixtures/" + name)) {
            if (stream == null) throw new IllegalStateException("Missing fixture " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void providerDefaultsAreNotOverriddenByClientAndRequestIsAsynchronous() throws Exception {
        Atalanta atalanta = client();
        CompletionStage<Result<List<Order>>> stage = atalanta.orders();
        assertThat(stage.toCompletableFuture()).isNotDone();
        assertThat(calls).hasValue(1);

        assertThat(request.get().method()).isEqualTo("GET");
        assertThat(request.get().uri())
                .isEqualTo(URI.create("http://127.0.0.1:8765/v2/orders"));
        assertThat(request.get().uri().getRawQuery()).isNull();
        assertThat(request.get().timeout()).contains(Duration.ofSeconds(5));
        assertThat(request.get().headers().firstValue("APCA-API-KEY-ID"))
                .contains("TEST-KEY");
        assertThat(request.get().headers().firstValue("APCA-API-SECRET-KEY"))
                .contains("TEST-SECRET");

        remote.complete(new Reply(200, fixture("orders-mixed.json")));
        Result<List<Order>> result = stage.toCompletableFuture().join();

        assertThat(result).isInstanceOf(Result.Ok.class);
        List<Order> orders = ((Result.Ok<List<Order>>) result).value();
        assertThat(orders).hasSize(6).isUnmodifiable();
        assertThat(orders.get(2).legs()).hasSize(2);
        assertThat(orders.get(1).notional()).isNotNull();
        assertThat(atalanta.toString()).doesNotContain("TEST-KEY", "TEST-SECRET");
    }

    @Test
    void sendsAllNonCursorFiltersWithExactlyOneEncodingPass() {
        OrderQuery filters = OrderQuery.defaults()
                .withStatus("closed")
                .withLimit(500)
                .withAfter(Instant.parse("2026-11-11T00:00:00.123456789Z"))
                .withUntil(Instant.parse("2026-11-13T11:30:00Z"))
                .withDirection("asc")
                .withNested(true)
                .withSymbols(List.of("BTCUSD", "AAPL"))
                .withSide("buy")
                .withAssetClasses(List.of("crypto", "us_equity"));

        CompletionStage<Result<List<Order>>> stage = client().orders(filters);

        assertThat(request.get().uri().toASCIIString())
                .isEqualTo("http://127.0.0.1:8765/v2/orders"
                        + "?status=closed"
                        + "&limit=500"
                        + "&after=2026-11-11T00%3A00%3A00.123456789Z"
                        + "&until=2026-11-13T11%3A30%3A00Z"
                        + "&direction=asc"
                        + "&nested=true"
                        + "&symbols=BTCUSD%2CAAPL"
                        + "&side=buy"
                        + "&asset_class=crypto%2Cus_equity");
        remote.complete(new Reply(200, "[]"));
        assertThat(stage.toCompletableFuture().join()).isEqualTo(Result.ok(List.of()));
        assertThat(calls).hasValue(1);
    }

    @Test
    void orderIdCursorIsEncodedWithoutDateBoundsOrInventedPageToken() {
        UUID id = UUID.fromString("11111111-2222-4333-8444-555555555555");
        CompletionStage<Result<List<Order>>> stage =
                client().orders(OrderQuery.defaults().withAfterOrderId(id));

        assertThat(request.get().uri().getRawQuery())
                .isEqualTo("after_order_id=11111111-2222-4333-8444-555555555555");
        assertThat(request.get().uri().getRawQuery())
                .doesNotContain("page_token", "after=", "until=", "before_order_id");
        remote.complete(new Reply(200, "[]"));
        assertThat(stage.toCompletableFuture().join()).isEqualTo(Result.ok(List.of()));
    }

    @Test
    void callerValidationPreventsAnyRemoteInvocation() {
        Atalanta client = client();
        assertThatNullPointerException().isThrownBy(() -> client.orders(null));
        assertThat(calls).hasValue(0);
    }

    @Test
    void providerAuthenticationAndRateLimitFailuresRemainSealedErrors() {
        CompletionStage<Result<List<Order>>> stage = client().orders();
        remote.complete(new Reply(429, "{\"message\":\"PRIVATE_ACCOUNT_AND_SECRET\"}"));
        Result<List<Order>> result = stage.toCompletableFuture().join();

        assertThat(result).isEqualTo(Result.err(
                new AlpacaError.RateLimited("Alpaca rate limit exceeded")));
        assertThat(result.toString()).doesNotContain("PRIVATE_ACCOUNT_AND_SECRET", "TEST-SECRET");
    }

    @Test
    void badResponseShapeBecomesTypedInvalidResponseRatherThanException() {
        CompletionStage<Result<List<Order>>> stage = client().orders();
        remote.complete(new Reply(200, "{\"orders\":[]}"));

        assertThat(stage.toCompletableFuture().join()).isEqualTo(
                Result.err(new AlpacaError.InvalidResponse("Expected an orders JSON array")));
    }

    @Test
    void networkFailuresStayFunctionalAndUnforeseenCodeErrorsRemainExceptional() {
        CompletionStage<Result<List<Order>>> stage = client().orders();
        remote.completeExceptionally(new IOException("PRIVATE_REMOTE_DETAILS"));
        assertThat(stage.toCompletableFuture().join()).isEqualTo(Result.err(
                new AlpacaError.TransportFailure("Could not contact Alpaca")));
        assertThat(stage.toCompletableFuture().join().toString())
                .doesNotContain("PRIVATE_REMOTE_DETAILS");
    }

    @Test
    void unexpectedProgrammingErrorsAreNotCamouflagedAsAlpacaErrors() {
        CompletionStage<Result<List<Order>>> stage = client().orders();
        remote.completeExceptionally(new IllegalStateException("Unexpected programming bug"));
        assertThatThrownBy(() -> stage.toCompletableFuture().join())
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    private record Reply(int statusCode, String body) implements HttpResponse<String> {
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (k, v) -> true); }
        @Override public URI uri() { return LOOPBACK; }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
    }
}
