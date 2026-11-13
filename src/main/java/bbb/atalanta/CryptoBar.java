package bbb.atalanta;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A single Alpaca crypto OHLCV bar with exact numbers and source timestamp. */
public record CryptoBar(
        String timestamp,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        BigInteger tradeCount,
        BigDecimal vwap,
        Set<String> presentFields,
        Map<String, Object> additionalFields) {

    public CryptoBar {
        if (timestamp == null || timestamp.isBlank()) {
            throw new IllegalArgumentException("Bar timestamp is required");
        }
        Objects.requireNonNull(open, "open");
        Objects.requireNonNull(high, "high");
        Objects.requireNonNull(low, "low");
        Objects.requireNonNull(close, "close");
        Objects.requireNonNull(volume, "volume");
        if (tradeCount != null && tradeCount.signum() < 0) {
            throw new IllegalArgumentException("Bar trade count must be nonnegative");
        }
        presentFields = Set.copyOf(presentFields);
        additionalFields = ProviderJson.immutableMap(additionalFields);
    }

    public boolean supplied(String wireName) {
        return presentFields.contains(wireName);
    }
}
