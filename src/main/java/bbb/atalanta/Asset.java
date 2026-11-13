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
 * A read-only Alpaca asset snapshot. Supported and future provider enum strings
 * are retained verbatim. Decimal fields are exact and no Jackson types leak.
 *
 * <p>Optional values are null when absent or JSON null. Use
 * {@link #supplied(String)} to distinguish the two cases.
 */
public record Asset(
        UUID id,
        String assetClass,
        String exchange,
        String symbol,
        String name,
        String status,
        boolean tradable,
        boolean marginable,
        boolean shortable,
        boolean fractionable,
        Boolean easyToBorrow,
        String borrowStatus,
        String cusip,
        BigDecimal marginRequirementLong,
        BigDecimal marginRequirementShort,
        BigDecimal maintenanceMarginRequirement,
        BigDecimal minOrderSize,
        BigDecimal minTradeIncrement,
        BigDecimal priceIncrement,
        List<String> attributes,
        Set<String> presentFields,
        Map<String, Object> additionalFields) {

    public Asset {
        Objects.requireNonNull(id, "id");
        nonblank(assetClass, "assetClass");
        nonblank(exchange, "exchange");
        nonblank(symbol, "symbol");
        nonblank(name, "name");
        nonblank(status, "status");
        if (attributes != null) {
            attributes = List.copyOf(attributes);
        }
        Objects.requireNonNull(presentFields, "presentFields");
        presentFields = Collections.unmodifiableSet(new LinkedHashSet<>(presentFields));
        additionalFields = ProviderJson.immutableMap(additionalFields);
    }

    /** True if the original response included this JSON field, even when null. */
    public boolean supplied(String wireField) {
        return presentFields.contains(wireField);
    }

    private static void nonblank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
