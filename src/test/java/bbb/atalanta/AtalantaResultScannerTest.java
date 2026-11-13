package bbb.atalanta;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class AtalantaResultScannerTest {
    private static final URI PAPER_LOOPBACK = URI.create("http://127.0.0.1:8765");
    private static final URI DATA = URI.create("https://data.alpaca.markets");
    private static final String BAR = """
            {"bars":{"BTC/USD":[{
              "t":"2026-10-02T00:00:00.123456789Z",
              "o":29000.1,"h":29000.2,"l":29000,"c":29000.1,
              "v":2.5,"n":1,"vw":29000.125
            }]},"next_page_token":null}
            """;
    private static final String TRADE = """
            {"trades":{"BTC/USD":[{
              "i":18446744073709551616,
              "p":29000.125,"s":0.000000123456789,
              "t":"2026-10-02T00:00:00.123456789Z","tks":"B"
            }]},"next_page_token":null}
            """;
    private static final String QUOTE = """
            {"quotes":{"BTC/USD":[{
              "t":"2026-10-02T00:00:00.123456789Z",
              "ap":29000.125,"as":0.0000000000123456789,
              "bp":29000.120,"bs":2.50
            }]},"next_page_token":null}
            """;

    private record Response(int statusCode, String body) implements HttpResponse<String> {
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }
        @Override public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (name, value) -> true);
        }
        @Override public URI uri() { return DATA; }
        @Override public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
    }

    private static Atalanta fakeClient(List<HttpRequest> requests, AtomicInteger paperRequests) {
        AlpacaHttpTransport paper = new AlpacaHttpTransport(
                PAPER_LOOPBACK, "TEST-KEY", "TEST-SECRET", Duration.ofSeconds(20),
                (request, handler) -> {
                    paperRequests.incrementAndGet();
                    return CompletableFuture.failedFuture(
                            new AssertionError("No paper API calls permitted in market data scan"));
                });
        CryptoMarketDataTransport data = new CryptoMarketDataTransport(
                DATA, "TEST-KEY", "TEST-SECRET", Duration.ofSeconds(20),
                (request, handler) -> {
                    requests.add(request);
                    String path = request.uri().getPath();
                    String body = path.endsWith("/bars") ? BAR
                            : path.endsWith("/trades") ? TRADE
                            : path.endsWith("/quotes") ? QUOTE
                            : "{\"invalid\":true}";
                    return CompletableFuture.completedFuture(new Response(200, body));
                });
        return new Atalanta(paper, data);
    }

    @Test
    void allThreeRealEndpointDecodersUseOneResultBasedScanner() {
        var requests = new ArrayList<HttpRequest>();
        var paperCalls = new AtomicInteger();
        Atalanta client = fakeClient(requests, paperCalls);
        var barsDelivered = new ArrayList<CryptoBarPage>();
        var tradesDelivered = new ArrayList<CryptoTradePage>();
        var quotesDelivered = new ArrayList<CryptoQuotePage>();

        Result<ScanSummary> bars = client.scanBars(
                CryptoBarQuery.of(List.of("BTC/USD"), CryptoTimeframe.minutes(1)),
                3, page -> {
                    barsDelivered.add(page);
                    return CompletableFuture.completedFuture(null);
                }).toCompletableFuture().join();
        Result<ScanSummary> trades = client.scanTrades(
                CryptoTradeQuery.of(List.of("BTC/USD")),
                3, page -> {
                    tradesDelivered.add(page);
                    return CompletableFuture.completedFuture(null);
                }).toCompletableFuture().join();
        Result<ScanSummary> quotes = client.scanQuotes(
                CryptoQuoteQuery.of(List.of("BTC/USD")),
                3, page -> {
                    quotesDelivered.add(page);
                    return CompletableFuture.completedFuture(null);
                }).toCompletableFuture().join();

        var expected = Result.ok(new ScanSummary(1, 1, Optional.empty()));
        assertThat(bars).isEqualTo(expected);
        assertThat(trades).isEqualTo(expected);
        assertThat(quotes).isEqualTo(expected);
        assertThat(barsDelivered).hasSize(1);
        assertThat(tradesDelivered).hasSize(1);
        assertThat(quotesDelivered).hasSize(1);
        assertThat(tradesDelivered.get(0).trades().get("BTC/USD").get(0).price())
                .isEqualByComparingTo("29000.125");
        assertThat(quotesDelivered.get(0).quotes().get("BTC/USD").get(0).askSize())
                .isEqualByComparingTo("0.0000000000123456789");
        assertThat(paperCalls).hasValue(0);
        assertThat(requests).hasSize(3);
        assertThat(requests).extracting(req -> req.uri().getPath()).containsExactly(
                "/v1beta3/crypto/us/bars",
                "/v1beta3/crypto/us/trades",
                "/v1beta3/crypto/us/quotes");
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.uri().getHost()).isEqualTo("data.alpaca.markets");
            assertThat(request.uri().getRawQuery()).contains("symbols=BTC%2FUSD");
        });
        assertThat(requests.get(0).uri().getRawQuery()).contains("timeframe=1Min");
        assertThat(requests.get(1).uri().getRawQuery()).doesNotContain("timeframe");
        assertThat(requests.get(2).uri().getRawQuery()).doesNotContain("timeframe");
    }

    @Test
    void nullQueriesAreRejectedBeforeAnyHttpCall() {
        var requests = new ArrayList<HttpRequest>();
        var paperCalls = new AtomicInteger();
        Atalanta client = fakeClient(requests, paperCalls);
        assertThatNullPointerException().isThrownBy(
                () -> client.scanBars(null, 1, ignored -> CompletableFuture.completedFuture(null)));
        assertThatNullPointerException().isThrownBy(
                () -> client.scanTrades(null, 1, ignored -> CompletableFuture.completedFuture(null)));
        assertThatNullPointerException().isThrownBy(
                () -> client.scanQuotes(null, 1, ignored -> CompletableFuture.completedFuture(null)));
        assertThat(requests).isEmpty();
        assertThat(paperCalls).hasValue(0);
    }

    @Test
    void allThreePublicScansWaitForDurableAcknowledgementEvenOnFinalPage() {
        var requests = new ArrayList<HttpRequest>();
        var paperCalls = new AtomicInteger();
        var client = fakeClient(requests, paperCalls);

        var barsAck = new CompletableFuture<Void>();
        var tradesAck = new CompletableFuture<Void>();
        var quotesAck = new CompletableFuture<Void>();

        var bars = client.scanBars(
                CryptoBarQuery.of(List.of("BTC/USD"), CryptoTimeframe.minutes(1)),
                2, page -> barsAck).toCompletableFuture();
        var trades = client.scanTrades(
                CryptoTradeQuery.of(List.of("BTC/USD")),
                2, page -> tradesAck).toCompletableFuture();
        var quotes = client.scanQuotes(
                CryptoQuoteQuery.of(List.of("BTC/USD")),
                2, page -> quotesAck).toCompletableFuture();

        assertThat(requests).hasSize(3);
        assertThat(paperCalls).hasValue(0);
        assertThat(bars).isNotDone();
        assertThat(trades).isNotDone();
        assertThat(quotes).isNotDone();

        barsAck.complete(null);
        assertThat(bars.join()).isEqualTo(Result.ok(new ScanSummary(1, 1, Optional.empty())));
        assertThat(trades).isNotDone();
        assertThat(quotes).isNotDone();

        tradesAck.complete(null);
        assertThat(trades.join()).isEqualTo(Result.ok(new ScanSummary(1, 1, Optional.empty())));
        assertThat(quotes).isNotDone();

        quotesAck.complete(null);
        assertThat(quotes.join()).isEqualTo(Result.ok(new ScanSummary(1, 1, Optional.empty())));
        assertThat(requests).hasSize(3);
    }
}
