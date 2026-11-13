package bbb.atalanta;

import java.util.Set;

/**
 * A validated Alpaca bar aggregation interval. Retains the provider's accepted
 * textual form, including compact aliases; never silently changes the interval.
 */
public record CryptoTimeframe(String value) {
    private static final Set<Integer> MONTHS = Set.of(1, 2, 3, 4, 6, 12);

    public CryptoTimeframe {
        if (!valid(value)) {
            throw new IllegalArgumentException("Invalid Alpaca crypto bar timeframe");
        }
    }

    public static CryptoTimeframe minutes(int count) {
        return new CryptoTimeframe(count + "Min");
    }

    public static CryptoTimeframe hours(int count) {
        return new CryptoTimeframe(count + "Hour");
    }

    public static CryptoTimeframe days() {
        return new CryptoTimeframe("1Day");
    }

    private static boolean valid(String value) {
        if (value == null) return false;
        if (value.equals("1Day") || value.equals("1D")
                || value.equals("1Week") || value.equals("1W")) return true;
        for (String suffix : new String[]{"Min", "T", "Hour", "H", "Month", "M"}) {
            if (!value.endsWith(suffix)) continue;
            String digits = value.substring(0, value.length() - suffix.length());
            if (!digits.matches("[1-9][0-9]?")) continue;
            int number = Integer.parseInt(digits);
            if (suffix.equals("Min") || suffix.equals("T")) return number <= 59;
            if (suffix.equals("Hour") || suffix.equals("H")) return number <= 23;
            return MONTHS.contains(number);
        }
        return false;
    }
}
