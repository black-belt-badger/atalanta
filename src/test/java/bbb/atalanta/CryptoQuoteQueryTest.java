package bbb.atalanta;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoQuoteQueryTest {

    @Test
    void defaultsOmitUnspecifiedProviderFiltersAndNeverSendTimeframe() {
        var query = CryptoQuoteQuery.of(List.of("BTC/USD", "ETH/USD"));
        assertThat(query.location()).isEqualTo(CryptoLocation.US);
        assertThat(query.toQueryParameters()).containsExactlyEntriesOf(
                Map.of("symbols", "BTC/USD,ETH/USD"));
        assertThat(query.toQueryParameters()).doesNotContainKey("timeframe");
        assertThat(query.start()).isNull();
        assertThat(query.end()).isNull();
        assertThat(query.limit()).isNull();
        assertThat(query.sort()).isNull();
        assertThat(query.pageToken()).isNull();
    }

    @Test
    void copiesSymbolsDefensivelyAndSupportsAllFiveLocations() {
        var mutable = new ArrayList<>(List.of("BTC/USD", "ETH/USDT"));
        var query = CryptoQuoteQuery.of(mutable);
        mutable.clear();
        assertThat(query.symbols()).containsExactly("BTC/USD", "ETH/USDT");
        assertThatThrownBy(() -> query.symbols().add("SOL/USD"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(List.of(CryptoLocation.values())).extracting(CryptoLocation::path)
                .containsExactly("us", "us-1", "us-2", "eu-1", "bs-1");
    }

    @Test
    void validatesSymbolsBeforeConstructingUrlOrCredentials() {
        for (String bad : List.of("", "BTCUSD", "../orders", "btc/USD", "BTC/USD,ETH/USD",
                "BTC/USD?", "BTC%2FUSD", "BTC/USD\n", "BTC/US D", "/USD", "BTC/")) {
            assertThatIllegalArgumentException().as(bad).isThrownBy(
                    () -> CryptoQuoteQuery.of(List.of(bad)));
        }
        assertThatIllegalArgumentException().isThrownBy(
                () -> CryptoQuoteQuery.of(List.of()));
        assertThatIllegalArgumentException().isThrownBy(
                () -> CryptoQuoteQuery.of(Collections.nCopies(31, "BTC/USD")));
        assertThatNullPointerException().isThrownBy(
                () -> CryptoQuoteQuery.of(null));
        assertThatNullPointerException().isThrownBy(
                () -> CryptoQuoteQuery.of(List.of("BTC/USD")).withLocation(null));
    }

    @Test
    void acceptsInclusiveDatesRfc3339WithNanosecondsAndOriginalOffsetSpelling() {
        var query = CryptoQuoteQuery.of(List.of("BTC/USD", "ETH/USD"))
                .withLocation(CryptoLocation.EU_1)
                .withStart("2026-10-02T00:00:00.123456789Z")
                .withEnd("2026-10-02T03:00:00.123456789+03:00")
                .withLimit(10000)
                .withSort(CryptoQuoteQuery.Sort.DESC)
                .withPageToken("ABC+/==");
        assertThat(query.toQueryParameters()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "symbols", "BTC/USD,ETH/USD",
                "start", "2026-10-02T00:00:00.123456789Z",
                "end", "2026-10-02T03:00:00.123456789+03:00",
                "limit", "10000",
                "sort", "desc",
                "page_token", "ABC+/=="));
        assertThat(query.withPageToken("NEXT").pageToken()).isEqualTo("NEXT");
        assertThat(query.pageToken()).isEqualTo("ABC+/==");
        assertThat(query.withPageToken(null).pageToken()).isNull();
        assertThat(query.toQueryParameters()).doesNotContainKey("timeframe");
    }

    @Test
    void rejectsMalformedDatesAndReversedInstantBounds() {
        var query = CryptoQuoteQuery.of(List.of("SOL/USD"));
        for (String bad : List.of("", "yesterday", "2026-13-01", "2026-02-30",
                "2026-11-13T10:11:12", "2026-11-13T10:11:12+25:00",
                " 2026-11-13", "2026-11-13\n",
                "2026-11-13T10:11:12+01:00&x=1")) {
            assertThatIllegalArgumentException().as(bad)
                    .isThrownBy(() -> query.withStart(bad));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> query
                .withStart("2026-11-13T10:00:00+02:00")
                .withEnd("2026-11-13T07:59:59Z"));
        assertThatIllegalArgumentException().isThrownBy(() -> query
                .withStart("2026-11-14")
                .withEnd("2026-11-13"));
        assertThat(query.withStart("2026-11-13")
                .withEnd("2026-11-13T00:00:00Z").end()).isNotNull();
    }

    @Test
    void validatesLimitsAndOpaqueTokens() {
        var query = CryptoQuoteQuery.of(List.of("BTC/USD"));
        for (int bad : new int[]{0, -1, 10001, Integer.MAX_VALUE}) {
            assertThatIllegalArgumentException().isThrownBy(() -> query.withLimit(bad));
        }
        for (String bad : List.of("", "not opaque", "line\nbreak", "tab\tbreak",
                "nonascii-€", "0123456789".repeat(205))) {
            assertThatIllegalArgumentException().isThrownBy(() -> query.withPageToken(bad));
        }
        assertThat(query.withLimit(1).limit()).isEqualTo(1);
        assertThat(query.withLimit(10000).limit()).isEqualTo(10000);
        assertThat(query.withPageToken("ABC+/==").pageToken()).isEqualTo("ABC+/==");
    }
}
