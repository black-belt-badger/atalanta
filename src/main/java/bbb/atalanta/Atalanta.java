package bbb.atalanta;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Read-only Java 17 client for Alpaca's paper Trading API.
 *
 * <p>Remote operations are asynchronous and return functional Results:
 * ordinary provider/network failures are typed {@link Result.Err} values.
 * Unexpected programming faults or cancellation may complete exceptionally.
 *
 * <p>Account, asset-list, position, order-list and order-by-ID reads are
 * supported. No order placement or live-trading methods are available.
 */
public final class Atalanta {

    private final AlpacaHttpTransport transport;

    /** Create a client locked to Alpaca's paper trading endpoint. */
    public static Atalanta paper(String apiKeyId, String secretKey) {
        return new Atalanta(AlpacaHttpTransport.paper(apiKeyId, secretKey));
    }

    /** Package-private constructor for isolated contract tests. */
    Atalanta(AlpacaHttpTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
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
     * timestamp/order-ID pagination. No order placement is supported.
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

    /** Avoid accidental diagnostic printing of credentials or account data. */
    @Override
    public String toString() {
        return "Atalanta[paper]";
    }
}
