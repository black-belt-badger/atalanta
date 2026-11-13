package bbb.atalanta;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtalantaCryptoTradesTest {
    private static final URI DATA = URI.create("https://data.alpaca.markets");
    private static final URI LOCAL = URI.create("http://127.0.0.1:8765");
    private static final String KEY = "SYNTHETIC-KEY";
    private static final String SECRET = "SYNTHETIC-SECRET";

    private static final String FIRST = """
            {
              "trades": {
                "BTC/USD": [{
                  "i":184467440737095516161234567890,
                  "p":12345678901234567890.123456789012345678901,
                  "s":0.0000000000000000123456789,
                  "t":"2026-10-02T00:00:00.123456789Z",
                  "tks":null,
                  "future":{"ratio":0.1234567890123456789012345,"flag":true}
                }],
                "ETH/USD":[]
              },
              "next_page_token":"ABC+/==",
              "provider_version":987654321098765432109876543210
            }
            """;

    private static final String LAST = """
            {
              "trades": {
                "BTC/USD": [{
                  "i":31455289,"p":29791.125,"s":0.0016,
                  "t":"2026-10-02T00:00:01.000000001Z","tks":"S"
                }]
              },
              "next_page_token":null
            }
            """;
    private static final String EMPTY_MORE =
            "{\"trades\":{},\"next_page_token\":\"MORE\"}";

    private static final class Stub {
        private final List<HttpRequest> requests = new ArrayList<>();
        private final List<CompletableFuture<HttpResponse<String>>> replies = new ArrayList<>();

        Stub reply(int status, String body) {
            replies.add(CompletableFuture.completedFuture(new Reply(status, body)));
            return this;
        }

        Stub failed(Throwable failure) {
            replies.add(CompletableFuture.failedFuture(failure));
            return this;
        }

        Atalanta client() {
            AlpacaHttpTransport paper = new AlpacaHttpTransport(
                    LOCAL, KEY, SECRET, Duration.ofSeconds(20),
                    (request, handler) -> CompletableFuture.failedFuture(
                            new AssertionError("Unexpected paper Trading API request")));
            CryptoMarketDataTransport data = new CryptoMarketDataTransport(
                    DATA, KEY, SECRET, Duration.ofSeconds(20),
                    (request, handler) -> {
                        int index = requests.size();
                        requests.add(request);
                        if (index >= replies.size()) {
                            return CompletableFuture.failedFuture(
                                    new AssertionError("Unexpected extra historical trade page"));
                        }
                        return replies.get(index);
                    });
            return new Atalanta(paper, data);
        }
    }

    private static CryptoTradeQuery query() {
        return CryptoTradeQuery.of(List.of("BTC/USD", "ETH/USD"));
    }

    @Test
    void decodesExactLargeIdsDecimalsNanosecondsAndUnknownFields() {
        Stub stub = new Stub().reply(200, FIRST);
        Result<CryptoTradePage> outcome = stub.client()
                .cryptoTrades(query()).toCompletableFuture().join();
        assertThat(outcome).isInstanceOf(Result.Ok.class);
        CryptoTradePage page = ((Result.Ok<CryptoTradePage>) outcome).value();
        assertThat(page.trades()).containsKeys("BTC/USD", "ETH/USD");
        assertThat(page.trades().get("ETH/USD")).isEmpty();
        assertThat(page.tradeCount()).isEqualTo(1);
        assertThat(page.continuation()).contains("ABC+/==");
        CryptoTrade trade = page.trades().get("BTC/USD").get(0);
        assertThat(trade.id()).isEqualTo(new BigInteger("184467440737095516161234567890"));
        assertThat(trade.price()).isEqualByComparingTo(
                new BigDecimal("12345678901234567890.123456789012345678901"));
        assertThat(trade.size()).isEqualByComparingTo(
                new BigDecimal("0.0000000000000000123456789"));
        assertThat(trade.timestamp()).isEqualTo("2026-10-02T00:00:00.123456789Z");
        assertThat(trade.takerSide()).isNull();
        assertThat(trade.supplied("tks")).isTrue();
        assertThat(((Map<?, ?>) trade.additionalFields().get("future")).get("ratio"))
                .isEqualTo(new BigDecimal("0.1234567890123456789012345"));
        assertThat(page.additionalFields().get("provider_version"))
                .isEqualTo(new BigInteger("987654321098765432109876543210"));
        assertThatThrownBy(() -> page.trades().get("ETH/USD").add(trade))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void distinguishesOmittedOptionalTakerSideFromExplicitNull() {
        String withoutSide = LAST.replace(",\"tks\":\"S\"", "");
        var absent = new Stub().reply(200, withoutSide);
        var supplied = new Stub().reply(200, FIRST);
        CryptoTrade noSide = ((Result.Ok<CryptoTradePage>) absent.client()
                .cryptoTrades(query()).toCompletableFuture().join())
                .value().trades().get("BTC/USD").get(0);
        CryptoTrade nullSide = ((Result.Ok<CryptoTradePage>) supplied.client()
                .cryptoTrades(query()).toCompletableFuture().join())
                .value().trades().get("BTC/USD").get(0);
        assertThat(noSide.supplied("tks")).isFalse();
        assertThat(noSide.takerSide()).isNull();
        assertThat(nullSide.supplied("tks")).isTrue();
        assertThat(nullSide.takerSide()).isNull();
    }

    @Test
    void sendsReadOnlyGetToExactAlpacaMarketDataHostAndEncodesAllParameters() {
        Stub stub = new Stub().reply(200, FIRST);
        CryptoTradeQuery request = query()
                .withLocation(CryptoLocation.EU_1)
                .withStart("2026-10-02T00:00:00.123456789Z")
                .withEnd("2026-10-02T03:00:00.123456789+03:00")
                .withLimit(10000)
                .withSort(CryptoTradeQuery.Sort.DESC)
                .withPageToken("ABC+/==");
        stub.client().cryptoTrades(request).toCompletableFuture().join();
        assertThat(stub.requests).hasSize(1);
        HttpRequest sent = stub.requests.get(0);
        assertThat(sent.method()).isEqualTo("GET");
        assertThat(sent.uri().toASCIIString()).isEqualTo(
                "https://data.alpaca.markets/v1beta3/crypto/eu-1/trades"
                + "?symbols=BTC%2FUSD%2CETH%2FUSD"
                + "&start=2026-10-02T00%3A00%3A00.123456789Z"
                + "&end=2026-10-02T03%3A00%3A00.123456789%2B03%3A00"
                + "&limit=10000&sort=desc&page_token=ABC%2B%2F%3D%3D");
        assertThat(sent.timeout()).contains(Duration.ofSeconds(20));
        assertThat(sent.uri().getHost()).isEqualTo("data.alpaca.markets");
        assertThat(sent.headers().firstValue("APCA-API-KEY-ID")).contains(KEY);
        assertThat(sent.headers().firstValue("APCA-API-SECRET-KEY")).contains(SECRET);
        assertThat(sent.headers().firstValue("Accept")).contains("application/json");
        assertThat(sent.headers().firstValue("Content-Type")).isEmpty();
        assertThat(sent.uri().getRawQuery()).doesNotContain("timeframe");
    }

    @Test
    void acceptsEmptyResultsAndAbsentOrEmptyContinuation() {
        for (String raw : List.of(
                "{\"trades\":{}}",
                "{\"trades\":{},\"next_page_token\":null}",
                "{\"trades\":{},\"next_page_token\":\"\"}",
                "{\"trades\":{\"BTC/USD\":[]}}")) {
            Stub stub = new Stub().reply(200, raw);
            var result = stub.client().cryptoTrades(query()).toCompletableFuture().join();
            assertThat(result).isInstanceOf(Result.Ok.class);
            CryptoTradePage page = ((Result.Ok<CryptoTradePage>) result).value();
            assertThat(page.tradeCount()).isZero();
            assertThat(page.continuation()).isEmpty();
        }
    }

    @Test
    void malformedAndMisTypedResponseIsSanitizedWithoutThrowing() {
        for (String raw : List.of(
                "[]", "{}", "null", "42", "{broken",
                "{\"trades\":null}", "{\"trades\":[]}",
                "{\"trades\":{\"BTC/USD\":{}}}",
                "{\"trades\":{\"BTC/USD\":[null]}}",
                "{\"trades\":{\"BTC/USD\":[{\"i\":10}]}}",
                FIRST.replace("184467440737095516161234567890", "1.25"),
                FIRST.replace("184467440737095516161234567890", "-1"),
                FIRST.replace("\"p\":12345678901234567890.123456789012345678901", "\"p\":\"SECRET_PRICE\""),
                FIRST.replace("\"s\":0.0000000000000000123456789", "\"s\":null"),
                FIRST.replace("\"tks\":null", "\"tks\":42"),
                FIRST.replace("2026-10-02T00:00:00.123456789Z", "not-a-time"),
                FIRST.replace("\"next_page_token\":\"ABC+/==\"", "\"next_page_token\":123"))) {
            Stub stub = new Stub().reply(200, raw);
            Result<CryptoTradePage> result = stub.client()
                    .cryptoTrades(query()).toCompletableFuture().join();
            assertThat(result).as(raw).isInstanceOf(Result.Err.class);
            AlpacaError error = ((Result.Err<CryptoTradePage>) result).error();
            assertThat(error).isInstanceOf(AlpacaError.InvalidResponse.class);
            assertThat(error.toString()).doesNotContain("SECRET_PRICE", KEY, SECRET);
            assertThat(stub.requests).hasSize(1);
        }
    }

    @Test
    void mapsAuthenticationRateLimitServerAndTimeoutToTypedErrors() {
        for (int status : List.of(401, 403, 404, 429, 503)) {
            Stub stub = new Stub().reply(status, "{\"message\":\"SECRET_PROVIDER_DATA\"}");
            Result<CryptoTradePage> result = stub.client()
                    .cryptoTrades(query()).toCompletableFuture().join();
            assertThat(result).isInstanceOf(Result.Err.class);
            AlpacaError error = ((Result.Err<CryptoTradePage>) result).error();
            switch (status) {
                case 401 -> assertThat(error).isInstanceOf(AlpacaError.Authentication.class);
                case 403 -> assertThat(error).isInstanceOf(AlpacaError.Forbidden.class);
                case 429 -> assertThat(error).isInstanceOf(AlpacaError.RateLimited.class);
                default -> assertThat(error).isInstanceOf(AlpacaError.HttpFailure.class);
            }
            assertThat(result.toString()).doesNotContain("SECRET_PROVIDER_DATA", SECRET);
            assertThat(stub.requests).hasSize(1);
        }
        for (Throwable failure : List.of(
                new IOException("SECRET_TRANSPORT_DETAIL"),
                new HttpTimeoutException("SECRET_TIMEOUT_DETAIL"))) {
            Stub stub = new Stub().failed(failure);
            Result<CryptoTradePage> result = stub.client()
                    .cryptoTrades(query()).toCompletableFuture().join();
            assertThat(result).isInstanceOf(Result.Err.class);
            AlpacaError error = ((Result.Err<CryptoTradePage>) result).error();
            if (failure instanceof HttpTimeoutException) {
                assertThat(error).isInstanceOf(AlpacaError.Timeout.class);
            } else {
                assertThat(error).isInstanceOf(AlpacaError.TransportFailure.class);
            }
            assertThat(result.toString()).doesNotContain("SECRET_TRANSPORT", "SECRET_TIMEOUT");
            assertThat(stub.requests).hasSize(1);
        }
    }

    @Test
    void followsOpaqueTokensEvenWhenIntermediatePageIsEmptyOrShort() {
        Stub stub = new Stub().reply(200, EMPTY_MORE)
                .reply(200, FIRST)
                .reply(200, LAST);
        CryptoTradeScan result = stub.client()
                .cryptoTradesPages(query(), 5).toCompletableFuture().join();
        assertThat(result).isInstanceOf(CryptoTradeScan.Stopped.class);
        var stopped = (CryptoTradeScan.Stopped) result;
        assertThat(stopped.exhausted()).isTrue();
        assertThat(stopped.pages()).hasSize(3);
        assertThat(stopped.pages().get(0).tradeCount()).isZero();
        assertThat(stopped.pages().get(1).tradeCount()).isEqualTo(1);
        assertThat(stopped.pages().get(2).tradeCount()).isEqualTo(1);
        assertThat(stub.requests).hasSize(3);
        assertThat(stub.requests.get(1).uri().getRawQuery()).contains("page_token=MORE");
        assertThat(stub.requests.get(2).uri().getRawQuery())
                .contains("page_token=ABC%2B%2F%3D%3D");
        assertThatThrownBy(() -> stopped.pages().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void stopsAtExplicitPageBoundAndProvidesUnconsumedContinuation() {
        Stub stub = new Stub().reply(200, EMPTY_MORE);
        CryptoTradeScan outcome = stub.client()
                .cryptoTradesPages(query(), 1).toCompletableFuture().join();
        var stopped = (CryptoTradeScan.Stopped) outcome;
        assertThat(stopped.pages()).hasSize(1);
        assertThat(stopped.exhausted()).isFalse();
        assertThat(stopped.continuation()).contains("MORE");
        assertThat(stub.requests).hasSize(1);
    }

    @Test
    void preservesPreviousPagesAndErrorOnLaterHttpFailure() {
        Stub stub = new Stub().reply(200, FIRST)
                .reply(429, "{\"error\":\"SECRET_TOO_MANY\"}");
        CryptoTradeScan outcome = stub.client()
                .cryptoTradesPages(query(), 5).toCompletableFuture().join();
        assertThat(outcome).isInstanceOf(CryptoTradeScan.Failed.class);
        var failed = (CryptoTradeScan.Failed) outcome;
        assertThat(failed.pages()).hasSize(1);
        assertThat(failed.pages().get(0).tradeCount()).isEqualTo(1);
        assertThat(failed.error()).isInstanceOf(AlpacaError.RateLimited.class);
        assertThat(failed.toString()).doesNotContain("SECRET_TOO_MANY");
        assertThat(stub.requests).hasSize(2);
    }

    @Test
    void detectsRepeatedTokenWithoutUnboundedRequests() {
        Stub stub = new Stub().reply(200, EMPTY_MORE).reply(200, EMPTY_MORE);
        CryptoTradeScan outcome = stub.client()
                .cryptoTradesPages(query(), 20).toCompletableFuture().join();
        assertThat(outcome).isInstanceOf(CryptoTradeScan.Failed.class);
        var failed = (CryptoTradeScan.Failed) outcome;
        assertThat(failed.pages()).hasSize(2);
        assertThat(failed.error()).isInstanceOf(AlpacaError.InvalidResponse.class);
        assertThat(stub.requests).hasSize(2);
    }

    @Test
    void detectsReusedInitialResumeTokenWithoutFetchingAgain() {
        Stub stub = new Stub().reply(200, EMPTY_MORE);
        CryptoTradeScan outcome = stub.client()
                .cryptoTradesPages(query().withPageToken("MORE"), 20)
                .toCompletableFuture().join();
        assertThat(outcome).isInstanceOf(CryptoTradeScan.Failed.class);
        assertThat(stub.requests).hasSize(1);
    }

    @Test
    void pageBoundIsValidatedBeforeAnyNetworkRequest() {
        Stub stub = new Stub();
        Atalanta client = stub.client();
        for (int limit : new int[]{-1, 0, 21, Integer.MAX_VALUE}) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> client.cryptoTradesPages(query(), limit));
        }
        assertThat(stub.requests).isEmpty();
    }

    private record Reply(int statusCode, String body) implements HttpResponse<String> {
        public HttpRequest request() { return null; }
        public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (n, v) -> true); }
        public URI uri() { return DATA; }
        public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        public Optional<SSLSession> sslSession() { return Optional.empty(); }
    }
}
