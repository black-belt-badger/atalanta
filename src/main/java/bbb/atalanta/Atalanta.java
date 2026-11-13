package bbb.atalanta;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Java 17 client for Alpaca paper Trading API (including explicit single-order
 * paper cancellation) and read-only historical crypto Market Data.
 *
 * <p>Remote operations are asynchronous and return functional Results:
 * ordinary provider/network failures are typed {@link Result.Err} values.
 * Unexpected programming faults or cancellation may complete exceptionally.
 *
 * <p>Account, asset-list, position, order-list and order-by-ID reads are
 * supported. Paper order submission and cancellation require explicit calls;
 * there are no live-trading methods or implicit mutations.
 */
public final class Atalanta {

    private final AlpacaHttpTransport transport;
    private final CryptoMarketDataTransport marketData;

    /**
     * Create a paper-only Trading API client with read-only crypto Market Data
     * access, using the same credentials but strictly separate allowed hosts.
     */
    public static Atalanta paper(String apiKeyId, String secretKey) {
        return new Atalanta(AlpacaHttpTransport.paper(apiKeyId, secretKey),
                CryptoMarketDataTransport.alpaca(apiKeyId, secretKey));
    }

    /** Existing package-private seam for the five Trading API contract tests. */
    Atalanta(AlpacaHttpTransport transport) {
        this(transport, null);
    }

