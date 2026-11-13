package bbb.atalanta;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable filters for GET /v2/orders.
 *
 * <p>Null scalar filters and empty collections mean "omit this parameter".
 * This preserves Alpaca's own defaults: status=open, limit=50 and
 * direction=desc. The API uses exclusive timestamp/order-ID boundaries.
 *
 * <p>Order-ID cursors are mutually exclusive with each other AND with
 * the timestamp bounds (after/until).
 */
public record OrderQuery(
        String status,
        Integer limit,
        Instant after,
        Instant until,
        String direction,
        Boolean nested,
        List<String> symbols,
        String side,
        List<String> assetClasses,
        UUID beforeOrderId,
        UUID afterOrderId) {

    public OrderQuery {
        if (status != null && !Set.of("open", "closed", "all").contains(status)) {
            throw new IllegalArgumentException("status must be open, closed, or all");
        }
        if (limit != null && (limit < 1 || limit > 500)) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
        if (direction != null && !Set.of("asc", "desc").contains(direction)) {
            throw new IllegalArgumentException("direction must be asc or desc");
        }
        if (side != null && !Set.of("buy", "sell").contains(side)) {
            throw new IllegalArgumentException("side must be buy or sell");
        }
        symbols = copyTokens(symbols, "symbol", false);
        assetClasses = copyTokens(assetClasses, "asset class", true);
        if (beforeOrderId != null && afterOrderId != null) {
            throw new IllegalArgumentException("before_order_id and after_order_id are mutually exclusive");
        }
        if ((beforeOrderId != null || afterOrderId != null) && (after != null || until != null)) {
            throw new IllegalArgumentException("Order-ID cursors cannot be combined with after/until");
        }
        if (after != null && until != null && !after.isBefore(until)) {
            throw new IllegalArgumentException("after must be earlier than until");
        }
    }

    /** Use all Alpaca server defaults, not a CLI-specific convention. */
    public static OrderQuery defaults() {
        return new OrderQuery(null, null, null, null, null, null,
                List.of(), null, List.of(), null, null);
    }

    public OrderQuery withStatus(String value) {
        return new OrderQuery(value, limit, after, until, direction, nested,
                symbols, side, assetClasses, beforeOrderId, afterOrderId);
    }

    public OrderQuery withLimit(Integer value) {
        return new OrderQuery(status, value, after, until, direction, nested,
                symbols, side, assetClasses, beforeOrderId, afterOrderId);
    }

    public OrderQuery withAfter(Instant value) {
        return new OrderQuery(status, limit, value, until, direction, nested,
                symbols, side, assetClasses, beforeOrderId, afterOrderId);
    }

    public OrderQuery withUntil(Instant value) {
        return new OrderQuery(status, limit, after, value, direction, nested,
                symbols, side, assetClasses, beforeOrderId, afterOrderId);
    }

    public OrderQuery withDirection(String value) {
        return new OrderQuery(status, limit, after, until, value, nested,
                symbols, side, assetClasses, beforeOrderId, afterOrderId);
    }

    public OrderQuery withNested(Boolean value) {
        return new OrderQuery(status, limit, after, until, direction, value,
                symbols, side, assetClasses, beforeOrderId, afterOrderId);
    }

    public OrderQuery withSymbols(List<String> values) {
        return new OrderQuery(status, limit, after, until, direction, nested,
                values, side, assetClasses, beforeOrderId, afterOrderId);
    }

    public OrderQuery withSide(String value) {
        return new OrderQuery(status, limit, after, until, direction, nested,
                symbols, value, assetClasses, beforeOrderId, afterOrderId);
    }

    public OrderQuery withAssetClasses(List<String> values) {
        return new OrderQuery(status, limit, after, until, direction, nested,
                symbols, side, values, beforeOrderId, afterOrderId);
    }

    public OrderQuery withBeforeOrderId(UUID value) {
        return new OrderQuery(status, limit, after, until, direction, nested,
                symbols, side, assetClasses, value, afterOrderId);
    }

    public OrderQuery withAfterOrderId(UUID value) {
        return new OrderQuery(status, limit, after, until, direction, nested,
                symbols, side, assetClasses, beforeOrderId, value);
    }

    Map<String, String> toQueryParameters() {
        var params = new LinkedHashMap<String, String>();
        if (status != null) params.put("status", status);
        if (limit != null) params.put("limit", Integer.toString(limit));
        if (after != null) params.put("after", after.toString());
        if (until != null) params.put("until", until.toString());
        if (direction != null) params.put("direction", direction);
        if (nested != null) params.put("nested", Boolean.toString(nested));
        if (!symbols.isEmpty()) params.put("symbols", String.join(",", symbols));
        if (side != null) params.put("side", side);
        if (!assetClasses.isEmpty()) params.put("asset_class", String.join(",", assetClasses));
        if (beforeOrderId != null) params.put("before_order_id", beforeOrderId.toString());
        if (afterOrderId != null) params.put("after_order_id", afterOrderId.toString());
        return Collections.unmodifiableMap(params);
    }

    private static List<String> copyTokens(List<String> values, String label, boolean assetClass) {
        if (values == null) {
            throw new IllegalArgumentException(label + " filters cannot be null");
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(label + " must not be blank");
            }
            // List-based filters cannot contain separators or URL syntax.
            // Accept newly introduced asset classes without a closed enum.
            String pattern = assetClass ? "[a-z][a-z0-9_]*" : "[A-Za-z0-9._:-]+";
            if (!value.matches(pattern)) {
                throw new IllegalArgumentException("Invalid " + label + " filter");
            }
        }
        return List.copyOf(values);
    }
}
