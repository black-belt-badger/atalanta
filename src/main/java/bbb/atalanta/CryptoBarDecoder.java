package bbb.atalanta;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Package-private Alpaca historical bar decoder, with no Jackson types in public APIs. */
final class CryptoBarDecoder {

    private static final Set<String> BAR_FIELDS =
            Set.of("t", "o", "h", "l", "c", "v", "n", "vw");
    private static final Set<String> PAGE_FIELDS = Set.of("bars", "next_page_token");

    private CryptoBarDecoder() {}

    static Result<CryptoBarPage> decode(JsonNode root) {
        if (root == null || !root.isObject() || !root.has("bars")
                || !root.get("bars").isObject()) {
            return invalid();
        }
        try {
            var map = new LinkedHashMap<String, java.util.List<CryptoBar>>();
            root.get("bars").properties().forEach(symbol -> {
                if (!symbol.getKey().matches("[A-Z0-9]+/[A-Z0-9]+")
                        || !symbol.getValue().isArray()) {
                    throw new IllegalArgumentException("Invalid symbol group");
                }
                var values = new ArrayList<CryptoBar>();
                symbol.getValue().forEach(entry -> values.add(bar(entry)));
                map.put(symbol.getKey(), java.util.List.copyOf(values));
            });
            JsonNode rawToken = root.get("next_page_token");
            String token = null;
            if (rawToken != null && !rawToken.isNull()) {
                if (!rawToken.isTextual()) throw new IllegalArgumentException("Invalid page token");
                token = rawToken.textValue();
                if (!token.isEmpty() && (token.length() > 2048
                        || !token.chars().allMatch(code -> code >= 33 && code <= 126))) {
                    throw new IllegalArgumentException("Invalid page token");
                }
            }
            return Result.ok(new CryptoBarPage(map, token, extra(root, PAGE_FIELDS)));
        } catch (IllegalArgumentException e) {
            return invalid();
        }
    }

    private static CryptoBar bar(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("Expected bar object");
        }
        String timestamp = text(node, "t");
        if (timestamp == null) throw new IllegalArgumentException("Missing timestamp");
        try {
            OffsetDateTime.parse(timestamp);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid timestamp");
        }

        JsonNode count = node.get("n");
        BigInteger trades = null;
        if (count != null && !count.isNull()) {
            if (!count.isIntegralNumber()) throw new IllegalArgumentException("Invalid count");
            trades = count.bigIntegerValue();
        }
        JsonNode weighted = node.get("vw");
        BigDecimal vwap = weighted == null || weighted.isNull()
                ? null : decimal(weighted);
        var present = new LinkedHashSet<String>();
        node.properties().forEach(field -> present.add(field.getKey()));

        return new CryptoBar(timestamp,
                requiredDecimal(node, "o"),
                requiredDecimal(node, "h"),
                requiredDecimal(node, "l"),
                requiredDecimal(node, "c"),
                requiredDecimal(node, "v"),
                trades, vwap, present, extra(node, BAR_FIELDS));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("Missing or invalid text");
        }
        return value.textValue();
    }

    private static BigDecimal requiredDecimal(JsonNode root, String field) {
        return decimal(root.get(field));
    }

    private static BigDecimal decimal(JsonNode value) {
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException("Expected numeric field");
        }
        return value.decimalValue();
    }

    private static Map<String, Object> extra(JsonNode node, Set<String> known) {
        var map = new LinkedHashMap<String, Object>();
        node.properties().forEach(entry -> {
            if (!known.contains(entry.getKey())) {
                map.put(entry.getKey(), ProviderJson.javaValue(entry.getValue()));
            }
        });
        return map;
    }

    private static Result<CryptoBarPage> invalid() {
        return Result.err(new AlpacaError.InvalidResponse("Invalid historical crypto bars response"));
    }
}
