package bbb.atalanta;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Optional Alpaca paper-account fields, including deprecated properties
 * that may still appear in older responses.
 *
 * <p>All documented amounts have exact decimal types. Optional values are
 * nullable: if null, consult presentFields to distinguish omitted from
 * explicit provider null. Additional new provider fields are retained in
 * immutable standard-Java values, never Jackson JSON types.
 */
public record AccountDetails(
        BigDecimal regtBuyingPower,
        BigDecimal daytradingBuyingPower,
        BigDecimal nonMarginableBuyingPower,
        BigDecimal optionsBuyingPower,
        BigDecimal effectiveBuyingPower,
        BigDecimal portfolioValue,
        BigDecimal accruedFees,
        BigDecimal pendingTransferIn,
        BigDecimal pendingTransferOut,
        BigDecimal initialMargin,
        BigDecimal maintenanceMargin,
        BigDecimal lastMaintenanceMargin,
        BigDecimal longMarketValue,
        BigDecimal shortMarketValue,
        BigDecimal positionMarketValue,
        BigDecimal lastEquity,
        BigDecimal lastCash,
        BigDecimal lastInitialMargin,
        BigDecimal lastLongMarketValue,
        BigDecimal lastShortMarketValue,
        BigDecimal lastRegtBuyingPower,
        BigDecimal lastDaytradingBuyingPower,
        BigDecimal intradayAdjustments,
        BigDecimal pendingRegTafFees,
        BigDecimal sma,
        BigDecimal multiplier,
        BigDecimal cashWithdrawable,
        BigDecimal cashTransferable,
        Boolean accountBlocked,
        Boolean transfersBlocked,
        Boolean tradeSuspendedByUser,
        Boolean shortingEnabled,
        Boolean patternDayTrader,
        Integer optionsApprovedLevel,
        Integer optionsTradingLevel,
        Integer cryptoTier,
        Integer daytradeCount,
        Integer lastDaytradeCount,
        String balanceAsof,
        String createdAt,
        String previousClose,
        String clearingBroker,
        Set<String> presentFields,
        Map<String, Object> additionalFields) {

    public AccountDetails {
        Objects.requireNonNull(presentFields, "presentFields");
        Objects.requireNonNull(additionalFields, "additionalFields");
        presentFields = Collections.unmodifiableSet(new LinkedHashSet<>(presentFields));
        additionalFields = immutableMap(additionalFields);
    }

    /** Distinguish a missing provider property from its explicit JSON null. */
    public boolean supplied(String wireField) {
        return presentFields.contains(wireField);
    }

    /** Immutable defensive conversion of only standard Java values. */
    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        var copy = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> copy.put(
                Objects.requireNonNull(key, "field name"), immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> nested) {
            var cast = new LinkedHashMap<String, Object>();
            nested.forEach((key, item) -> cast.put(
                    (String) Objects.requireNonNull(key, "nested field"), item));
            return immutableMap(cast);
        }
        if (value instanceof List<?> list) {
            var copy = new ArrayList<Object>();
            list.forEach(item -> copy.add(immutableValue(item)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    /** Account data can be private: avoid leaking it via automatic record toString. */
    @Override
    public String toString() {
        return "AccountDetails[redacted]";
    }
}
