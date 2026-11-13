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

class AtalantaCryptoQuotesTest {
    private static final URI DATA = URI.create("https://data.alpaca.markets");
    private static final URI LOCAL = URI.create("http://127.0.0.1:8765");
    private static final String KEY = "SYNTHETIC-ID";
    private static final String SECRET = "SYNTHETIC-SECRET";

    private static final String FIRST = """
            {
              "quotes": {
                "BTC/USD": [{
                  "t":"2026-10-02T00:00:00.123456789Z",
                  "ap":12345678901234567890.12345678901234567890,
                  "as":0.000000000000000000123456789,
                  "bp":12345678901234567889.000000000000000001,
                  "bs":3.000000000000000001,
                  "future":{"status":"NEW","ratio":0.1234567890123456789012345}
                }],
                "ETH/USD": []
              },
              "next_page_token":"ABC+/==",
              "provider_version":987654321098765432109876543210
            }
            """;
    private static final String LAST = """
            {"quotes":{"BTC/USD":[
              {"t":"2026-10-02T00:00:01.234567891+00:00",
               "ap":29000.5,"as":2,"bp":29000.4,"bs":0.25}
            ]},"next_page_token":null}
            """;
    private static final String EMPTY_MORE = """
            {"quotes":{},"next_page_token":"MORE"}
            """;

    private static final class Stub {
        private final List<HttpRequest> requests = new ArrayList<>();
        private final List<CompletableFuture<HttpResponse<String>>> replies = new ArrayList<>();

        Stub reply(int status, String body) {
            replies.add(CompletableFuture.completedFuture(new Reply(status, body)));
            return this;
        }

        Stub failed(Throwable cause) {
            replies.add(CompletableFuture.failedFuture(cause));
            return this;
        }

        Atalanta client() {
            AlpacaHttpTransport paper = new AlpacaHttpTransport(
                    LOCAL, KEY, SECRET, Duration.ofSeconds(20),
                    (request, handler) -> CompletableFuture.failedFuture(
                            new AssertionError("Quotes must never contact paper trading")));
            CryptoMarketDataTransport data = new CryptoMarketDataTransport(
                    DATA, KEY, SECRET, Duration.ofSeconds(20),
                    (request, handler) -> {
                        int next = requests.size();
                        requests.add(request);
                        if (next >= replies.size()) {
                            return CompletableFuture.failedFuture(
                                    new AssertionError("Unexpected extra quote page request"));
                        }
                        return replies.get(next);
                    });
            return new Atalanta(paper, data);
        }
    }

    private static CryptoQuoteQuery query() {
        return CryptoQuoteQuery.of(List.of("BTC/USD", "ETH/USD"));
    }

    private static CryptoQuotePage ok(Result<CryptoQuotePage> response) {
        assertThat(response).isInstanceOf(Result.Ok.class);
        return ((Result.Ok<CryptoQuotePage>) response).value();
    }

    private static AlpacaError error(Result<CryptoQuotePage> response) {
        assertThat(response).isInstanceOf(Result.Err.class);
        return ((Result.Err<CryptoQuotePage>) response).error();
    }

