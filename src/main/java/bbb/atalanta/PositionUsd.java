package bbb.atalanta;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Optional USD-denominated position valuation for local-currency accounts.
 * All amounts and return ratios are precise, and unknown fields are retained.
 */
public record PositionUsd(
        BigDecimal avgEntryPrice,
        BigDecimal changeToday,
        BigDecimal costBasis,
        BigDecimal currentPrice,
        BigDecimal lastdayPrice,
        BigDecimal marketValue,
        BigDecimal unrealizedIntradayPl,
        BigDecimal unrealizedIntradayPlpc,
        BigDecimal unrealizedPl,
        BigDecimal unrealizedPlpc,
        Set<String> presentFields,
        Map<String, Object> additionalFields) {

    public PositionUsd {
        Objects.requireNonNull(presentFields, "presentFields");
        presentFields = Collections.unmodifiableSet(new LinkedHashSet<>(presentFields));
        additionalFields = ProviderJson.immutableMap(additionalFields);
    }

    public boolean supplied(String wireField) {
        return presentFields.contains(wireField);
    }

    @Override
    public String toString() {
        return "PositionUsd[redacted]";
    }
}
