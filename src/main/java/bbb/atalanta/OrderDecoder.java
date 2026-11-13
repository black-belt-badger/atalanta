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

/** Internal decoder: complete typed order response, optionally including legs. */
final class OrderDecoder {

    private static final Set<String> KNOWN_FIELDS = Set.of(
            "id", "client_order_id", "asset_id", "asset_class", "symbol",
            "side", "position_intent", "status", "type", "order_type",
            "order_class", "time_in_force", "qty", "ratio_qty", "notional", "filled_qty",
            "filled_avg_price", "limit_price", "stop_price", "trail_price",
            "trail_percent", "hwm", "extended_hours", "created_at",
            "updated_at", "submitted_at", "filled_at", "expired_at",
            "canceled_at", "failed_at", "replaced_at", "replaced_by",
            "replaces", "expires_at", "group_id", "group_role", "source",
            "subtag", "legs");

    private OrderDecoder() {}

    static Result<List<Order>> decode(JsonNode root) {
        if (root == null || !root.isArray()) {
            return Result.err(new AlpacaError.InvalidResponse("Expected an orders JSON array"));
        }
        try {
            var orders = new ArrayList<Order>(root.size());
            for (JsonNode order : root) {
                orders.add(decodeOne(order, 0));
            }
            return Result.ok(List.copyOf(orders));
        } catch (IllegalArgumentException failure) {
            // Do not leak response values, IDs, financial details, or parser messages.
            return Result.err(new AlpacaError.InvalidResponse("Invalid order field or type"));
        }
    }

    /**
     * Decode the single-order object returned by GET /v2/orders/{order_id}.
     * Reuse the exact same nested-leg, type and precision rules as the list
     * decoder, so list and lookup cannot drift apart.
     */
    static Result<Order> decodeSingle(JsonNode root) {
        if (root == null || !root.isObject()) {
            return Result.err(new AlpacaError.InvalidResponse("Expected an order JSON object"));
        }
        try {
            return Result.ok(decodeOne(root, 0));
        } catch (IllegalArgumentException error) {
            // Do not leak provider account data, order IDs or JSON snippets.
            return Result.err(new AlpacaError.InvalidResponse("Invalid order field or type"));
        }
    }

    private static Order decodeOne(JsonNode root, int depth) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Expected order object");
        }
        var present = new LinkedHashSet<String>();
        var additional = new LinkedHashMap<String, Object>();
        root.properties().forEach(field -> {
            present.add(field.getKey());
            if (!KNOWN_FIELDS.contains(field.getKey())) {
                additional.put(field.getKey(), ProviderJson.javaValue(field.getValue()));
            }
        });

        return new Order(
                requiredUuid(root, "id"),
                text(root, "client_order_id"),
                uuid(root, "asset_id"),
                text(root, "asset_class"),
                text(root, "symbol"),
                text(root, "side"),  // null is valid on mleg parent orders
                text(root, "position_intent"),
                requiredText(root, "status"),
                text(root, "type"),
                text(root, "order_type"),
                text(root, "order_class"),
                text(root, "time_in_force"),
                decimal(root, "qty"),      // notional-based orders have null qty
                decimal(root, "ratio_qty"), // optional multi-leg option ratio
                decimal(root, "notional"), // quantity-based orders have null notional
                decimal(root, "filled_qty"),
                decimal(root, "filled_avg_price"),
                decimal(root, "limit_price"),
                decimal(root, "stop_price"),
                decimal(root, "trail_price"),
                decimal(root, "trail_percent"),
                decimal(root, "hwm"),
                booleanValue(root, "extended_hours"),
                text(root, "created_at"),
                text(root, "updated_at"),
                text(root, "submitted_at"),
                text(root, "filled_at"),
                text(root, "expired_at"),
                text(root, "canceled_at"),
                text(root, "failed_at"),
                text(root, "replaced_at"),
                uuid(root, "replaced_by"),
                uuid(root, "replaces"),
                text(root, "expires_at"),
                uuid(root, "group_id"),
                text(root, "group_role"),
                text(root, "source"),
                text(root, "subtag"),
                legs(root, depth),
                present,
                additional);
    }

    private static List<Order> legs(JsonNode root, int depth) {
        JsonNode node = nonNull(root, "legs");
        if (node == null) {
            return null; // null is different from explicitly empty []
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("Expected legs array");
        }
        if (depth >= 1 && !node.isEmpty()) {
            throw new IllegalArgumentException("Alpaca order legs must not nest");
        }
        var results = new ArrayList<Order>(node.size());
        for (JsonNode child : node) {
            results.add(decodeOne(child, depth + 1));
        }
        return List.copyOf(results);
    }

    private static JsonNode nonNull(JsonNode root, String name) {
        JsonNode node = root.get(name);
        return node == null || node.isNull() ? null : node;
    }

    private static String text(JsonNode root, String name) {
        JsonNode node = nonNull(root, name);
        if (node == null) return null;
        if (!node.isTextual()) throw new IllegalArgumentException("Expected text field");
        return node.textValue();
    }

    private static String requiredText(JsonNode root, String name) {
        String value = text(root, name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required text");
        }
        return value;
    }

    private static UUID uuid(JsonNode root, String name) {
        String value = text(root, name);
        return value == null ? null : UUID.fromString(value);
    }

    private static UUID requiredUuid(JsonNode root, String name) {
        return UUID.fromString(requiredText(root, name));
    }

    private static BigDecimal decimal(JsonNode root, String name) {
        JsonNode node = nonNull(root, name);
        if (node == null) return null;
        if (!node.isTextual() && !node.isNumber()) {
            throw new IllegalArgumentException("Expected decimal field");
        }
        return new BigDecimal(node.asText());
    }

    private static Boolean booleanValue(JsonNode root, String name) {
        JsonNode node = nonNull(root, name);
        if (node == null) return null;
        if (!node.isBoolean()) throw new IllegalArgumentException("Expected boolean field");
        return node.booleanValue();
    }
}
