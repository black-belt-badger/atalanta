package bbb.atalanta;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable snapshot of an open Alpaca paper-account position.
 *
 * <p>Prices, values, ratios, and fractional quantities are exact decimals.
 * Provider asset-class/side strings are not restricted to today's enum values.
 * Optional values are nullable; {@link #supplied(String)} distinguishes missing
 * provider properties from explicit JSON null and numeric zero.
 *
 * <p>Class-dependent fields (including prices during an IPO allocation) may be
 * null even when the generic provider schema marks them required.
 */
public record Position(
        UUID assetId,
        String symbol,
        String exchange,
        String assetClass,
        Boolean assetMarginable,
        BigDecimal qty,
        BigDecimal qtyAvailable,
        String side,
        BigDecimal avgEntryPrice,
        BigDecimal costBasis,
        BigDecimal marketValue,
        BigDecimal currentPrice,
        BigDecimal lastdayPrice,
        BigDecimal changeToday,
        BigDecimal unrealizedPl,
        BigDecimal unrealizedPlpc,
        BigDecimal unrealizedIntradayPl,
        BigDecimal unrealizedIntradayPlpc,
        BigDecimal swapRate,
        BigDecimal prevSwapRate,
        BigDecimal avgEntrySwapRate,
        PositionUsd usd,
        Set<String> presentFields,
        Map<String, Object> additionalFields) {

    public Position {
        Objects.requireNonNull(assetId, "assetId");
        nonblank(symbol, "symbol");
        nonblank(assetClass, "assetClass");
        nonblank(side, "side");
        Objects.requireNonNull(qty, "qty");
        Objects.requireNonNull(presentFields, "presentFields");
        presentFields = Collections.unmodifiableSet(new LinkedHashSet<>(presentFields));
        additionalFields = ProviderJson.immutableMap(additionalFields);
    }

    public boolean supplied(String wireField) {
        return presentFields.contains(wireField);
    }

    private static void nonblank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }

    /** Account holdings and values must not accidentally appear in log output. */
    @Override
    public String toString() {
        return "Position[redacted]";
    }
}
