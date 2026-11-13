package bbb.atalanta;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable request filters for GET /v2/assets.
 *
 * <p>Leaving assetClass null honors Alpaca's own us_equity default. Leaving
 * status null includes all statuses. Multi-value exchange/attribute filters
 * are serialized as comma-separated values; attribute matching is OR.
 *
 * <p>Unknown future asset classes and attribute codes remain expressible
 * without changing this public Java API.
 */
public record AssetQuery(
        String assetClass,
        String status,
        List<String> exchanges,
        List<String> attributes) {

    private static final Set<String> EQUITY_EXCHANGES = Set.of(
            "AMEX", "ARCA", "BATS", "NYSE", "NASDAQ", "NYSEARCA", "OTC", "TXSE");

    public AssetQuery {
        assetClass = token(assetClass, "assetClass", false);
        status = token(status, "status", false);
        if (status != null && !Set.of("active", "inactive").contains(status)) {
            throw new IllegalArgumentException("status must be active or inactive");
        }
        exchanges = checked(exchanges, "exchange", true);
        attributes = checked(attributes, "attribute", false);

        if (!exchanges.isEmpty()) {
            String effectiveClass = assetClass == null ? "us_equity" : assetClass;
            if (!effectiveClass.equals("us_equity") && !effectiveClass.equals("us_index")) {
                throw new IllegalArgumentException("Exchange filtering requires us_equity or us_index");
            }
            for (String exchange : exchanges) {
                if (effectiveClass.equals("us_index") && !exchange.equals("INDEX")) {
                    throw new IllegalArgumentException("us_index exchange filter must be INDEX");
                }
                if (effectiveClass.equals("us_equity") && !EQUITY_EXCHANGES.contains(exchange)) {
                    throw new IllegalArgumentException("Unsupported us_equity exchange filter");
                }
            }
        }
    }

    /** Use Alpaca's defaults: us_equity, all statuses, no other filters. */
    public static AssetQuery defaults() {
        return new AssetQuery(null, null, List.of(), List.of());
    }

    /** Explicit crypto selection, independent of the provider's us_equity default. */
    public static AssetQuery crypto() {
        return defaults().withAssetClass("crypto");
    }

    public AssetQuery withAssetClass(String value) {
        return new AssetQuery(value, status, exchanges, attributes);
    }

    public AssetQuery withStatus(String value) {
        return new AssetQuery(assetClass, value, exchanges, attributes);
    }

    public AssetQuery withExchanges(List<String> values) {
        return new AssetQuery(assetClass, status, values, attributes);
    }

    public AssetQuery withAttributes(List<String> values) {
        return new AssetQuery(assetClass, status, exchanges, values);
    }

    /** Package-private HTTP serialization; no JSON or HTTP types in the public API. */
    Map<String, String> toQueryParameters() {
        var query = new LinkedHashMap<String, String>();
        if (status != null) {
            query.put("status", status);
        }
        if (assetClass != null) {
            query.put("asset_class", assetClass);
        }
        if (!exchanges.isEmpty()) {
            query.put("exchange", String.join(",", exchanges));
        }
        if (!attributes.isEmpty()) {
            query.put("attributes", String.join(",", attributes));
        }
        return Collections.unmodifiableMap(query);
    }

    private static List<String> checked(List<String> values, String label, boolean uppercase) {
        if (values == null) {
            throw new IllegalArgumentException(label + " list must not be null");
        }
        for (String value : values) {
            if (value == null) {
                throw new IllegalArgumentException(label + " entries must not be null");
            }
            token(value, label, uppercase);
        }
        return List.copyOf(values);
    }

    private static String token(String value, String label, boolean uppercase) {
        if (value == null) {
            return null;
        }
        String pattern = uppercase ? "[A-Z][A-Z0-9_]*" : "[a-z][a-z0-9_]*";
        if (!value.matches(pattern)) {
            throw new IllegalArgumentException(label + " must be a nonempty API token");
        }
        return value;
    }
}
