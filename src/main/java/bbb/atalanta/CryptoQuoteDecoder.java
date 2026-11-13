package bbb.atalanta;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Strict historical crypto quote decoder. Provider extensions remain deep
 * immutable Java values; Jackson is private to this package.
 */
final class CryptoQuoteDecoder {
    private static final Set<String> QUOTE_FIELDS = Set.of("ap", "as", "bp", "bs", "t");
    private static final Set<String> PAGE_FIELDS = Set.of("quotes", "next_page_token");

    private CryptoQuoteDecoder() {}

    static Result<CryptoQuotePage> decode(JsonNode root) {
        if (root == null || !root.isObject() || !root.has("quotes")
                || !root.get("quotes").isObject()) {
            return invalid();
        }
        try {
            var grouped = new LinkedHashMap<String, java.util.List<CryptoQuote>>();
            root.get("quotes").properties().forEach(group -> {
                if (!group.getKey().matches("[A-Z0-9]+/[A-Z0-9]+")
                        || !group.getValue().isArray()) {
                    throw new IllegalArgumentException("Invalid quote symbol group");
                }
                var quotes = new ArrayList<CryptoQuote>();
                group.getValue().forEach(quote -> quotes.add(parse(quote)));
                grouped.put(group.getKey(), java.util.List.copyOf(quotes));
            });

            JsonNode rawToken = root.get("next_page_token");
            String token = null;
            if (rawToken != null && !rawToken.isNull()) {
                if (!rawToken.isTextual()) throw new IllegalArgumentException("Invalid page token");
                token = rawToken.textValue();
                if (!token.isEmpty() && (token.length() > 2048
                        || !token.chars().allMatch(c -> c >= 33 && c <= 126))) {
                    throw new IllegalArgumentException("Invalid page token");
                }
            }

            return Result.ok(new CryptoQuotePage(grouped, token, extra(root, PAGE_FIELDS)));
        } catch (IllegalArgumentException ex) {
            return invalid();
        }
    }

    private static CryptoQuote parse(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("Expected quote object");
        }
        JsonNode time = node.get("t");
        if (time == null || !time.isTextual() || time.textValue().isBlank()) {
            throw new IllegalArgumentException("Missing or invalid quote timestamp");
        }
        try {
            OffsetDateTime.parse(time.textValue());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Invalid quote timestamp");
        }
        var present = new LinkedHashSet<String>();
        node.properties().forEach(item -> present.add(item.getKey()));

        return new CryptoQuote(time.textValue(),
                decimal(node, "ap"), decimal(node, "as"),
                decimal(node, "bp"), decimal(node, "bs"),
                present, extra(node, QUOTE_FIELDS));
    }

    private static BigDecimal decimal(JsonNode node, String wireName) {
        JsonNode value = node.get(wireName);
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException("Required quote decimal missing or not numeric");
        }
        return value.decimalValue();
    }

    private static Map<String, Object> extra(JsonNode root, Set<String> known) {
        var extras = new LinkedHashMap<String, Object>();
        root.properties().forEach(field -> {
            if (!known.contains(field.getKey())) {
                extras.put(field.getKey(), ProviderJson.javaValue(field.getValue()));
            }
        });
        return extras;
    }

    private static Result<CryptoQuotePage> invalid() {
        return Result.err(new AlpacaError.InvalidResponse(
                "Invalid historical crypto quotes response"));
    }
}
