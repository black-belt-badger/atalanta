package bbb.atalanta;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

class AtalantaOrderByIdTest {

    private static final UUID EQUITY_ID =
            UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final UUID CRYPTO_ID =
            UUID.fromString("22222222-3333-4444-8555-666666666666");
    private static final UUID MLEG_ID =
            UUID.fromString("55555555-6666-4777-8888-999999999999");
    private static final UUID BRACKET_ID =
            UUID.fromString("77777777-8888-4999-8aaa-bbbbbbbbbbbb");
    private static final URI LOOPBACK = URI.create("http://127.0.0.1:8765");

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<HttpRequest> request = new AtomicReference<>();
    private final CompletableFuture<HttpResponse<String>> response = new CompletableFuture<>();

    private Atalanta client() {
        AlpacaHttpTransport http = new AlpacaHttpTransport(
                LOOPBACK, "TEST-KEY", "TEST-SECRET", Duration.ofSeconds(5),
                (req, handler) -> {
                    calls.incrementAndGet();
                    request.set(req);
                    return response;
                });
        return new Atalanta(http);
    }

    private String fixture(String name) throws IOException {
        try (var stream = getClass().getResourceAsStream("/fixtures/" + name)) {
            if (stream == null) throw new IllegalStateException("Missing fixture: " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void defaultLookupIsAsyncAndSendsOnlyUuidPathWithoutNestedParameter() throws Exception {
        CompletionStage<Result<Optional<Order>>> stage = client().order(EQUITY_ID);

        assertThat(calls).hasValue(1);
        assertThat(stage.toCompletableFuture()).isNotDone();
        assertThat(request.get().method()).isEqualTo("GET");
        assertThat(request.get().uri())
                .isEqualTo(URI.create("http://127.0.0.1:8765/v2/orders/" + EQUITY_ID));
        assertThat(request.get().uri().getRawQuery()).isNull();
        assertThat(request.get().headers().firstValue("APCA-API-KEY-ID")).contains("TEST-KEY");
        assertThat(request.get().headers().firstValue("APCA-API-SECRET-KEY"))
                .contains("TEST-SECRET");

        response.complete(new Reply(200, fixture("order-equity.json")));
        Result<Optional<Order>> result = stage.toCompletableFuture().join();

        assertThat(result).isInstanceOf(Result.Ok.class);
        Optional<Order> found = ((Result.Ok<Optional<Order>>) result).value();
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().id()).isEqualTo(EQUITY_ID);
        assertThat(found.orElseThrow().qty())
                .isEqualByComparingTo(new BigDecimal("5.123456789012345678901"));
        assertThat(found.orElseThrow().filledQty())
                .isEqualByComparingTo(new BigDecimal("2.000000000000000001"));
        assertThat(calls).hasValue(1);
    }

    @Test
    void trueNestedFlagUnpacksTypedOptionLegs() throws Exception {
        CompletionStage<Result<Optional<Order>>> stage = client().order(MLEG_ID, true);

        assertThat(request.get().uri().toASCIIString())
                .isEqualTo("http://127.0.0.1:8765/v2/orders/" + MLEG_ID + "?nested=true");
        response.complete(new Reply(200, fixture("order-mleg.json")));

        Order order = ((Result.Ok<Optional<Order>>) stage.toCompletableFuture().join())
                .value().orElseThrow();
        assertThat(order.id()).isEqualTo(MLEG_ID);
        assertThat(order.side()).isNull();
        assertThat(order.legs()).hasSize(2);
        assertThat(order.legs()).extracting(Order::side).containsExactly("buy", "sell");
        assertThat(order.legs().get(0).ratioQty())
                .isEqualByComparingTo(new BigDecimal("1.000000000000000001"));
        assertThat(order.legs().get(1).ratioQty())
                .isEqualByComparingTo(new BigDecimal("2.000000000000000002"));
        assertThat(order.legs().get(0).limitPrice())
                .isEqualByComparingTo(new BigDecimal("1.2500000001"));
        assertThat(order.legs()).isUnmodifiable();
    }

    @Test
    void falseNestedFlagIsSentExplicitly() throws Exception {
        CompletionStage<Result<Optional<Order>>> stage = client().order(CRYPTO_ID, false);

        assertThat(request.get().uri().getRawQuery()).isEqualTo("nested=false");
        response.complete(new Reply(200, fixture("order-crypto.json")));
        Order order = ((Result.Ok<Optional<Order>>) stage.toCompletableFuture().join())
                .value().orElseThrow();
        assertThat(order.qty()).isNull();
        assertThat(order.notional())
                .isEqualByComparingTo(new BigDecimal("25.1234567890123456789012345"));
        assertThat(order.legs()).isEmpty();
    }

    @Test
    void bracketOrderUsesSameRecursiveDecoderAsListEndpoint() throws Exception {
        CompletionStage<Result<Optional<Order>>> stage = client().order(BRACKET_ID, true);
        response.complete(new Reply(200, fixture("order-bracket.json")));

        Order order = ((Result.Ok<Optional<Order>>) stage.toCompletableFuture().join())
                .value().orElseThrow();
        assertThat(order.orderClass()).isEqualTo("bracket");
        assertThat(order.legs()).hasSize(1);
        assertThat(order.legs().get(0).stopPrice())
                .isEqualByComparingTo(new BigDecimal("400.50"));
    }

    @Test
    void missingOrderReturnsSuccessfulEmptyOptionalNotAnHttpError() {
        CompletionStage<Result<Optional<Order>>> stage = client().order(EQUITY_ID);
        response.complete(new Reply(404, "{\"message\":\"PRIVATE_UNKNOWN_ORDER\"}"));

        assertThat(stage.toCompletableFuture().join())
                .isEqualTo(Result.ok(Optional.empty()));
        assertThat(stage.toCompletableFuture().join().toString())
                .doesNotContain("PRIVATE_UNKNOWN_ORDER", "TEST-SECRET");
    }

    @Test
    void allNon404ProviderFailuresRetainTypedOperationalErrors() {
        for (int status : List.of(401, 403, 429, 500)) {
            AtomicReference<Result<Optional<Order>>> actual = new AtomicReference<>();
            // Each call creates a fresh transport and pending response.
            var pending = new CompletableFuture<HttpResponse<String>>();
            AlpacaHttpTransport http = new AlpacaHttpTransport(
                    LOOPBACK, "TEST-KEY", "TEST-SECRET", Duration.ofSeconds(5),
                    (req, handler) -> pending);
            CompletionStage<Result<Optional<Order>>> stage =
                    new Atalanta(http).order(EQUITY_ID);
            pending.complete(new Reply(status, "{\"message\":\"PRIVATE\"}"));
            actual.set(stage.toCompletableFuture().join());
            assertThat(actual.get()).isInstanceOf(Result.Err.class);
            assertThat(actual.get()).isNotEqualTo(Result.ok(Optional.empty()));
            AlpacaError error = ((Result.Err<Optional<Order>>) actual.get()).error();
            switch (status) {
                case 401 -> assertThat(error).isInstanceOf(AlpacaError.Authentication.class);
                case 403 -> assertThat(error).isInstanceOf(AlpacaError.Forbidden.class);
                case 429 -> assertThat(error).isInstanceOf(AlpacaError.RateLimited.class);
                default -> assertThat(error).isEqualTo(
                        new AlpacaError.HttpFailure(500, "Alpaca returned HTTP 500"));
            }
            assertThat(actual.get().toString()).doesNotContain("PRIVATE");
        }
    }

    @Test
    void anArrayOrScalarIsNotASingleOrderAndRemainsAnError() {
        for (String body : List.of("[]", "[{}]", "\"foo\"", "42", "{}", "null")) {
            var pending = new CompletableFuture<HttpResponse<String>>();
            AlpacaHttpTransport http = new AlpacaHttpTransport(
                    LOOPBACK, "TEST-KEY", "TEST-SECRET", Duration.ofSeconds(5),
                    (req, handler) -> pending);
            CompletionStage<Result<Optional<Order>>> stage =
                    new Atalanta(http).order(EQUITY_ID);
            pending.complete(new Reply(200, body));
            Result<Optional<Order>> result = stage.toCompletableFuture().join();
            assertThat(result).isInstanceOf(Result.Err.class);
            assertThat(((Result.Err<Optional<Order>>) result).error())
                    .isInstanceOf(AlpacaError.InvalidResponse.class);
        }
    }

    @Test
    void aDifferentOrderIdInSuccessfulResponseIsRejected() throws Exception {
        CompletionStage<Result<Optional<Order>>> stage = client().order(EQUITY_ID);
        response.complete(new Reply(200, fixture("order-crypto.json")));

        assertThat(stage.toCompletableFuture().join()).isEqualTo(
                Result.err(new AlpacaError.InvalidResponse(
                        "Order response ID does not match requested ID")));
    }

    @Test
    void malformedOrderDecimalReturnsInvalidResponseNotAnException() throws Exception {
        CompletionStage<Result<Optional<Order>>> stage = client().order(EQUITY_ID);
        response.complete(new Reply(200,
                fixture("order-equity.json").replace("\"limit_price\": \"193.1234567890123456789\"",
                        "\"limit_price\": \"PRIVATE_INVALID\"")));

        assertThat(stage.toCompletableFuture().join()).isEqualTo(
                Result.err(new AlpacaError.InvalidResponse("Invalid order field or type")));
    }

    @Test
    void nullIdIsRejectedBeforeAnyNetworkRequest() {
        Atalanta client = client();

        assertThatNullPointerException().isThrownBy(() -> client.order(null));
        assertThatNullPointerException().isThrownBy(() -> client.order(null, true));
        assertThat(calls).hasValue(0);
    }

    @Test
    void transportIoErrorsRemainFunctionalAndUnexpectedFailuresStayExceptional() {
        var transportFailure = client().order(EQUITY_ID);
        response.completeExceptionally(new IOException("PRIVATE_NETWORK_DETAILS"));

        assertThat(transportFailure.toCompletableFuture().join()).isEqualTo(
                Result.err(new AlpacaError.TransportFailure("Could not contact Alpaca")));

        var unexpected = new CompletableFuture<HttpResponse<String>>();
        AlpacaHttpTransport second = new AlpacaHttpTransport(
                LOOPBACK, "TEST-KEY", "TEST-SECRET", Duration.ofSeconds(5),
                (req, handler) -> unexpected);
        CompletionStage<Result<Optional<Order>>> stage = new Atalanta(second).order(EQUITY_ID);
        unexpected.completeExceptionally(new IllegalStateException("Unexpected code bug"));

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