    @Test
    void exactPricesSizesTimestampAndNestedProviderExtensionsSurviveDecoding() {
        Stub stub = new Stub().reply(200, FIRST);
        CryptoQuotePage page = ok(stub.client().cryptoQuotes(query()).toCompletableFuture().join());
        assertThat(page.quoteCount()).isEqualTo(1);
        assertThat(page.quotes()).containsKeys("BTC/USD", "ETH/USD");
        assertThat(page.quotes().get("ETH/USD")).isEmpty();
        assertThat(page.continuation()).contains("ABC+/==");

        CryptoQuote quote = page.quotes().get("BTC/USD").get(0);
        assertThat(quote.timestamp()).isEqualTo("2026-10-02T00:00:00.123456789Z");
        assertThat(quote.askPrice()).isEqualByComparingTo("12345678901234567890.12345678901234567890");
        assertThat(quote.askSize()).isEqualByComparingTo("0.000000000000000000123456789");
        assertThat(quote.bidPrice()).isEqualByComparingTo("12345678901234567889.000000000000000001");
        assertThat(quote.bidSize()).isEqualByComparingTo("3.000000000000000001");
        assertThat(quote.supplied("ap")).isTrue();
        assertThat(quote.supplied("future")).isTrue();
        assertThat(quote.additionalFields()).containsKey("future");
        assertThat(((Map<?, ?>) quote.additionalFields().get("future")).get("ratio"))
                .isEqualTo(new BigDecimal("0.1234567890123456789012345"));
        assertThat(page.additionalFields().get("provider_version"))
                .isEqualTo(new BigInteger("987654321098765432109876543210"));

        assertThatThrownBy(() -> page.quotes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> page.quotes().get("BTC/USD").clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> quote.additionalFields().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void encodesExactReadOnlyHttpsRequestWithoutTimeframeAndWithoutPaperOrderMutation() {
        Stub stub = new Stub().reply(200, FIRST);
        var explicit = query().withLocation(CryptoLocation.EU_1)
                .withStart("2026-10-02T00:00:00.123456789Z")
                .withEnd("2026-10-02T03:00:00.123456789+03:00")
                .withLimit(10000)
                .withSort(CryptoQuoteQuery.Sort.DESC)
                .withPageToken("ABC+/==");

        ok(stub.client().cryptoQuotes(explicit).toCompletableFuture().join());
        assertThat(stub.requests).hasSize(1);
        HttpRequest sent = stub.requests.get(0);
        assertThat(sent.method()).isEqualTo("GET");
        assertThat(sent.uri().toASCIIString())
                .isEqualTo("https://data.alpaca.markets/v1beta3/crypto/eu-1/quotes"
                        + "?symbols=BTC%2FUSD%2CETH%2FUSD"
                        + "&start=2026-10-02T00%3A00%3A00.123456789Z"
                        + "&end=2026-10-02T03%3A00%3A00.123456789%2B03%3A00"
                        + "&limit=10000&sort=desc&page_token=ABC%2B%2F%3D%3D");
        assertThat(sent.uri().getRawQuery()).doesNotContain("timeframe");
        assertThat(sent.uri().getHost()).isEqualTo("data.alpaca.markets");
        assertThat(sent.timeout()).contains(Duration.ofSeconds(20));
        assertThat(sent.headers().firstValue("APCA-API-KEY-ID")).contains(KEY);
        assertThat(sent.headers().firstValue("APCA-API-SECRET-KEY")).contains(SECRET);
        assertThat(sent.headers().firstValue("Accept")).contains("application/json");
        assertThat(sent.headers().firstValue("Content-Type")).isEmpty();
        assertThat(sent.bodyPublisher()).isEmpty();
    }

    @Test
    void defaultRequestUsesOnlySymbolFilterAndPreservesProviderDefaults() {
        Stub stub = new Stub().reply(200, LAST);
        ok(stub.client().cryptoQuotes(query()).toCompletableFuture().join());
        assertThat(stub.requests.get(0).uri().toASCIIString())
                .isEqualTo("https://data.alpaca.markets/v1beta3/crypto/us/quotes"
                        + "?symbols=BTC%2FUSD%2CETH%2FUSD");
    }

    @Test
    void sparseAndEmptyPagesAreValidAndNullTokenMeansExhausted() {
        for (String body : List.of(
                "{\"quotes\":{}}",
                "{\"quotes\":{},\"next_page_token\":null}",
                "{\"quotes\":{},\"next_page_token\":\"\"}",
                "{\"quotes\":{\"BTC/USD\":[]}}")) {
            Stub stub = new Stub().reply(200, body);
            CryptoQuotePage page = ok(stub.client().cryptoQuotes(query())
                    .toCompletableFuture().join());
            assertThat(page.quoteCount()).isZero();
            assertThat(page.continuation()).isEmpty();
        }
    }

    @Test
    void rejectsMalformedProviderFieldsWithoutLeakingProviderInput() {
        for (String body : List.of(
                "[]", "{}", "{broken", "null", "42",
                "{\"quotes\":null}", "{\"quotes\":[]}",
                "{\"quotes\":{\"BTC/USD\":{}}}",
                "{\"quotes\":{\"BTC/USD\":[null]}}",
                "{\"quotes\":{\"BTC/USD\":[{\"t\":\"2026-10-01T00:00:00Z\",\"ap\":1}]}}",
                LAST.replace("\"ap\":29000.5", "\"ap\":\"SECRET_PRICE\""),
                LAST.replace("\"as\":2", "\"as\":null"),
                LAST.replace("\"bp\":29000.4", "\"bp\":true"),
                LAST.replace("\"bs\":0.25", "\"bs\":{}"),
                LAST.replace("\"ap\":29000.5", "\"ap\":-1"),
                LAST.replace("\"bs\":0.25", "\"bs\":-0.25"),
                LAST.replace("\"t\":\"2026-10-02T00:00:01.234567891+00:00\"", "\"t\":15"),
                LAST.replace("2026-10-02T00:00:01.234567891+00:00", "not-time"),
                "{\"quotes\":{},\"next_page_token\":123}",
                "{\"quotes\":{\"BTC/USD\":[1]}}",
                "{\"quotes\":{\"BTC/../../orders\":[]}}")) {
            Stub stub = new Stub().reply(200, body);
            AlpacaError failure = error(stub.client().cryptoQuotes(query()).toCompletableFuture().join());
            assertThat(failure).as(body).isInstanceOf(AlpacaError.InvalidResponse.class);
            assertThat(failure.toString()).doesNotContain("SECRET_PRICE", KEY);
        }
    }

    @Test
    void preservesUnknownNullVersusAbsentWithinNestedProviderFields() {
        Stub stub = new Stub().reply(200,
                LAST.replace("\"bs\":0.25}", "\"bs\":0.25,\"nullable_future\":null}"));
        CryptoQuote quote = ok(stub.client().cryptoQuotes(query()).toCompletableFuture().join())
                .quotes().get("BTC/USD").get(0);
        assertThat(quote.supplied("nullable_future")).isTrue();
        assertThat(quote.additionalFields()).containsEntry("nullable_future", null);

        Stub omitted = new Stub().reply(200, LAST);
        CryptoQuote clean = ok(omitted.client().cryptoQuotes(query()).toCompletableFuture().join())
                .quotes().get("BTC/USD").get(0);
        assertThat(clean.supplied("nullable_future")).isFalse();
    }

    @Test
    void typedHttpFailuresPreservePrivacyAndDoNotRetry() {
        for (int status : List.of(400, 401, 403, 404, 429, 500, 503)) {
            Stub stub = new Stub().reply(status, "{\"message\":\"SECRET_PROVIDER_PAYLOAD\"}");
            AlpacaError problem = error(stub.client().cryptoQuotes(query()).toCompletableFuture().join());
            switch (status) {
                case 401 -> assertThat(problem).isInstanceOf(AlpacaError.Authentication.class);
                case 403 -> assertThat(problem).isInstanceOf(AlpacaError.Forbidden.class);
                case 429 -> assertThat(problem).isInstanceOf(AlpacaError.RateLimited.class);
                default -> assertThat(problem).isInstanceOf(AlpacaError.HttpFailure.class);
            }
            assertThat(problem.toString()).doesNotContain("SECRET_PROVIDER_PAYLOAD", SECRET);
            assertThat(stub.requests).hasSize(1);
        }
    }

    @Test
    void timeoutAndNetworkFailureAreTypedAndDoNotRetry() {
        for (Throwable failure : List.of(new IOException("SECRET_HTTP"),
                new HttpTimeoutException("SECRET_TIMEOUT"))) {
            Stub stub = new Stub().failed(failure);
            AlpacaError error = error(stub.client().cryptoQuotes(query()).toCompletableFuture().join());
            if (failure instanceof HttpTimeoutException) {
                assertThat(error).isInstanceOf(AlpacaError.Timeout.class);
            } else {
                assertThat(error).isInstanceOf(AlpacaError.TransportFailure.class);
            }
            assertThat(error.toString()).doesNotContain("SECRET_");
            assertThat(stub.requests).hasSize(1);
        }
    }

    @Test
    void boundedPaginationHonorsShortAndEmptyPagesWithTokens() {
        Stub stub = new Stub().reply(200, EMPTY_MORE)
                .reply(200, FIRST)
                .reply(200, LAST);
        CryptoQuoteScan scan = stub.client().cryptoQuotesPages(query(), 5)
                .toCompletableFuture().join();
        assertThat(scan).isInstanceOf(CryptoQuoteScan.Stopped.class);
        var stopped = (CryptoQuoteScan.Stopped) scan;
        assertThat(stopped.pages()).hasSize(3);
        assertThat(stopped.pages().get(0).quoteCount()).isZero();
        assertThat(stopped.pages().get(1).quoteCount()).isEqualTo(1);
        assertThat(stopped.pages().get(2).quoteCount()).isEqualTo(1);
        assertThat(stopped.exhausted()).isTrue();
        assertThat(stub.requests).hasSize(3);
        assertThat(stub.requests.get(1).uri().getRawQuery()).contains("page_token=MORE");
        assertThat(stub.requests.get(2).uri().getRawQuery())
                .contains("page_token=ABC%2B%2F%3D%3D");
    }

    @Test
    void boundedScanReturnsExplicitContinuationWithoutUnboundedHistory() {
        Stub stub = new Stub().reply(200, EMPTY_MORE);
        var scan = (CryptoQuoteScan.Stopped) stub.client()
                .cryptoQuotesPages(query(), 1).toCompletableFuture().join();
        assertThat(scan.pages()).hasSize(1);
        assertThat(scan.exhausted()).isFalse();
        assertThat(scan.continuation()).contains("MORE");
        assertThat(stub.requests).hasSize(1);
    }

    @Test
    void failedPagePreservesPreviouslyValidPageAndTypedError() {
        Stub stub = new Stub().reply(200, FIRST)
                .reply(429, "{\"message\":\"SECRET_PROVIDER_ERROR\"}");
        var scan = stub.client().cryptoQuotesPages(query(), 4)
                .toCompletableFuture().join();
        assertThat(scan).isInstanceOf(CryptoQuoteScan.Failed.class);
        var failed = (CryptoQuoteScan.Failed) scan;
        assertThat(failed.pages()).hasSize(1);
        assertThat(failed.pages().get(0).continuation()).contains("ABC+/==");
        assertThat(failed.error()).isInstanceOf(AlpacaError.RateLimited.class);
        assertThat(failed.toString()).doesNotContain("SECRET_PROVIDER_ERROR");
        assertThat(stub.requests).hasSize(2);
    }

    @Test
    void detectsRepeatedTokensAndInitialTokenCycle() {
        Stub repeat = new Stub().reply(200, EMPTY_MORE).reply(200, EMPTY_MORE);
        var scan = repeat.client().cryptoQuotesPages(query(), 20).toCompletableFuture().join();
        assertThat(scan).isInstanceOf(CryptoQuoteScan.Failed.class);
        var failed = (CryptoQuoteScan.Failed) scan;
        assertThat(failed.pages()).hasSize(2);
        assertThat(failed.error()).isInstanceOf(AlpacaError.InvalidResponse.class);
        assertThat(repeat.requests).hasSize(2);

        Stub resume = new Stub().reply(200, EMPTY_MORE);
        var resumeScan = resume.client().cryptoQuotesPages(
                query().withPageToken("MORE"), 3).toCompletableFuture().join();
        assertThat(resumeScan).isInstanceOf(CryptoQuoteScan.Failed.class);
        assertThat(resume.requests).hasSize(1);
    }

    @Test
    void rejectsInvalidPageBoundsAndNullQueryBeforeSending() {
        Stub stub = new Stub();
        Atalanta client = stub.client();
        for (int invalid : new int[]{0, -1, 21, Integer.MAX_VALUE}) {
            assertThatIllegalArgumentException().isThrownBy(
                    () -> client.cryptoQuotesPages(query(), invalid));
        }
        assertThatThrownBy(() -> client.cryptoQuotes(null))
                .isInstanceOf(NullPointerException.class);
        assertThat(stub.requests).isEmpty();
    }

    @Test
    void cannotCallQuotesWithoutMarketDataTransport() {
        AlpacaHttpTransport paper = new AlpacaHttpTransport(
                LOCAL, KEY, SECRET, Duration.ofSeconds(20),
                (request, handler) -> CompletableFuture.failedFuture(
                        new AssertionError("No paper call is expected")));
        Atalanta client = new Atalanta(paper);
        assertThatThrownBy(() -> client.cryptoQuotes(query()))
                .isInstanceOf(IllegalStateException.class);
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
