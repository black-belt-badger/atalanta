package bbb.atalanta;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CryptoBarQueryTest {

    @Test
    void timeframeAcceptsAllDocumentedRangesAndAliases() {
        for (String frame : List.of("1Min", "5Min", "59Min", "1T", "59T",
                "1Hour", "23Hour", "3H", "1Day", "1D", "1Week", "1W",
                "1Month", "2M", "3Month", "4M", "6Month", "12Month")) {
            assertThat(new CryptoTimeframe(frame).value()).isEqualTo(frame);
        }
        assertThat(CryptoTimeframe.minutes(5).value()).isEqualTo("5Min");
        assertThat(CryptoTimeframe.hours(12).value()).isEqualTo("12Hour");
        assertThat(CryptoTimeframe.days().value()).isEqualTo("1Day");
    }

    @Test
    void timeframeRejectsInvalidOrUnboundedRanges() {
        for (String invalid : List.of("", "0Min", "60Min", "99Min", "24Hour",
                "2Day", "2Week", "5Month", "7Month", "13Month", "01Min",
                "1min", "1Minute", "1Hour?foo=bar", "-1Min", "1.5Min",
                "1ms", "1Y", "1M\nx")) {
            assertThatIllegalArgumentException().isThrownBy(
                    () -> new CryptoTimeframe(invalid));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new CryptoTimeframe(null));
    }

    @Test
    void queryRequiresValidPairsAndDefensivelyCopiesSymbols() {
        List<String> mutable = new ArrayList<>(List.of("BTC/USD", "ETH/USDT"));
        CryptoBarQuery query = CryptoBarQuery.of(mutable, CryptoTimeframe.days());
        mutable.clear();
        assertThat(query.symbols()).containsExactly("BTC/USD", "ETH/USDT");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                query.symbols().add("SOL/USD"))
                .isInstanceOf(UnsupportedOperationException.class);
        for (String invalid : List.of("", "BTCUSD", "btc/USD", "BTC/USD,ETH/USD",
                "../orders", "BTC/USD;", "BTC/USD/", "BTC/US D",
                "BTC/USD?x=1", "BTC%2FUSD", "ETH/USD\n")) {
            assertThatIllegalArgumentException().isThrownBy(
                    () -> CryptoBarQuery.of(List.of(invalid), CryptoTimeframe.days()));
        }
        assertThatIllegalArgumentException().isThrownBy(() ->
                CryptoBarQuery.of(List.of(), CryptoTimeframe.days()));
        assertThatIllegalArgumentException().isThrownBy(() ->
                CryptoBarQuery.of(java.util.Collections.nCopies(31, "BTC/USD"),
                        CryptoTimeframe.days()));
        org.assertj.core.api.Assertions.assertThatNullPointerException().isThrownBy(() ->
                CryptoBarQuery.of(null, CryptoTimeframe.days()));
    }

    @Test
    void optionalFiltersAreOmittedRatherThanInventingProviderDefaults() {
        CryptoBarQuery query = CryptoBarQuery.of(
                List.of("BTC/USD", "ETH/USD"), new CryptoTimeframe("1Hour"));
        assertThat(query.location()).isEqualTo(CryptoLocation.US);
        assertThat(query.toQueryParameters()).containsExactlyInAnyOrderEntriesOf(
                Map.of("symbols", "BTC/USD,ETH/USD", "timeframe", "1Hour"));
        assertThat(query.pageToken()).isNull();
        assertThat(query.limit()).isNull();
        assertThat(query.sort()).isNull();
    }

    @Test
    void allSupportedLocationsHaveDistinctSafeSegments() {
        assertThat(List.of(CryptoLocation.values()))
                .extracting(CryptoLocation::path)
                .containsExactly("us", "us-1", "us-2", "eu-1", "bs-1");
    }

    @Test
    void supportsInclusiveDateBoundsAndPreservesSourceTimezoneSpelling() {
        CryptoBarQuery query = CryptoBarQuery.of(List.of("BTC/USD"), CryptoTimeframe.minutes(1))
                .withStart("2026-10-02T00:00:00.123456789Z")
                .withEnd("2026-10-02T03:00:00.123456789+03:00")
                .withLocation(CryptoLocation.EU_1)
                .withLimit(10000)
                .withSort(CryptoBarQuery.Sort.DESC)
                .withPageToken("ABC+/==");
        assertThat(query.toQueryParameters()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "symbols", "BTC/USD",
                "timeframe", "1Min",
                "start", "2026-10-02T00:00:00.123456789Z",
                "end", "2026-10-02T03:00:00.123456789+03:00",
                "limit", "10000",
                "sort", "desc",
                "page_token", "ABC+/=="));
        assertThat(query.location()).isEqualTo(CryptoLocation.EU_1);
        assertThat(query.withPageToken("NEXT").pageToken()).isEqualTo("NEXT");
        assertThat(query.pageToken()).isEqualTo("ABC+/==");
    }

    @Test
    void rejectsReversedAbsoluteInstantsIncludingDifferentOffsets() {
        var query = CryptoBarQuery.of(List.of("BTC/USD"), CryptoTimeframe.days());
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
    void rejectsMalformedDateTimeAndNaiveTimestampStrings() {
        var query = CryptoBarQuery.of(List.of("BTC/USD"), CryptoTimeframe.days());
        for (String value : List.of("", "2026-02-30", "2026-13-01",
                "2026-11-13T10:11:12", "today", "2026-11-13T10:11:12Z/../",
                "2026-11-13T10:11:12+25:00", "2026-11-13\n",
                " 2026-11-13", "2026-11-13T10:11:12+01:00&x=1")) {
            assertThatIllegalArgumentException().as(value).isThrownBy(
                    () -> query.withStart(value));
        }
    }

    @Test
    void validatesProviderPageLimitAndOpaqueContinuation() {
        var query = CryptoBarQuery.of(List.of("BTC/USD"), CryptoTimeframe.days());
        for (int limit : new int[]{0, -1, 10001, Integer.MAX_VALUE}) {
            assertThatIllegalArgumentException().isThrownBy(
                    () -> query.withLimit(limit));
        }
        for (String token : List.of("", "has space", "line\nbreak",
                "tab\tbreak", "nonascii-€", "0123456789".repeat(205))) {
            assertThatIllegalArgumentException().isThrownBy(
                    () -> query.withPageToken(token));
        }
        assertThat(query.withPageToken("AA+/==").pageToken()).isEqualTo("AA+/==");
        assertThat(query.withPageToken(null).pageToken()).isNull();
    }
}
