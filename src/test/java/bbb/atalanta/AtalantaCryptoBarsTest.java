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

class AtalantaCryptoBarsTest {

    private static final URI DATA = URI.create("https://data.alpaca.markets");
    private static final URI LOCAL = URI.create("http://127.0.0.1:8765");
    private static final String KEY = "SYNTHETIC-ID";
    private static final String SECRET = "SYNTHETIC-SECRET";

    private static final String FIRST = """
            {
              "bars": {
                "BTC/USD": [{
                  "t":"2026-10-02T00:00:00.123456789Z",
                  "o":12345678901234567890.12345678901234567890,
                  "h":12345678901234567891.12345678901234567890,
                  "l":12345678901234567889.000000000000000001,
                  "c":12345678901234567890.000000000000000002,
                  "v":0.000000000000000000123456789,
                  "n":18446744073709551616,
                  "vw":null,
                  "future":{"code":1,"ratio":0.1234567890123456789012345}
                }],
                "ETH/USD": []
              },
              "next_page_token": "ABC+/==",
              "provider_version": 987654321098765432109876543210
            }
            """;

    private static final String LAST = """
            {
              "bars": {
                "BTC/USD": [{
                  "t":"2026-10-02T00:01:00.123456789Z",
                  "o":29000.1, "h":29000.2, "l":29000.0, "c":29000.1,
                  "v":3.123456789, "n":null, "vw":29000.125
                }]
              },
              "next_page_token":null
            }
            """;

    private static final String EMPTY_MORE =
            "{\"bars\":{},\"next_page_token\":\"MORE\"}";

    private static final class Stub {
        private final List<HttpRequest> requests = new ArrayList<>();
        private final List<CompletableFuture<HttpResponse<String>>> replies = new ArrayList<>();

        private Stub reply(int status, String body) {
            replies.add(CompletableFuture.completedFuture(new Reply(status, body)));
            return this;
        }

        private Stub failed(Throwable error) {
            replies.add(CompletableFuture.failedFuture(error));
            return this;
        }

        private Atalanta client() {
            AlpacaHttpTransport paper = new AlpacaHttpTransport(
                    LOCAL, KEY, SECRET, Duration.ofSeconds(20),
                    (request, handler) -> CompletableFuture.failedFuture(
                            new AssertionError("Unexpected paper API call")));
            CryptoMarketDataTransport data = new CryptoMarketDataTransport(
                    DATA, KEY, SECRET, Duration.ofSeconds(20),
                    (request, handler) -> {
                        int index = requests.size();
                        requests.add(request);
                        if (index >= replies.size()) {
                            return CompletableFuture.failedFuture(
                                    new AssertionError("Unexpected additional market-data page"));
                        }
                        return replies.get(index);
                    });
            return new Atalanta(paper, data);
        }
    }

    private static CryptoBarQuery query() {
        return CryptoBarQuery.of(
                List.of("BTC/USD", "ETH/USD"), new CryptoTimeframe("1Min"));
    }

    @Test
    void readsPreciseBarsAndSourceNanosecondsWithoutFloatingPointLoss() {
        Stub stub = new Stub().reply(200, FIRST);
        Result<CryptoBarPage> outcome = stub.client().cryptoBars(query())
                .toCompletableFuture().join();
        assertThat(outcome).isInstanceOf(Result.Ok.class);
        CryptoBarPage page = ((Result.Ok<CryptoBarPage>) outcome).value();
        assertThat(page.barCount()).isEqualTo(1);
        assertThat(page.bars()).containsKeys("BTC/USD", "ETH/USD");
        assertThat(page.bars().get("ETH/USD")).isEmpty();
        assertThat(page.nextPageToken()).isEqualTo("ABC+/==");
        assertThat(page.continuation()).contains("ABC+/==");

        CryptoBar bar = page.bars().get("BTC/USD").get(0);
        assertThat(bar.timestamp()).isEqualTo("2026-10-02T00:00:00.123456789Z");
        assertThat(bar.open()).isEqualByComparingTo(new BigDecimal("12345678901234567890.12345678901234567890"));
        assertThat(bar.high()).isEqualByComparingTo(new BigDecimal("12345678901234567891.12345678901234567890"));
        assertThat(bar.low()).isEqualByComparingTo(new BigDecimal("12345678901234567889.000000000000000001"));
        assertThat(bar.close()).isEqualByComparingTo(new BigDecimal("12345678901234567890.000000000000000002"));
        assertThat(bar.volume()).isEqualByComparingTo(new BigDecimal("0.000000000000000000123456789"));
        assertThat(bar.tradeCount()).isEqualTo(new BigInteger("18446744073709551616"));
        assertThat(bar.vwap()).isNull();
        assertThat(bar.supplied("vw")).isTrue();
        assertThat(bar.additionalFields()).containsKey("future");
        assertThat(((Map<?, ?>) bar.additionalFields().get("future")).get("ratio"))
                .isEqualTo(new BigDecimal("0.1234567890123456789012345"));
        assertThat(page.additionalFields().get("provider_version"))
                .isEqualTo(new BigInteger("987654321098765432109876543210"));
    }

