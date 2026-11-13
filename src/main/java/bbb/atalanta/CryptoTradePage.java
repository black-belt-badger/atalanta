package bbb.atalanta;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** One immutable provider page of symbol-keyed crypto executions. */
public record CryptoTradePage(
        Map<String, List<CryptoTrade>> trades,
        String nextPageToken,
        Map<String, Object> additionalFields) {

    public CryptoTradePage {
        var copy = new LinkedHashMap<String, List<CryptoTrade>>();
        trades.forEach((symbol, values) -> {
            if (symbol == null || !symbol.matches("[A-Z0-9]+/[A-Z0-9]+")
                    || values == null) {
                throw new IllegalArgumentException("Invalid crypto trades page group");
            }
            copy.put(symbol, List.copyOf(values));
        });
        trades = Collections.unmodifiableMap(copy);
        if (nextPageToken != null && nextPageToken.isEmpty()) nextPageToken = null;
        additionalFields = ProviderJson.immutableMap(additionalFields);
    }

    public Optional<String> continuation() {
        return Optional.ofNullable(nextPageToken);
    }

    public long tradeCount() {
        return trades.values().stream().mapToLong(List::size).sum();
    }
}
