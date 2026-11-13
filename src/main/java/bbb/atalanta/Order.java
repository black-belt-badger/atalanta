package bbb.atalanta;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable Alpaca paper-account order.
 *
 * <p>All money, quantity, price and percentage values use exact
 * {@link BigDecimal}. The documented order enums are represented as strings
 * so unknown future values remain readable. All optional fields are nullable,
 * and {@link #supplied(String)} distinguishes absent fields from JSON null.
 *
 * <p>For nested=true, a primary order's legs can contain order records.
 * Provider legs never nest beyond one level.
 */
public record Order(
        UUID id,
        String clientOrderId,
        UUID assetId,
        String assetClass,
        String symbol,
        String side,
        String positionIntent,
        String status,
        String type,
        String orderType,
        String orderClass,
        String timeInForce,
        BigDecimal qty,
        BigDecimal notional,
        BigDecimal filledQty,
        BigDecimal filledAvgPrice,
        BigDecimal limitPrice,
        BigDecimal stopPrice,
        BigDecimal trailPrice,
        BigDecimal trailPercent,
        BigDecimal hwm,
        Boolean extendedHours,
        String createdAt,
        String updatedAt,
        String submittedAt,
        String filledAt,
        String expiredAt,
        String canceledAt,
        String failedAt,
        String replacedAt,
        UUID replacedBy,
        UUID replaces,
        String expiresAt,
        UUID groupId,
        String groupRole,
        String source,
        String subtag,
        List<Order> legs,
        Set<String> presentFields,
        Map<String, Object> additionalFields) {

    public Order {
        Objects.requireNonNull(id, "id");
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status must be nonblank");
        }
        if (legs != null) {
            legs = List.copyOf(legs);
        }
        Objects.requireNonNull(presentFields, "presentFields");
        presentFields = Collections.unmodifiableSet(new LinkedHashSet<>(presentFields));
        additionalFields = ProviderJson.immutableMap(additionalFields);
    }

    /** Was the field in the response, even when its value was JSON null? */
    public boolean supplied(String wireField) {
        return presentFields.contains(wireField);
    }

    /** Prevent accidental logging of orders, account linkage, and prices. */
    @Override
    public String toString() {
        return "Order[redacted]";
    }
}
