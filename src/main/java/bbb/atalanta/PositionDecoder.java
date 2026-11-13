package bbb.atalanta;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Package-private position decoding boundary; only standard Java types escape. */
final class PositionDecoder {

    private static final Set<String> KNOWN_FIELDS = Set.of(
            "asset_id", "symbol", "exchange", "asset_class", "asset_marginable",
            "qty", "qty_available", "side", "avg_entry_price", "cost_basis",
            "market_value", "current_price", "lastday_price", "change_today",
            "unrealized_pl", "unrealized_plpc", "unrealized_intraday_pl",
            "unrealized_intraday_plpc", "swap_rate", "prev_swap_rate",
            "avg_entry_swap_rate", "usd");

    private static final Set<String> USD_FIELDS = Set.of(
            "avg_entry_price", "change_today", "cost_basis", "current_price",
            "lastday_price", "market_value", "unrealized_intraday_pl",
            "unrealized_intraday_plpc", "unrealized_pl", "unrealized_plpc");

    private PositionDecoder() {}

    static Result<List<Position>> decode(JsonNode root) {
        if (root == null || !root.isArray()) {
            return Result.err(new AlpacaError.InvalidResponse("Expected a positions JSON array"));
        }
        var positions = new ArrayList<Position>(root.size());
        for (JsonNode entry : root) {
            Result<Position> position = decodeOne(entry);
            if (position instanceof Result.Err<Position> error) {
                return Result.err(error.error());
            }
            positions.add(((Result.Ok<Position>) position).value());
        }
        return Result.ok(List.copyOf(positions));
    }

    private static Result<Position> decodeOne(JsonNode root) {
        if (root == null || !root.isObject()) {
            return Result.err(new AlpacaError.InvalidResponse("Expected a position JSON object"));
        }
        try {
            var present = new LinkedHashSet<String>();
            var additional = additionalFields(root, KNOWN_FIELDS, present);
            return Result.ok(new Position(
                    UUID.fromString(requiredText(root, "asset_id")),
                    requiredText(root, "symbol"),
                    text(root, "exchange"), // "" is permitted for some asset classes
                    requiredText(root, "asset_class"),
                    booleanValue(root, "asset_marginable"),
                    requiredDecimal(root, "qty"),
                    decimal(root, "qty_available"),
                    requiredText(root, "side"),
                    decimal(root, "avg_entry_price"),
                    decimal(root, "cost_basis"),
                    decimal(root, "market_value"),
                    decimal(root, "current_price"),
                    decimal(root, "lastday_price"),
                    decimal(root, "change_today"),
                    decimal(root, "unrealized_pl"),
                    decimal(root, "unrealized_plpc"),
                    decimal(root, "unrealized_intraday_pl"),
                    decimal(root, "unrealized_intraday_plpc"),
                    decimal(root, "swap_rate"),
                    decimal(root, "prev_swap_rate"),
                    decimal(root, "avg_entry_swap_rate"),
                    usd(root.get("usd")),
                    present, additional));
        } catch (IllegalArgumentException e) {
            // Deliberately do not reveal provider JSON or parser exception messages.
            return Result.err(new AlpacaError.InvalidResponse("Invalid position field or type"));
        }
    }

    private static PositionUsd usd(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("USD position fields are not an object");
        }
        var present = new LinkedHashSet<String>();
        var additional = additionalFields(node, USD_FIELDS, present);
        return new PositionUsd(
                decimal(node, "avg_entry_price"),
                decimal(node, "change_today"),
                decimal(node, "cost_basis"),
                decimal(node, "current_price"),
                decimal(node, "lastday_price"),
                decimal(node, "market_value"),
                decimal(node, "unrealized_intraday_pl"),
                decimal(node, "unrealized_intraday_plpc"),
                decimal(node, "unrealized_pl"),
                decimal(node, "unrealized_plpc"),
                present, additional);
    }

    private static Map<String, Object> additionalFields(
            JsonNode root, Set<String> known, Set<String> present) {
        var additional = new LinkedHashMap<String, Object>();
        root.properties().forEach(field -> {
            present.add(field.getKey());
            if (!known.contains(field.getKey())) {
                additional.put(field.getKey(), ProviderJson.javaValue(field.getValue()));
            }
        });
        return additional;
    }

    private static JsonNode nonNull(JsonNode root, String name) {
        JsonNode value = root.get(name);
        return value == null || value.isNull() ? null : value;
    }

    private static String text(JsonNode root, String name) {
        JsonNode node = nonNull(root, name);
        if (node == null) {
            return null;
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException("Expected text");
        }
        return node.textValue();
    }

    private static String requiredText(JsonNode root, String name) {
        String value = text(root, name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required text");
        }
        return value;
    }

    private static Boolean booleanValue(JsonNode root, String name) {
        JsonNode node = nonNull(root, name);
        if (node == null) {
            return null;
        }
        if (!node.isBoolean()) {
            throw new IllegalArgumentException("Expected boolean");
        }
        return node.booleanValue();
    }

    private static BigDecimal decimal(JsonNode root, String name) {
        JsonNode node = nonNull(root, name);
        if (node == null) {
            return null;
        }
        if (!node.isTextual() && !node.isNumber()) {
            throw new IllegalArgumentException("Expected decimal");
        }
        return new BigDecimal(node.asText());
    }

    private static BigDecimal requiredDecimal(JsonNode root, String name) {
        BigDecimal value = decimal(root, name);
        if (value == null) {
            throw new IllegalArgumentException("Missing required decimal");
        }
        return value;
    }
}
