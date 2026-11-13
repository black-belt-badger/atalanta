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

/**
 * Package-private decoder for an asset array, including new/unknown fields.
 * Provider field values are never included in validation error messages.
 */
final class AssetDecoder {

    private static final Set<String> KNOWN_FIELDS = Set.of(
            "id", "class", "exchange", "symbol", "name", "status",
            "tradable", "marginable", "shortable", "fractionable",
            "easy_to_borrow", "borrow_status", "cusip", "attributes",
            "margin_requirement_long", "margin_requirement_short",
            "maintenance_margin_requirement", "min_order_size",
            "min_trade_increment", "price_increment");

    private AssetDecoder() {}

    static Result<List<Asset>> decode(JsonNode root) {
        if (root == null || !root.isArray()) {
            return Result.err(new AlpacaError.InvalidResponse("Expected an assets JSON array"));
        }
        var assets = new ArrayList<Asset>(root.size());
        for (JsonNode json : root) {
            Result<Asset> decoded = decodeOne(json);
            if (decoded instanceof Result.Err<Asset> failure) {
                return Result.err(failure.error());
            }
            assets.add(((Result.Ok<Asset>) decoded).value());
        }
        return Result.ok(List.copyOf(assets));
    }

    private static Result<Asset> decodeOne(JsonNode root) {
        if (!root.isObject()) {
            return Result.err(new AlpacaError.InvalidResponse("Expected an asset JSON object"));
        }
        try {
            var present = new LinkedHashSet<String>();
            var additional = new LinkedHashMap<String, Object>();
            root.properties().forEach(field -> {
                present.add(field.getKey());
                if (!KNOWN_FIELDS.contains(field.getKey())) {
                    additional.put(field.getKey(), ProviderJson.javaValue(field.getValue()));
                }
            });

            Asset asset = new Asset(
                    UUID.fromString(requiredText(root, "id")),
                    requiredText(root, "class"),
                    requiredText(root, "exchange"),
                    requiredText(root, "symbol"),
                    requiredText(root, "name"),
                    requiredText(root, "status"),
                    requiredBoolean(root, "tradable"),
                    requiredBoolean(root, "marginable"),
                    requiredBoolean(root, "shortable"),
                    requiredBoolean(root, "fractionable"),
                    optionalBoolean(root, "easy_to_borrow"),
                    optionalText(root, "borrow_status"),
                    optionalText(root, "cusip"),
                    decimal(root, "margin_requirement_long"),
                    decimal(root, "margin_requirement_short"),
                    decimal(root, "maintenance_margin_requirement"),
                    decimal(root, "min_order_size"),
                    decimal(root, "min_trade_increment"),
                    decimal(root, "price_increment"),
                    attributes(root, "attributes"),
                    present,
                    additional);
            return Result.ok(asset);
        } catch (IllegalArgumentException error) {
            return Result.err(new AlpacaError.InvalidResponse("Invalid asset field or type"));
        }
    }

    private static String requiredText(JsonNode root, String field) {
        String value = optionalText(root, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required text");
        }
        return value;
    }

    private static JsonNode nonNull(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node;
    }

    private static String optionalText(JsonNode root, String field) {
        JsonNode node = nonNull(root, field);
        if (node == null) {
            return null;
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException("Wrong text type");
        }
        return node.textValue();
    }

    private static Boolean optionalBoolean(JsonNode root, String field) {
        JsonNode node = nonNull(root, field);
        if (node == null) {
            return null;
        }
        if (!node.isBoolean()) {
            throw new IllegalArgumentException("Wrong boolean type");
        }
        return node.booleanValue();
    }

    private static boolean requiredBoolean(JsonNode root, String field) {
        Boolean value = optionalBoolean(root, field);
        if (value == null) {
            throw new IllegalArgumentException("Missing required boolean");
        }
        return value;
    }

    private static BigDecimal decimal(JsonNode root, String field) {
        JsonNode node = nonNull(root, field);
        if (node == null) {
            return null;
        }
        if (!node.isTextual() && !node.isNumber()) {
            throw new IllegalArgumentException("Wrong decimal type");
        }
        return new BigDecimal(node.asText());
    }

    private static List<String> attributes(JsonNode root, String field) {
        JsonNode node = nonNull(root, field);
        if (node == null) {
            return null;
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("Wrong attributes type");
        }
        var values = new ArrayList<String>();
        node.forEach(value -> {
            if (!value.isTextual()) {
                throw new IllegalArgumentException("Wrong attribute value");
            }
            values.add(value.textValue());
        });
        return List.copyOf(values);
    }
}
