package bbb.atalanta;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Internal JSON-to-record adapter. Public models contain no Jackson classes.
 * Never include provider values in errors: account data is sensitive.
 */
final class AccountDecoder {

    private static final Set<String> KNOWN_FIELDS = Set.of(
            "account_blocked", "account_number", "accrued_fees", "balance_asof", "buying_power",
            "cash", "cash_transferable", "cash_withdrawable", "clearing_broker", "created_at",
            "crypto_status", "crypto_tier", "currency", "daytrade_count", "daytrading_buying_power",
            "effective_buying_power", "equity", "id", "initial_margin", "intraday_adjustments",
            "last_cash", "last_daytrade_count", "last_daytrading_buying_power", "last_equity", "last_initial_margin",
            "last_long_market_value", "last_maintenance_margin", "last_regt_buying_power", "last_short_market_value", "long_market_value",
            "maintenance_margin", "multiplier", "non_marginable_buying_power", "options_approved_level", "options_buying_power",
            "options_trading_level", "pattern_day_trader", "pending_reg_taf_fees", "pending_transfer_in", "pending_transfer_out",
            "portfolio_value", "position_market_value", "previous_close", "regt_buying_power", "short_market_value",
            "shorting_enabled", "sma", "status", "trade_suspended_by_user", "trading_blocked",
            "transfers_blocked"
    );

    private AccountDecoder() {}

    static Result<Account> decode(JsonNode root) {
        if (root == null || !root.isObject()) {
            return Result.err(new AlpacaError.InvalidResponse("Expected an account JSON object"));
        }
        try {
            var additional = new LinkedHashMap<String, Object>();
            var present = new LinkedHashSet<String>();
            root.properties().forEach(entry -> {
                present.add(entry.getKey());
                if (!KNOWN_FIELDS.contains(entry.getKey())) {
                    additional.put(entry.getKey(), javaValue(entry.getValue()));
                }
            });

            var details = new AccountDetails(
                    decimal(root, "regt_buying_power"),
                    decimal(root, "daytrading_buying_power"),
                    decimal(root, "non_marginable_buying_power"),
                    decimal(root, "options_buying_power"),
                    decimal(root, "effective_buying_power"),
                    decimal(root, "portfolio_value"),
                    decimal(root, "accrued_fees"),
                    decimal(root, "pending_transfer_in"),
                    decimal(root, "pending_transfer_out"),
                    decimal(root, "initial_margin"),
                    decimal(root, "maintenance_margin"),
                    decimal(root, "last_maintenance_margin"),
                    decimal(root, "long_market_value"),
                    decimal(root, "short_market_value"),
                    decimal(root, "position_market_value"),
                    decimal(root, "last_equity"),
                    decimal(root, "last_cash"),
                    decimal(root, "last_initial_margin"),
                    decimal(root, "last_long_market_value"),
                    decimal(root, "last_short_market_value"),
                    decimal(root, "last_regt_buying_power"),
                    decimal(root, "last_daytrading_buying_power"),
                    decimal(root, "intraday_adjustments"),
                    decimal(root, "pending_reg_taf_fees"),
                    decimal(root, "sma"),
                    decimal(root, "multiplier"),
                    decimal(root, "cash_withdrawable"),
                    decimal(root, "cash_transferable"),
                    booleanValue(root, "account_blocked"),
                    booleanValue(root, "transfers_blocked"),
                    booleanValue(root, "trade_suspended_by_user"),
                    booleanValue(root, "shorting_enabled"),
                    booleanValue(root, "pattern_day_trader"),
                    integer(root, "options_approved_level"),
                    integer(root, "options_trading_level"),
                    integer(root, "crypto_tier"),
                    integer(root, "daytrade_count"),
                    integer(root, "last_daytrade_count"),
                    text(root, "balance_asof"),
                    text(root, "created_at"),
                    text(root, "previous_close"),
                    text(root, "clearing_broker"),
                    present, additional);

            return Result.ok(new Account(
                    uuid(root, "id"),
                    text(root, "account_number"),
                    requiredText(root, "status"),
                    text(root, "crypto_status"),
                    text(root, "currency"),
                    decimal(root, "cash"),
                    decimal(root, "equity"),
                    decimal(root, "buying_power"),
                    booleanValue(root, "trading_blocked"),
                    details));
        } catch (IllegalArgumentException error) {
            // Never leak provider JSON values, account IDs, or Jackson parser errors.
            return Result.err(new AlpacaError.InvalidResponse("Invalid account field or type"));
        }
    }

    private static JsonNode optional(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node;
    }

    private static String requiredText(JsonNode root, String field) {
        String result = text(root, field);
        if (result == null || result.isBlank()) {
            throw new IllegalArgumentException("Missing or empty required field");
        }
        return result;
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = optional(root, field);
        if (node == null) {
            return null;
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException("Not a string");
        }
        return node.textValue();
    }

    private static UUID uuid(JsonNode root, String field) {
        return UUID.fromString(requiredText(root, field));
    }

    private static BigDecimal decimal(JsonNode root, String field) {
        JsonNode node = optional(root, field);
        if (node == null) {
            return null;
        }
        if (!node.isTextual() && !node.isNumber()) {
            throw new IllegalArgumentException("Not a decimal");
        }
        return new BigDecimal(node.asText());
    }

    private static Boolean booleanValue(JsonNode root, String field) {
        JsonNode node = optional(root, field);
        if (node == null) {
            return null;
        }
        if (!node.isBoolean()) {
            throw new IllegalArgumentException("Not a boolean");
        }
        return node.booleanValue();
    }

    private static Integer integer(JsonNode root, String field) {
        JsonNode node = optional(root, field);
        if (node == null) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new IllegalArgumentException("Not a 32-bit integer");
        }
        return node.intValue();
    }

    private static Object javaValue(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.isIntegralNumber() ? node.bigIntegerValue() : node.decimalValue();
        }
        if (node.isArray()) {
            var result = new ArrayList<Object>();
            node.forEach(item -> result.add(javaValue(item)));
            return Collections.unmodifiableList(result);
        }
        if (node.isObject()) {
            var result = new LinkedHashMap<String, Object>();
            node.properties().forEach(entry ->
                    result.put(entry.getKey(), javaValue(entry.getValue())));
            return Collections.unmodifiableMap(result);
        }
        throw new IllegalArgumentException("Unsupported provider JSON value");
    }
}