    /** Offline fixture seam: inject both independent hosts without real HTTP. */
    Atalanta(AlpacaHttpTransport transport, CryptoMarketDataTransport marketData) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.marketData = marketData;
    }

    /** Fetch the complete, read-only paper-account snapshot (GET /v2/account). */
    public CompletionStage<Result<Account>> account() {
        return transport.get(List.of("v2", "account"), Map.of())
                .thenApply(json -> json.flatMap(AccountDecoder::decode));
    }

    /**
     * List assets using Alpaca's documented defaults: us_equity, all statuses,
     * no exchange or attribute filter. This does not imply a crypto default.
     */
    public CompletionStage<Result<List<Asset>>> assets() {
        return assets(AssetQuery.defaults());
    }

    /** Read GET /v2/assets with explicit, immutable provider query filters. */
    public CompletionStage<Result<List<Asset>>> assets(AssetQuery query) {
        Objects.requireNonNull(query, "query");
        return transport.get(List.of("v2", "assets"), query.toQueryParameters())
                .thenApply(result -> result.flatMap(AssetDecoder::decode));
    }

    /**
     * Retrieve all currently open paper-account positions.
     *
     * <p>GET /v2/positions has no request parameters. An empty portfolio is a
     * successful empty immutable list, not an error. Closed positions are not
     * included and this is not a symbol-by-symbol lookup.
     */
    public CompletionStage<Result<List<Position>>> positions() {
        return transport.get(List.of("v2", "positions"), Map.of())
                .thenApply(result -> result.flatMap(PositionDecoder::decode));
    }

    /**
     * List orders using Alpaca's documented defaults: open orders, 50 results,
     * newest first, no additional filters, no nested-leg roll-up.
     */
    public CompletionStage<Result<List<Order>>> orders() {
        return orders(OrderQuery.defaults());
    }

    /**
     * Read GET /v2/orders with explicit immutable filters and exclusive
     * timestamp/order-ID pagination. This method never submits an order.
     */
    public CompletionStage<Result<List<Order>>> orders(OrderQuery query) {
        Objects.requireNonNull(query, "query");
        return transport.get(List.of("v2", "orders"), query.toQueryParameters())
                .thenApply(result -> result.flatMap(OrderDecoder::decode));
    }

    /**
     * Fetch one order by its Alpaca UUID, omitting the optional nested flag.
     *
     * <p>A missing order (HTTP 404) is represented by a successful empty
     * Optional; other expected provider failures remain typed errors.
     */
    public CompletionStage<Result<Optional<Order>>> order(UUID id) {
        return orderById(id, Map.of());
    }

    /**
     * Fetch one order and explicitly request nested or non-nested leg results.
     * This is a read-only lookup; it cannot create, replace or cancel orders.
     */
    public CompletionStage<Result<Optional<Order>>> order(UUID id, boolean nested) {
        return orderById(id, Map.of("nested", Boolean.toString(nested)));
    }

    private CompletionStage<Result<Optional<Order>>> orderById(
            UUID id, Map<String, String> query) {
        Objects.requireNonNull(id, "id");
        return transport.get(List.of("v2", "orders", id.toString()), query)
                .thenApply(response -> response.fold(
                        json -> OrderDecoder.decodeSingle(json).flatMap(order ->
                                order.id().equals(id)
                                        ? Result.ok(Optional.of(order))
                                        : Result.err(new AlpacaError.InvalidResponse(
                                                "Order response ID does not match requested ID"))),
                        error -> error instanceof AlpacaError.HttpFailure failure
                                && failure.statusCode() == 404
                                ? Result.ok(Optional.empty())
                                : Result.err(error)));
    }

    /**
     * Fetch exactly ONE historical crypto bars page from Alpaca Market Data.
     * No implicit pagination: the returned page contains the opaque next token.
     */
    public CompletionStage<Result<CryptoBarPage>> cryptoBars(CryptoBarQuery query) {
        Objects.requireNonNull(query, "query");
        if (marketData == null) {
            throw new IllegalStateException("Historical market-data transport unavailable");
        }
        return marketData.get(query.location(), "bars", query.toQueryParameters())
                .thenApply(response -> response.flatMap(CryptoBarDecoder::decode));
    }

    /**
     * Retrieve 1–20 pages, without unbounded recursion or hidden retries.
     * A failed later page yields Failed(successfulPages, error), not an empty
     * error-only outcome. Stopped with a nonempty token means more pages exist.
     */
    public CompletionStage<CryptoBarScan> cryptoBarsPages(CryptoBarQuery query, int maxPages) {
        Objects.requireNonNull(query, "query");
        if (maxPages < 1 || maxPages > 20) {
            throw new IllegalArgumentException("maxPages must be between 1 and 20");
        }
        var seen = new HashSet<String>();
        if (query.pageToken() != null) {
            seen.add(query.pageToken());
        }
        return cryptoBarsPages(query, maxPages, List.of(), seen);
    }

    private CompletionStage<CryptoBarScan> cryptoBarsPages(
            CryptoBarQuery query, int maxPages,
            List<CryptoBarPage> received, Set<String> seen) {
        return cryptoBars(query).thenCompose(result -> {
            if (result instanceof Result.Err<CryptoBarPage> failure) {
                return CompletableFuture.completedFuture(
                        new CryptoBarScan.Failed(received, failure.error()));
            }
            CryptoBarPage page = ((Result.Ok<CryptoBarPage>) result).value();
            var pages = new ArrayList<>(received);
            pages.add(page);
            String token = page.nextPageToken();

            if (token == null) {
                return CompletableFuture.completedFuture(
                        new CryptoBarScan.Stopped(pages, Optional.empty()));
            }
            if (seen.contains(token)) {
                return CompletableFuture.completedFuture(new CryptoBarScan.Failed(
                        pages, new AlpacaError.InvalidResponse(
                                "Alpaca returned a repeated crypto bars page token")));
            }
            if (pages.size() >= maxPages) {
                return CompletableFuture.completedFuture(
                        new CryptoBarScan.Stopped(pages, Optional.of(token)));
            }
            var nextSeen = new HashSet<>(seen);
            nextSeen.add(token);
            return cryptoBarsPages(query.withPageToken(token), maxPages, pages, nextSeen);
        });
    }

    /**
     * Fetch exactly ONE historical crypto trades page. A provider continuation
     * token is returned for explicit paging; trades have no timeframe filter.
     */
    public CompletionStage<Result<CryptoTradePage>> cryptoTrades(CryptoTradeQuery query) {
        Objects.requireNonNull(query, "query");
        if (marketData == null) {
            throw new IllegalStateException("Historical market-data transport unavailable");
        }
        return marketData.get(query.location(), "trades", query.toQueryParameters())
                .thenApply(response -> response.flatMap(CryptoTradeDecoder::decode));
    }

    /**
     * Fetch 1-20 historical trade pages, retaining previous valid pages on
     * a later provider error. Stopped with continuation means more data exists.
     */
    public CompletionStage<CryptoTradeScan> cryptoTradesPages(
            CryptoTradeQuery query, int maxPages) {
        Objects.requireNonNull(query, "query");
        if (maxPages < 1 || maxPages > 20) {
            throw new IllegalArgumentException("maxPages must be between 1 and 20");
        }
        var seen = new HashSet<String>();
        if (query.pageToken() != null) {
            seen.add(query.pageToken());
        }
        return cryptoTradesPages(query, maxPages, List.of(), seen);
    }

    private CompletionStage<CryptoTradeScan> cryptoTradesPages(
            CryptoTradeQuery query, int maxPages,
            List<CryptoTradePage> received, Set<String> seen) {
        return cryptoTrades(query).thenCompose(result -> {
            if (result instanceof Result.Err<CryptoTradePage> failure) {
                return CompletableFuture.completedFuture(
                        new CryptoTradeScan.Failed(received, failure.error()));
            }
            CryptoTradePage page = ((Result.Ok<CryptoTradePage>) result).value();
            var pages = new ArrayList<>(received);
            pages.add(page);
            String token = page.nextPageToken();

            if (token == null) {
                return CompletableFuture.completedFuture(
                        new CryptoTradeScan.Stopped(pages, Optional.empty()));
            }
            if (seen.contains(token)) {
                return CompletableFuture.completedFuture(new CryptoTradeScan.Failed(
                        pages, new AlpacaError.InvalidResponse(
                                "Alpaca returned a repeated crypto trades page token")));
            }
            if (pages.size() >= maxPages) {
                return CompletableFuture.completedFuture(
                        new CryptoTradeScan.Stopped(pages, Optional.of(token)));
            }
            var nextSeen = new HashSet<>(seen);
            nextSeen.add(token);
            return cryptoTradesPages(query.withPageToken(token), maxPages, pages, nextSeen);
        });
    }

    /**
     * Fetch exactly ONE historical crypto quotes page from Alpaca Market Data.
     * No implicit pagination: the returned page contains the opaque next token.
     */
    public CompletionStage<Result<CryptoQuotePage>> cryptoQuotes(CryptoQuoteQuery query) {
        Objects.requireNonNull(query, "query");
        if (marketData == null) {
            throw new IllegalStateException("Historical market-data transport unavailable");
        }
        return marketData.get(query.location(), "quotes", query.toQueryParameters())
                .thenApply(response -> response.flatMap(CryptoQuoteDecoder::decode));
    }

    /**
     * Retrieve 1–20 pages, without unbounded recursion or hidden retries.
     * A failed later page yields Failed(successfulPages, error), not an empty
     * error-only outcome. Stopped with a nonempty token means more pages exist.
     */
    public CompletionStage<CryptoQuoteScan> cryptoQuotesPages(CryptoQuoteQuery query, int maxPages) {
        Objects.requireNonNull(query, "query");
        if (maxPages < 1 || maxPages > 20) {
            throw new IllegalArgumentException("maxPages must be between 1 and 20");
        }
        var seen = new HashSet<String>();
        if (query.pageToken() != null) {
            seen.add(query.pageToken());
        }
        return cryptoQuotesPages(query, maxPages, List.of(), seen);
    }

    private CompletionStage<CryptoQuoteScan> cryptoQuotesPages(
            CryptoQuoteQuery query, int maxPages,
            List<CryptoQuotePage> received, Set<String> seen) {
        return cryptoQuotes(query).thenCompose(result -> {
            if (result instanceof Result.Err<CryptoQuotePage> failure) {
                return CompletableFuture.completedFuture(
                        new CryptoQuoteScan.Failed(received, failure.error()));
            }
            CryptoQuotePage page = ((Result.Ok<CryptoQuotePage>) result).value();
            var pages = new ArrayList<>(received);
            pages.add(page);
            String token = page.nextPageToken();

            if (token == null) {
                return CompletableFuture.completedFuture(
                        new CryptoQuoteScan.Stopped(pages, Optional.empty()));
            }
            if (seen.contains(token)) {
                return CompletableFuture.completedFuture(new CryptoQuoteScan.Failed(
                        pages, new AlpacaError.InvalidResponse(
                                "Alpaca returned a repeated crypto quotes page token")));
            }
            if (pages.size() >= maxPages) {
                return CompletableFuture.completedFuture(
                        new CryptoQuoteScan.Stopped(pages, Optional.of(token)));
            }
            var nextSeen = new HashSet<>(seen);
            nextSeen.add(token);
            return cryptoQuotesPages(query.withPageToken(token), maxPages, pages, nextSeen);
        });
    }

    /**
     * Experimental result-based, non-accumulating bars scan.
     *
     * <p>Each decoded page is delivered exactly once to onPage before another
     * page is requested. The caller owns durable checkpoints and job policy.
     * The callback should return only once its page is safely handled.
     * Exceptions from onPage complete the stage exceptionally rather than
     * becoming a provider error. No automatic retries or page retention.
     */
    public CompletionStage<Result<ScanSummary>> scanBars(
            CryptoBarQuery query, int maxPages, Consumer<? super CryptoBarPage> onPage) {
        Objects.requireNonNull(query, "query");
        return PageScanner.scan(query, query.pageToken(), maxPages,
                new PageScanner.Adapter<>(
                        this::cryptoBars, CryptoBarQuery::withPageToken,
                        CryptoBarPage::continuation, CryptoBarPage::barCount), onPage);
    }

    /** Experimental historical trades scan with the same Result contract. */
    public CompletionStage<Result<ScanSummary>> scanTrades(
            CryptoTradeQuery query, int maxPages, Consumer<? super CryptoTradePage> onPage) {
        Objects.requireNonNull(query, "query");
        return PageScanner.scan(query, query.pageToken(), maxPages,
                new PageScanner.Adapter<>(
                        this::cryptoTrades, CryptoTradeQuery::withPageToken,
                        CryptoTradePage::continuation, CryptoTradePage::tradeCount), onPage);
    }

    /** Experimental historical quotes scan with the same Result contract. */
    public CompletionStage<Result<ScanSummary>> scanQuotes(
            CryptoQuoteQuery query, int maxPages, Consumer<? super CryptoQuotePage> onPage) {
        Objects.requireNonNull(query, "query");
        return PageScanner.scan(query, query.pageToken(), maxPages,
                new PageScanner.Adapter<>(
                        this::cryptoQuotes, CryptoQuoteQuery::withPageToken,
                        CryptoQuotePage::continuation, CryptoQuotePage::quoteCount), onPage);
    }

    /**
     * Submit one previously validated and explicitly authorized crypto PAPER
     * order. Creating CryptoOrderRequest or calling authorize() alone does
     * not send HTTP; this method sends exactly one POST without retries.
     *
     * <p>A returned Order is Alpaca's acknowledged order status, NOT a
     * guarantee of any filled quantity. If the result is unknown, inspect
     * existing paper orders using the caller's clientOrderId before retrying.
     */
    public CompletionStage<Result<Order>> submitPaperOrder(ConfirmedPaperOrder confirmed) {
        Objects.requireNonNull(confirmed, "confirmed");
        return transport.postPaperOrder(confirmed)
                .thenApply(result -> result.flatMap(payload -> {
                    Result<Order> decoded = OrderDecoder.decodeSingle(payload);
                    if (decoded instanceof Result.Err<Order>) {
                        // A server may have created the order even though its
                        // success JSON was not what this decoder expected.
                        return Result.err(new AlpacaError.SubmissionOutcomeUnknown(
                                null, "Unverifiable paper submission response; inspect client_order_id"));
                    }
                    Order order = ((Result.Ok<Order>) decoded).value();
                    if (!confirmed.request().clientOrderId().equals(order.clientOrderId())) {
                        return Result.err(new AlpacaError.SubmissionOutcomeUnknown(
                                null, "Paper submission ID mismatch; inspect client_order_id"));
                    }
                    return Result.ok(order);
                }));
    }

    /**
     * Explicitly attempt cancellation of one paper order by its server UUID.
     *
     * <p>This is a real DELETE; no read operation calls it automatically.
     * A successful value means Alpaca accepted the request (HTTP 204), NOT
     * that the order is in terminal "canceled" state. Verify independently
     * with {@link #order(UUID)}. If the result is uncertain, inspect status
     * before considering a repeat request.
     */
    public CompletionStage<Result<PaperCancellationAccepted>> cancelPaperOrder(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId");
        return transport.deletePaperOrder(orderId);

    }

    /** Avoid accidental diagnostic printing of credentials or account data. */
    @Override
    public String toString() {
        return "Atalanta[paper]";
    }
}