    @Test
    void sendsOnlyOneAuthenticatedMarketDataGetWithExactUriAndNoTradeMutation() {
        Stub stub = new Stub().reply(200, FIRST);
        CryptoBarQuery request = query().withLocation(CryptoLocation.EU_1)
                .withStart("2026-10-02T00:00:00.123456789Z")
                .withEnd("2026-10-02T03:00:00.123456789+03:00")
                .withLimit(10000)
                .withSort(CryptoBarQuery.Sort.DESC)
                .withPageToken("ABC+/==");

        stub.client().cryptoBars(request).toCompletableFuture().join();
        assertThat(stub.requests).hasSize(1);
        HttpRequest sent = stub.requests.get(0);
        assertThat(sent.method()).isEqualTo("GET");
        assertThat(sent.uri().toASCIIString())
                .isEqualTo("https://data.alpaca.markets/v1beta3/crypto/eu-1/bars"
                        + "?symbols=BTC%2FUSD%2CETH%2FUSD"
                        + "&timeframe=1Min"
                        + "&start=2026-10-02T00%3A00%3A00.123456789Z"
                        + "&end=2026-10-02T03%3A00%3A00.123456789%2B03%3A00"
                        + "&limit=10000&sort=desc&page_token=ABC%2B%2F%3D%3D");
        assertThat(sent.timeout()).contains(Duration.ofSeconds(20));
        assertThat(sent.headers().firstValue("APCA-API-KEY-ID")).contains(KEY);
        assertThat(sent.headers().firstValue("APCA-API-SECRET-KEY")).contains(SECRET);
        assertThat(sent.headers().firstValue("Accept")).contains("application/json");
        assertThat(sent.headers().firstValue("Authorization")).isEmpty();
        assertThat(sent.headers().firstValue("Content-Type")).isEmpty();
        assertThat(sent.uri().getHost()).isEqualTo("data.alpaca.markets");
    }

    @Test
    void decodesMissingAndNullOptionalFieldsDifferently() {
        Stub stub = new Stub().reply(200, LAST);
        CryptoBar bar = ((Result.Ok<CryptoBarPage>) stub.client()
                .cryptoBars(query()).toCompletableFuture().join())
                .value().bars().get("BTC/USD").get(0);
        assertThat(bar.tradeCount()).isNull();
        assertThat(bar.supplied("n")).isTrue();
        assertThat(bar.vwap()).isEqualByComparingTo("29000.125");

        Stub missing = new Stub().reply(200, LAST
                .replace(", \"n\":null, \"vw\":29000.125", ""));
        CryptoBar noOptional = ((Result.Ok<CryptoBarPage>) missing.client()
                .cryptoBars(query()).toCompletableFuture().join())
                .value().bars().get("BTC/USD").get(0);
        assertThat(noOptional.tradeCount()).isNull();
        assertThat(noOptional.vwap()).isNull();
        assertThat(noOptional.supplied("n")).isFalse();
        assertThat(noOptional.supplied("vw")).isFalse();
    }

    @Test
    void validatesEmptyProviderResponsesAsSuccessfulEmptyPages() {
        for (String json : List.of(
                "{\"bars\":{}}",
                "{\"bars\":{},\"next_page_token\":null}",
                "{\"bars\":{},\"next_page_token\":\"\"}",
                "{\"bars\":{\"BTC/USD\":[]}}")) {
            Stub stub = new Stub().reply(200, json);
            Result<CryptoBarPage> result = stub.client().cryptoBars(query())
                    .toCompletableFuture().join();
            assertThat(result).isInstanceOf(Result.Ok.class);
            CryptoBarPage page = ((Result.Ok<CryptoBarPage>) result).value();
            assertThat(page.barCount()).isZero();
            assertThat(page.continuation()).isEmpty();
        }
    }

