package bbb.atalanta;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Internal Alpaca historical trades decoder; no Jackson types in public APIs. */
final class CryptoTradeDecoder {

    private static final Set<String> TRADE_FIELDS = Set.of("i", "p", "s", "t", "tks");
    private static final Set<String> PAGE_FIELDS = Set.of("trades", "next_page_token");

    private CryptoTradeDecoder() {}

    static Result<CryptoTradePage> decode(JsonNode root) {
        if (root == null || !root.isObject()
                || !root.has("trades") || !root.get("trades").isObject()) {
            return invalid();
        }
        try {
            var grouped = new LinkedHashMap<String, java.util.List<CryptoTrade>>();
            root.get("trades").properties().forEach(symbol -> {
                if (!symbol.getKey().matches("[A-Z0-9]+/[A-Z0-9]+")
                        || !symbol.getValue().isArray()) {
                    throw new IllegalArgumentException("Invalid trade symbol group");
                }
                var trades = new ArrayList<CryptoTrade>();
                symbol.getValue().forEach(entry -> trades.add(trade(entry)));
                grouped.put(symbol.getKey(), java.util.List.copyOf(trades));
            });

            JsonNode rawToken = root.get("next_page_token");
            String token = null;
            if (rawToken != null && !rawToken.isNull()) {
                if (!rawToken.isTextual()) {
                    throw new IllegalArgumentException("Invalid page token");
                }
                token = rawToken.textValue();
                if (!token.isEmpty() && (token.length() > 2048
                        || !token.chars().allMatch(code -> code >= 33 && code <= 126))) {
                    throw new IllegalArgumentException("Invalid page token");
                }
            }
            return Result.ok(new CryptoTradePage(grouped, token, extra(root, PAGE_FIELDS)));
        } catch (IllegalArgumentException e) {
            return invalid();
        }
    }

    private static CryptoTrade trade(JsonNode json) {
        if (json == null || !json.isObject()) {
            throw new IllegalArgumentException("Expected crypto trade object");
        }
        var present = new LinkedHashSet<String>();
        json.properties().forEach(entry -> present.add(entry.getKey()));

        JsonNode rawId = json.get("i");
        if (rawId == null || !rawId.isIntegralNumber()) {
            throw new IllegalArgumentException("Expected integer trade ID");
        }
        BigInteger id = rawId.bigIntegerValue();
        if (id.signum() < 0) {
            throw new IllegalArgumentException("Negative trade ID");
        }

        JsonNode rawTimestamp = json.get("t");
        if (rawTimestamp == null || !rawTimestamp.isTextual()
                || rawTimestamp.textValue().isBlank()) {
            throw new IllegalArgumentException("Missing trade timestamp");
        }

        JsonNode rawSide = json.get("tks");
        String side = null;
        if (rawSide != null && !rawSide.isNull()) {
            if (!rawSide.isTextual()) {
                throw new IllegalArgumentException("Invalid taker side");
            }
            side = rawSide.textValue();
        }

        return new CryptoTrade(id, number(json.get("p")), number(json.get("s")),
                rawTimestamp.textValue(), side, present, extra(json, TRADE_FIELDS));
    }

    private static BigDecimal number(JsonNode node) {
        if (node == null || !node.isNumber()) {
            throw new IllegalArgumentException("Expected numeric price or size");
        }
        return node.decimalValue();
    }

    private static Map<String, Object> extra(JsonNode json, Set<String> known) {
        var unknown = new LinkedHashMap<String, Object>();
        json.properties().forEach(entry -> {
            if (!known.contains(entry.getKey())) {
                unknown.put(entry.getKey(), ProviderJson.javaValue(entry.getValue()));
            }
        });
        return unknown;
    }

    private static Result<CryptoTradePage> invalid() {
        return Result.err(new AlpacaError.InvalidResponse(
                "Invalid historical crypto trades response"));
    }
}
