package bbb.atalanta;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Validated, immutable intent for exactly one crypto PAPER order.
 *
 * <p>This is preparation only: constructing an intent never sends HTTP.
 * To submit, explicitly call {@link #authorize()} then
 * {@link Atalanta#submitPaperOrder(ConfirmedPaperOrder)}.
 * A stable clientOrderId aids investigation after uncertain outcomes but
 * cannot be assumed to make remote requests idempotent.
 */
public record CryptoOrderRequest(
        String symbol,
        Side side,
        Kind kind,
        TimeInForce timeInForce,
        BigDecimal quantity,
        BigDecimal notional,
        BigDecimal limitPrice,
        String clientOrderId) {

    public enum Side {
        BUY("buy"), SELL("sell");
        private final String wire;
        Side(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    public enum Kind {
        MARKET("market"), LIMIT("limit");
        private final String wire;
        Kind(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    public enum TimeInForce {
        GTC("gtc"), IOC("ioc");
        private final String wire;
        TimeInForce(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    public CryptoOrderRequest {
        if (symbol == null || !symbol.matches("[A-Z0-9]+/[A-Z0-9]+")) {
            throw new IllegalArgumentException("Expected one uppercase BASE/QUOTE pair");
        }
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(timeInForce, "timeInForce");
        if ((quantity == null) == (notional == null)) {
            throw new IllegalArgumentException("Exactly one of quantity or notional is required");
        }
        if (quantity != null) validateAmount(quantity, "quantity", 9);
        if (notional != null) {
            validateAmount(notional, "notional", symbol.endsWith("/USD") ? 2 : 9);
        }
        if (kind == Kind.MARKET && limitPrice != null) {
            throw new IllegalArgumentException("Market orders cannot have a limit price");
        }
        if (kind == Kind.LIMIT) {
            if (limitPrice == null || notional != null) {
                throw new IllegalArgumentException("Limit orders require quantity and limit price");
            }
            validateAmount(limitPrice, "limitPrice", 9);
        }
        if (clientOrderId == null || clientOrderId.isBlank() || clientOrderId.length() > 128
                || !clientOrderId.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("Invalid stable clientOrderId");
        }
    }

    private static void validateAmount(BigDecimal value, String label, int maxFractionalDigits) {
        // Guard scale and precision BEFORE toPlainString: a BigDecimal such
        // as 1E+1000000000 must not trigger a massive allocation.
        if (value.signum() <= 0 || value.scale() > maxFractionalDigits
                || value.scale() < -64 || value.precision() > 64
                || value.toPlainString().length() > 64) {
            throw new IllegalArgumentException(
                    label + " must be a positive decimal with allowed precision");
        }
    }

    public static CryptoOrderRequest marketQuantity(String symbol, Side side,
            TimeInForce tif, BigDecimal quantity, String clientOrderId) {
        return new CryptoOrderRequest(symbol, side, Kind.MARKET, tif,
                quantity, null, null, clientOrderId);
    }

    public static CryptoOrderRequest marketNotional(String symbol, Side side,
            TimeInForce tif, BigDecimal notional, String clientOrderId) {
        return new CryptoOrderRequest(symbol, side, Kind.MARKET, tif,
                null, notional, null, clientOrderId);
    }

    public static CryptoOrderRequest limitQuantity(String symbol, Side side,
            TimeInForce tif, BigDecimal quantity, BigDecimal limitPrice, String clientOrderId) {
        return new CryptoOrderRequest(symbol, side, Kind.LIMIT, tif,
                quantity, null, limitPrice, clientOrderId);
    }

    /** Explicit operator/programmatic opt-in, separate from preparation. */
    public ConfirmedPaperOrder authorize() {
        return new ConfirmedPaperOrder(this);
    }

    /** Never print trading amounts or identifiers from record toString. */
    @Override
    public String toString() {
        return "CryptoOrderRequest[redacted]";
    }
}