    @Test
    void rejectsMalformedRootFieldsBadNumbersCountsAndTimestamps() {
        for (String json : List.of(
                "[]", "{}", "{broken", "null", "42",
                "{\"bars\":null}", "{\"bars\":[]}",
                "{\"bars\":{\"BTC/USD\":{}}}",
                "{\"bars\":{\"BTC/USD\":[null]}}",
                "{\"bars\":{\"BTC/USD\":[{\"t\":\"2026-10-01T00:00:00Z\",\"o\":1}]}}",
                "{\"bars\":{\"BTC/USD\":[{\"t\":\"2026-10-01T00:00:00Z\",\"o\":1,\"h\":2,\"l\":1,\"c\":1,\"v\":\"SECRET_INVALID_PRICE\"}]}}",
                LAST.replace("\"n\":null", "\"n\":1.2"),
                LAST.replace("\"n\":null", "\"n\":-1"),
                LAST.replace("\"vw\":29000.125", "\"vw\":false"),
                LAST.replace("2026-10-02T00:01:00.123456789Z", "not-time"),
                "{\"bars\":{},\"next_page_token\":123}")) {
            Stub stub = new Stub().reply(200, json);
            Result<CryptoBarPage> result = stub.client().cryptoBars(query())
                    .toCompletableFuture().join();
            assertThat(result).as(json).isInstanceOf(Result.Err.class);
            AlpacaError error = ((Result.Err<CryptoBarPage>) result).error();
            assertThat(error).isInstanceOf(AlpacaError.InvalidResponse.class);
            assertThat(error.toString()).doesNotContain("SECRET", KEY);
        }
    }

    @Test
    void knownHttpErrorsRemainTypedAndNeverExposeProviderBody() {
        for (int status : List.of(401, 403, 404, 429, 503)) {
            Stub stub = new Stub().reply(status,
                    "{\"message\":\"SECRET_PRIVATE_MARKET_DATA\"}");
            Result<CryptoBarPage> result = stub.client().cryptoBars(query())
                    .toCompletableFuture().join();
            assertThat(result).isInstanceOf(Result.Err.class);
            AlpacaError error = ((Result.Err<CryptoBarPage>) result).error();
            switch (status) {
                case 401 -> assertThat(error).isInstanceOf(AlpacaError.Authentication.class);
                case 403 -> assertThat(error).isInstanceOf(AlpacaError.Forbidden.class);
                case 429 -> assertThat(error).isInstanceOf(AlpacaError.RateLimited.class);
                default -> assertThat(error).isInstanceOf(AlpacaError.HttpFailure.class);
            }
            assertThat(result.toString()).doesNotContain("SECRET_PRIVATE", SECRET);
            assertThat(stub.requests).hasSize(1);
        }
    }

    @Test
    void timeoutAndNetworkFailuresAreTypedAndNeverRetry() {
        for (Throwable error : List.of(new IOException("SENSITIVE_URL"),
                new HttpTimeoutException("SECRET_TIMEOUT"))) {
            Stub stub = new Stub().failed(error);
            Result<CryptoBarPage> result = stub.client().cryptoBars(query())
                    .toCompletableFuture().join();
            assertThat(result).isInstanceOf(Result.Err.class);
            AlpacaError failure = ((Result.Err<CryptoBarPage>) result).error();
            if (error instanceof HttpTimeoutException) {
                assertThat(failure).isInstanceOf(AlpacaError.Timeout.class);
            } else {
                assertThat(failure).isInstanceOf(AlpacaError.TransportFailure.class);
            }
            assertThat(result.toString()).doesNotContain("SENSITIVE", "SECRET_TIMEOUT");
            assertThat(stub.requests).hasSize(1);
        }
    }

