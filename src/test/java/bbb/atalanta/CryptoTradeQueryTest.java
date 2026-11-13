package bbb.atalanta;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoTradeQueryTest {

    @Test
    void requiresOneToThirtyValidatedSymbolsAndCopiesDefensively() {
        var mutable = new ArrayList<>(List.of("BTC/USD", "ETH/USD"));
        var query = CryptoTradeQuery.of(mutable);
        mutable.clear();
        assertThat(query.symbols()).containsExactly("BTC/USD", "ETH/USD");
        assertThatThrownBy(() -> query.symbols().add("SOL/USD"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatIllegalArgumentException().isThrownBy(() -> CryptoTradeQuery.of(List.of()));
        assertThatIllegalArgumentException().isThrownBy(() ->
                CryptoTradeQuery.of(java.util.Collections.nCopies(31, "BTC/USD")));
        assertThatNullPointerException().isThrownBy(() -> CryptoTradeQuery.of(null));
        for (String invalid : List.of("", "BTCUSD", "btc/USD", "BTC/USD,ETH/USD",
                "../orders", "BTC/USD;", "BTC/USD/", "BTC/US D", "BTC/USD?x=1",
                "BTC%2FUSD", "BTC/USD\n")) {
            assertThatIllegalArgumentException().as(invalid)
                    .isThrownBy(() -> CryptoTradeQuery.of(List.of(invalid)));
        }
    }

    @Test
    void omitsOptionalParametersInsteadOfInventingProviderDefaults() {
        var query = CryptoTradeQuery.of(List.of("BTC/USD", "ETH/BTC"));
        assertThat(query.location()).isEqualTo(CryptoLocation.US);
        assertThat(query.start()).isNull();
        assertThat(query.end()).isNull();
        assertThat(query.pageToken()).isNull();
        assertThat(query.toQueryParameters()).containsExactlyInAnyOrderEntriesOf(
                Map.of("symbols", "BTC/USD,ETH/BTC"));
        assertThat(query.toQueryParameters()).doesNotContainKeys(
                "timeframe", "start", "end", "sort", "limit", "page_token");
    }

    @Test
    void includesAllProviderFiltersWithoutTimeframe() {
        var query = CryptoTradeQuery.of(List.of("BTC/USD", "ETH/USD"))
                .withLocation(CryptoLocation.EU_1)
                .withStart("2026-10-02T00:00:00.123456789Z")
                .withEnd("2026-10-02T03:00:00.123456789+03:00")
                .withSort(CryptoTradeQuery.Sort.DESC)
                .withLimit(10000)
                .withPageToken("ABC+/==");
        assertThat(query.location()).isEqualTo(CryptoLocation.EU_1);
        assertThat(query.toQueryParameters()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "symbols", "BTC/USD,ETH/USD",
                "start", "2026-10-02T00:00:00.123456789Z",
                "end", "2026-10-02T03:00:00.123456789+03:00",
                "sort", "desc",
                "limit", "10000",
                "page_token", "ABC+/=="));
        assertThat(query.withPageToken("NEW").pageToken()).isEqualTo("NEW");
        assertThat(query.pageToken()).isEqualTo("ABC+/==");
    }

    @Test
    void comparesAbsoluteTimesNotTextualOffsets() {
        var query = CryptoTradeQuery.of(List.of("BTC/USD"));
        assertThat(query.withStart("2026-10-02T03:00:00+03:00")
                .withEnd("2026-10-02T00:00:00Z").end()).isNotNull();
        assertThatIllegalArgumentException().isThrownBy(() ->
                query.withStart("2026-10-02T03:00:01+03:00")
                        .withEnd("2026-10-02T00:00:00Z"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                query.withStart("2026-10-04").withEnd("2026-10-03"));
    }

    @Test
    void acceptsDatesAndQualifiedNanosecondTimestampsButNotNaiveTimes() {
        var query = CryptoTradeQuery.of(List.of("BTC/USD"));
        assertThat(query.withStart("2026-10-02").start()).isEqualTo("2026-10-02");
        assertThat(query.withEnd("2026-10-03T23:59:59.123456789-02:00").end())
                .isEqualTo("2026-10-03T23:59:59.123456789-02:00");
        for (String invalid : List.of("", "today", "2026-13-01", "2026-02-30",
                "2026-10-02T00:00:00", "2026-10-02T00:00:00+25:00",
                "2026-10-02\n", " 2026-10-02", "2026-10-02T00:00:00Z?x=1")) {
            assertThatIllegalArgumentException().as(invalid)
                    .isThrownBy(() -> query.withStart(invalid));
        }
    }

    @Test
    void boundsPageLimitAndOpaqueTokenWithoutModifyingOriginal() {
        var query = CryptoTradeQuery.of(List.of("BTC/USD"));
        assertThat(query.withLimit(1).limit()).isEqualTo(1);
        assertThat(query.withLimit(10000).limit()).isEqualTo(10000);
        assertThat(query.withPageToken("abc+/==").pageToken()).isEqualTo("abc+/==");
        assertThat(query.withPageToken(null).pageToken()).isNull();
        for (int n : new int[]{-1, 0, 10001, Integer.MAX_VALUE}) {
            assertThatIllegalArgumentException().isThrownBy(() -> query.withLimit(n));
        }
        for (String invalid : List.of("", "spaces invalid", "line\nbreak",
                "tab\tbreak", "€", "a".repeat(2049))) {
            assertThatIllegalArgumentException().as(invalid)
                    .isThrownBy(() -> query.withPageToken(invalid));
        }
    }

    @Test
    void allFiveAllowedLocationPathSegmentsRemainTyped() {
        assertThat(List.of(CryptoLocation.values()))
                .extracting(CryptoLocation::path)
                .containsExactly("us", "us-1", "us-2", "eu-1", "bs-1");
    }
}
