package bbb.atalanta;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static bbb.atalanta.CryptoOrderRequest.Side.BUY;
import static bbb.atalanta.CryptoOrderRequest.TimeInForce.GTC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtalantaPaperSubmitTest {
    private static final URI PAPER = URI.create("https://paper-api.alpaca.markets");
    private static final UUID UUID_ORDER =
            UUID.fromString("9275f66a-0a1b-4a22-8c55-167416c7cf14");
    private static final String KEY = "SYNTHETIC-KEY";
    private static final String SECRET = "SYNTHETIC-SECRET";
    private static final String ID = "stable-crypto-test-01";

    private static final String VALID_ORDER = """
            {
              "id":"9275f66a-0a1b-4a22-8c55-167416c7cf14",
              "client_order_id":"stable-crypto-test-01",
              "symbol":"BTC/USD",
              "asset_class":"crypto",
              "status":"new",
              "type":"market",
              "side":"buy",
              "time_in_force":"gtc",
              "qty":"0.000000001",
              "notional":null,
              "filled_qty":"0",
              "filled_avg_price":null,
              "created_at":"2026-10-02T00:00:00.123456789Z"
            }
            """;

    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<HttpRequest> sent = new AtomicReference<>();
    private CompletableFuture<HttpResponse<String>> remote = new CompletableFuture<>();

    private Atalanta client() {
        var transport = new AlpacaHttpTransport(
                PAPER, KEY, SECRET, Duration.ofSeconds(20),
                (request, ignoredHandler) -> {
                    requests.incrementAndGet();
                    sent.set(request);
                    return remote;
                });
        return new Atalanta(transport);
    }

    private static CryptoOrderRequest prepared() {
        return CryptoOrderRequest.marketQuantity(
                "BTC/USD", BUY, GTC, new BigDecimal("0.000000001"), ID);
    }

    private static Order order(Result<Order> result) {
        assertThat(result).isInstanceOf(Result.Ok.class);
        return ((Result.Ok<Order>) result).value();
    }

    private static AlpacaError error(Result<Order> result) {
        assertThat(result).isInstanceOf(Result.Err.class);
        return ((Result.Err<Order>) result).error();
    }

    @Test
    void preparationAndAuthorizationArePureOnlyExplicitSubmitSendsOnePaperPost() throws Exception {
        Atalanta paper = client();
        CryptoOrderRequest request = prepared();
        ConfirmedPaperOrder confirmed = request.authorize();
        assertThat(requests).hasValue(0);

        CompletableFuture<Result<Order>> answer = paper.submitPaperOrder(confirmed)
                .toCompletableFuture();
        assertThat(requests).hasValue(1);
        assertThat(answer).isNotDone();

        HttpRequest outgoing = sent.get();
        assertThat(outgoing.method()).isEqualTo("POST");
        assertThat(outgoing.uri()).isEqualTo(URI.create("https://paper-api.alpaca.markets/v2/orders"));
        assertThat(outgoing.uri().getRawQuery()).isNull();
        assertThat(outgoing.timeout()).contains(Duration.ofSeconds(20));
        assertThat(outgoing.headers().firstValue("APCA-API-KEY-ID")).contains(KEY);
        assertThat(outgoing.headers().firstValue("APCA-API-SECRET-KEY")).contains(SECRET);
        assertThat(outgoing.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(outgoing.headers().firstValue("Accept")).contains("application/json");
        assertThat(outgoing.headers().firstValue("Authorization")).isEmpty();
        assertThat(outgoing.bodyPublisher()).isPresent();

        var body = new ObjectMapper().readTree(sentBody(outgoing));
        assertThat(body.size()).isEqualTo(6);
        assertThat(body.path("symbol").textValue()).isEqualTo("BTC/USD");
        assertThat(body.path("qty").textValue()).isEqualTo("0.000000001");
        assertThat(body.path("side").textValue()).isEqualTo("buy");
        assertThat(body.path("type").textValue()).isEqualTo("market");
        assertThat(body.path("time_in_force").textValue()).isEqualTo("gtc");
        assertThat(body.path("client_order_id").textValue()).isEqualTo(ID);
        assertThat(body.has("notional")).isFalse();
        assertThat(body.has("limit_price")).isFalse();
        assertThat(body.has("extended_hours")).isFalse();
        assertThat(body.has("confirmation")).isFalse();
        assertThat(body.has("live")).isFalse();

        remote.complete(new Reply(200, VALID_ORDER));
        Order accepted = order(answer.join());
        assertThat(accepted.id()).isEqualTo(UUID_ORDER);
        assertThat(accepted.clientOrderId()).isEqualTo(ID);
        assertThat(accepted.status()).isEqualTo("new");
        assertThat(accepted.filledQty()).isEqualByComparingTo("0");
        assertThat(requests).hasValue(1);
    }

    @Test
    void sameOrderCanBeAcknowledgedAlreadyPartiallyFilledWithoutClaimingFinalState() {
        Atalanta paper = client();
        var future = paper.submitPaperOrder(prepared().authorize()).toCompletableFuture();
        remote.complete(new Reply(201, VALID_ORDER
                .replace("\"status\":\"new\"", "\"status\":\"partially_filled\"")
                .replace("\"filled_qty\":\"0\"", "\"filled_qty\":\"0.0000000005\"")));
        var accepted = order(future.join());
        assertThat(accepted.status()).isEqualTo("partially_filled");
        assertThat(accepted.filledQty()).isEqualByComparingTo("0.0000000005");
        assertThat(requests).hasValue(1);
    }

    @Test
    void nullAuthorizationCannotBuildAnyHttpRequest() {
        Atalanta paper = client();
        assertThatNullPointerException().isThrownBy(() -> paper.submitPaperOrder(null));
        assertThat(requests).hasValue(0);
        assertThatNullPointerException().isThrownBy(() ->
                new AlpacaHttpTransport(PAPER, KEY, SECRET, Duration.ofSeconds(20),
                        (req, handler) -> CompletableFuture.completedFuture(new Reply(200, VALID_ORDER)))
                        .postPaperOrder(null));
    }

    @Test
    void orderWithWrongOrMissingClientIdIsNotAConfirmedSubmission() {
        for (String body : List.of(
                VALID_ORDER.replace(ID, "another-client-id"),
                VALID_ORDER.replace("\"client_order_id\":\"" + ID + "\",", ""),
                VALID_ORDER.replace("\"client_order_id\":\"" + ID + "\"", "\"client_order_id\":null"))) {
            var response = submitResponse(200, body);
            AlpacaError uncertain = error(response);
            assertThat(uncertain).isInstanceOf(AlpacaError.SubmissionOutcomeUnknown.class);
            assertThat(uncertain.toString()).contains("client_order_id");
            assertThat(response.toString()).doesNotContain(ID, "another-client-id", KEY, SECRET);
        }
    }

    @Test
    void incompleteOrMalformedSuccessBodyLeavesOutcomeUnknown() {
        for (String body : List.of(
                "", " ", "null", "[]", "{}",
                "{broken",
                VALID_ORDER.replace("\"id\":\"" + UUID_ORDER + "\"", "\"id\":false"),
                VALID_ORDER.replace("\"status\":\"new\"", "\"status\":123"))) {
            AlpacaError uncertain = error(submitResponse(200, body));
            assertThat(uncertain).isInstanceOf(AlpacaError.SubmissionOutcomeUnknown.class);
            assertThat(uncertain.toString()).contains("inspect client_order_id");
        }
    }

    @Test
    void unexpectedSuccessStatusesNeverClaimAcceptedOrder() {
        for (int status : List.of(202, 204, 205, 206)) {
            AlpacaError uncertain = error(submitResponse(status, VALID_ORDER));
            assertThat(uncertain).isInstanceOf(AlpacaError.SubmissionOutcomeUnknown.class);
            assertThat(((AlpacaError.SubmissionOutcomeUnknown) uncertain).httpStatus())
                    .isEqualTo(status);
        }
    }

    @Test
    void authenticationForbidden422AndRateLimitHaveDistinctTypedFailures() {
        assertThat(error(submitResponse(401, "{\"private\":\"NO\"}")))
                .isInstanceOf(AlpacaError.Authentication.class);
        assertThat(error(submitResponse(403, "{\"private\":\"NO\"}")))
                .isInstanceOf(AlpacaError.Forbidden.class);
        assertThat(error(submitResponse(422, "{\"private\":\"NO\"}")))
                .isInstanceOf(AlpacaError.OrderRejected.class);
        assertThat(error(submitResponse(429, "{\"private\":\"NO\"}")))
                .isInstanceOf(AlpacaError.RateLimited.class);
    }

    @Test
    void provider5xxMeansUnknownAndRequestIsNotRepeated() {
        for (int status : List.of(500, 502, 503, 504)) {
            AlpacaError uncertain = error(submitResponse(status, "{\"secret\":\"PRIVATE-BODY\"}"));
            assertThat(uncertain).isInstanceOf(AlpacaError.SubmissionOutcomeUnknown.class);
            assertThat(((AlpacaError.SubmissionOutcomeUnknown) uncertain).httpStatus())
                    .isEqualTo(status);
            assertThat(uncertain.toString()).doesNotContain("PRIVATE-BODY", ID, SECRET);
        }
    }

    @Test
    void unrelatedHttpStatusDoesNotBecomeSubmittedOrder() {
        for (int status : List.of(301, 302, 400, 404, 409, 499)) {
            AlpacaError failure = error(submitResponse(status, "{\"providerBody\":\"PRIVATE\"}"));
            assertThat(failure).isInstanceOf(AlpacaError.HttpFailure.class);
            assertThat(failure.toString()).doesNotContain("PRIVATE");
        }
    }

    @Test
    void timeoutOrLostConnectionAreUnknownOutcomeNotDefiniteFailure() {
        for (Throwable failure : List.of(
                new IOException("PRIVATE SECRET USER BODY"),
                new CompletionException(new HttpTimeoutException("PRIVATE TIMED OUT")))) {
            Atalanta paper = client();
            var result = paper.submitPaperOrder(prepared().authorize()).toCompletableFuture();
            remote.completeExceptionally(failure);
            AlpacaError uncertain = error(result.join());
            assertThat(uncertain).isInstanceOf(AlpacaError.SubmissionOutcomeUnknown.class);
            assertThat(uncertain.toString()).contains("inspect client_order_id");
            assertThat(uncertain.toString()).doesNotContain("PRIVATE", SECRET, ID);
            assertThat(requests).hasValue(1);
            reset();
        }
    }

    @Test
    void unexpectedProgrammingFaultRemainsExceptional() {
        var future = client().submitPaperOrder(prepared().authorize()).toCompletableFuture();
        remote.completeExceptionally(new IllegalStateException("programming fault"));
        assertThatThrownBy(future::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(requests).hasValue(1);
    }

    @Test
    void cancellationOfOutstandingHttpResponseDoesNotInventAcceptance() {
        var future = client().submitPaperOrder(prepared().authorize()).toCompletableFuture();
        remote.cancel(false);
        assertThatThrownBy(future::join)
                .isInstanceOfAny(CancellationException.class, CompletionException.class);
        assertThat(requests).hasValue(1);
    }

    @Test
    void manualReadOperationsNeverImplicitlySubmit() {
        var pending = client().order(UUID_ORDER).toCompletableFuture();
        assertThat(sent.get().method()).isEqualTo("GET");
        remote.complete(new Reply(404, "{}"));
        assertThat(pending.join()).isEqualTo(Result.ok(Optional.empty()));
        assertThat(requests).hasValue(1);
    }

    @Test
    void publicDefaultHostIsLockedToPaperAndRejectsLiveProductionHost() {
        assertThatThrownBy(() ->
                new AlpacaHttpTransport(
                        URI.create("https://api.alpaca.markets"), KEY, SECRET,
                        Duration.ofSeconds(20), (req, handler) -> remote))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void reset() {
        requests.set(0);
        sent.set(null);
        // The next iteration simulates an independent remote request.
        remote = new CompletableFuture<>();
    }

    private static Result<Order> submitResponse(int status, String json) {
        var calls = new AtomicInteger();
        var paper = new Atalanta(new AlpacaHttpTransport(
                PAPER, KEY, SECRET, Duration.ofSeconds(20),
                (req, handler) -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(new Reply(status, json));
                }));
        Result<Order> result = paper.submitPaperOrder(prepared().authorize())
                .toCompletableFuture().join();
        assertThat(calls).hasValue(1);
        assertThat(result.toString()).doesNotContain("SYNTHETIC-SECRET");
        return result;
    }

    private static String sentBody(HttpRequest request) {
        var text = new CompletableFuture<String>();
        var bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }
            @Override
            public void onNext(ByteBuffer buffer) {
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            @Override
            public void onError(Throwable error) {
                text.completeExceptionally(error);
            }
            @Override
            public void onComplete() {
                text.complete(bytes.toString(StandardCharsets.UTF_8));
            }
        });
        return text.join();
    }

    private record Reply(int statusCode, String body) implements HttpResponse<String> {
        public HttpRequest request() { return null; }
        public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
        public URI uri() { return PAPER; }
        public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        public Optional<SSLSession> sslSession() { return Optional.empty(); }
    }
}