    @Test
    void paginatesAcrossShortEmptyPagesUntilProviderEnd() {
        Stub stub = new Stub().reply(200, EMPTY_MORE)
                .reply(200, FIRST)
                .reply(200, LAST);
        CryptoBarScan scan = stub.client().cryptoBarsPages(query(), 5)
                .toCompletableFuture().join();
        assertThat(scan).isInstanceOf(CryptoBarScan.Stopped.class);
        var complete = (CryptoBarScan.Stopped) scan;
        assertThat(complete.pages()).hasSize(3);
        assertThat(complete.pages().get(0).barCount()).isZero();
        assertThat(complete.pages().get(1).barCount()).isEqualTo(1);
        assertThat(complete.pages().get(2).barCount()).isEqualTo(1);
        assertThat(complete.exhausted()).isTrue();
        assertThat(stub.requests).hasSize(3);
        assertThat(stub.requests.get(1).uri().getRawQuery()).contains("page_token=MORE");
        assertThat(stub.requests.get(2).uri().getRawQuery()).contains("page_token=ABC%2B%2F%3D%3D");
        assertThatThrownBy(() -> complete.pages().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void boundedScanPreservesUnconsumedContinuationAndDoesNotFetchExtraPage() {
        Stub stub = new Stub().reply(200, EMPTY_MORE);
        CryptoBarScan scan = stub.client().cryptoBarsPages(query(), 1)
                .toCompletableFuture().join();
        var stopped = (CryptoBarScan.Stopped) scan;
        assertThat(stopped.pages()).hasSize(1);
        assertThat(stopped.exhausted()).isFalse();
        assertThat(stopped.continuation()).contains("MORE");
        assertThat(stub.requests).hasSize(1);
    }

    @Test
    void laterPageErrorPreservesPreviousSuccessfulPages() {
        Stub stub = new Stub().reply(200, FIRST)
                .reply(429, "{\"message\":\"SECRET_PRIVATE_DATA\"}");
        CryptoBarScan scan = stub.client().cryptoBarsPages(query(), 4)
                .toCompletableFuture().join();
        assertThat(scan).isInstanceOf(CryptoBarScan.Failed.class);
        var failed = (CryptoBarScan.Failed) scan;
        assertThat(failed.pages()).hasSize(1);
        assertThat(failed.pages().get(0).continuation()).contains("ABC+/==");
        assertThat(failed.error()).isInstanceOf(AlpacaError.RateLimited.class);
        assertThat(stub.requests).hasSize(2);
        assertThat(failed.toString()).doesNotContain("SECRET_PRIVATE_DATA");
    }

    @Test
    void repeatedPageTokenIsDetectedWithoutUnboundedRequestLoop() {
        Stub stub = new Stub().reply(200, EMPTY_MORE)
                .reply(200, EMPTY_MORE);
        CryptoBarScan scan = stub.client().cryptoBarsPages(query(), 20)
                .toCompletableFuture().join();
        assertThat(scan).isInstanceOf(CryptoBarScan.Failed.class);
        var failed = (CryptoBarScan.Failed) scan;
        assertThat(failed.pages()).hasSize(2);
        assertThat(failed.error()).isInstanceOf(AlpacaError.InvalidResponse.class);
        assertThat(stub.requests).hasSize(2);
    }

    @Test
    void initialOpaqueResumeTokenIsIncludedAndRepetitionDetected() {
        Stub stub = new Stub().reply(200, EMPTY_MORE);
        CryptoBarScan scan = stub.client().cryptoBarsPages(
                query().withPageToken("MORE"), 3)
                .toCompletableFuture().join();
        assertThat(scan).isInstanceOf(CryptoBarScan.Failed.class);
        assertThat(stub.requests.get(0).uri().getRawQuery()).contains("page_token=MORE");
        assertThat(stub.requests).hasSize(1);
    }

    @Test
    void rejectsInvalidPageCapsAndPreventsAnyRequest() {
        Stub stub = new Stub();
        Atalanta client = stub.client();
        for (int pages : new int[]{0, -1, 21, Integer.MAX_VALUE}) {
            assertThatIllegalArgumentException().isThrownBy(
                    () -> client.cryptoBarsPages(query(), pages));
        }
        assertThat(stub.requests).isEmpty();
    }

    @Test
    void invalidTransportHostsAndCredentialsNeverConstructClient() {
        for (URI host : List.of(URI.create("https://api.alpaca.markets"),
                URI.create("https://paper-api.alpaca.markets"),
                URI.create("http://example.com"),
                URI.create("https://data.alpaca.markets/equities"),
                URI.create("https://data.alpaca.markets?x=1"))) {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new CryptoMarketDataTransport(
                            host, KEY, SECRET, Duration.ofSeconds(20),
                            (request, handler) -> CompletableFuture.failedFuture(
                                    new AssertionError("unexpected"))));
        }
        assertThatIllegalArgumentException().isThrownBy(() ->
                CryptoMarketDataTransport.alpaca("\nINVALID", SECRET));
    }

    private record Reply(int statusCode, String body) implements HttpResponse<String> {
        public HttpRequest request() { return null; }
        public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (name, value) -> true); }
        public URI uri() { return DATA; }
        public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        public Optional<SSLSession> sslSession() { return Optional.empty(); }
    }
}
