package bbb.atalanta;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class OrderQueryTest {

    private static final UUID BEFORE = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final UUID AFTER = UUID.fromString("22222222-3333-4444-8555-666666666666");

    @Test
    void defaultsRemainServerDefaultsAndNoImaginaryPageToken() {
        OrderQuery query = OrderQuery.defaults();

        assertThat(query.toQueryParameters()).isEmpty();
        assertThat(query.toQueryParameters()).isUnmodifiable();
        assertThat(query.status()).isNull();
        assertThat(query.limit()).isNull();
        assertThat(query.direction()).isNull();
        assertThat(query.nested()).isNull();
        assertThat(query.symbols()).isEmpty();
        assertThat(query.assetClasses()).isEmpty();
        assertThat(query.toString()).doesNotContain("page_token");
    }

    @Test
    void serializesAllNonPaginationParametersAndTimestampBounds() {
        Instant after = Instant.parse("2026-11-11T00:00:00.123456789Z");
        Instant until = Instant.parse("2026-11-13T11:30:00Z");
        OrderQuery query = OrderQuery.defaults()
                .withStatus("all")
                .withLimit(500)
                .withAfter(after)
                .withUntil(until)
                .withDirection("asc")
                .withNested(true)
                .withSymbols(List.of("BTCUSD", "ETHUSD", "AAPL"))
                .withSide("buy")
                .withAssetClasses(List.of("crypto", "us_equity", "crypto_perp"));

        assertThat(query.toQueryParameters()).containsExactly(
                Map.entry("status", "all"),
                Map.entry("limit", "500"),
                Map.entry("after", "2026-11-11T00:00:00.123456789Z"),
                Map.entry("until", "2026-11-13T11:30:00Z"),
                Map.entry("direction", "asc"),
                Map.entry("nested", "true"),
                Map.entry("symbols", "BTCUSD,ETHUSD,AAPL"),
                Map.entry("side", "buy"),
                Map.entry("asset_class", "crypto,us_equity,crypto_perp"));
    }

    @Test
    void supportsBothExclusiveCursorDirectionsIndividually() {
        assertThat(OrderQuery.defaults().withBeforeOrderId(BEFORE).toQueryParameters())
                .containsExactly(Map.entry("before_order_id", BEFORE.toString()));
        assertThat(OrderQuery.defaults().withAfterOrderId(AFTER).toQueryParameters())
                .containsExactly(Map.entry("after_order_id", AFTER.toString()));
        assertThat(OrderQuery.defaults().withAfterOrderId(AFTER)
                .withStatus("closed").toQueryParameters())
                .containsEntry("status", "closed")
                .containsEntry("after_order_id", AFTER.toString());
    }

    @Test
    void rejectsTimestampCursorMixAndOpposingCursorMix() {
        Instant after = Instant.parse("2026-11-11T00:00:00Z");
        Instant until = Instant.parse("2026-11-12T00:00:00Z");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> OrderQuery.defaults()
                        .withBeforeOrderId(BEFORE).withAfterOrderId(AFTER));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> OrderQuery.defaults()
                        .withAfterOrderId(AFTER).withBeforeOrderId(BEFORE));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> OrderQuery.defaults()
                        .withAfter(after).withAfterOrderId(AFTER));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> OrderQuery.defaults()
                        .withBeforeOrderId(BEFORE).withUntil(until));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> OrderQuery.defaults()
                        .withAfterOrderId(AFTER).withAfter(after));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> OrderQuery.defaults()
                        .withAfter(until).withUntil(after));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> OrderQuery.defaults()
                        .withAfter(after).withUntil(after));
    }

    @Test
    void enforcesLimitsAndDocumentedEnums() {
        assertThat(OrderQuery.defaults().withLimit(1).limit()).isEqualTo(1);
        assertThat(OrderQuery.defaults().withLimit(500).limit()).isEqualTo(500);

        for (int limit : new int[]{-5, 0, 501, 50_000}) {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    OrderQuery.defaults().withLimit(limit));
        }
        for (String status : List.of("", "pending", "ACTIVE", "open|all")) {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    OrderQuery.defaults().withStatus(status));
        }
        for (String direction : List.of("", "ASC", "sideways")) {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    OrderQuery.defaults().withDirection(direction));
        }
        for (String side : List.of("", "long", "BUY", "buy&status=all")) {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    OrderQuery.defaults().withSide(side));
        }
    }

    @Test
    void falseNestedParameterIsExplicitlySerialized() {
        assertThat(OrderQuery.defaults().withNested(false).toQueryParameters())
                .containsExactly(Map.entry("nested", "false"));
        assertThat(OrderQuery.defaults().withNested(true).toQueryParameters())
                .containsExactly(Map.entry("nested", "true"));
    }

    @Test
    void enforcesSymbolAndAssetClassTokenSafety() {
        for (String invalid : List.of("", "BTC/USD", "BTCUSD,ETHUSD", "AAPL&limit=500", "AA BB")) {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    OrderQuery.defaults().withSymbols(List.of(invalid)));
        }
        for (String invalid : List.of("", "us-equity", "crypto,us_equity", "crypto&nested=true")) {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    OrderQuery.defaults().withAssetClasses(List.of(invalid)));
        }
        assertThatIllegalArgumentException().isThrownBy(() ->
                OrderQuery.defaults().withSymbols(Arrays.asList("BTCUSD", null)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                OrderQuery.defaults().withAssetClasses(Arrays.asList("crypto", null)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                OrderQuery.defaults().withSymbols(null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                OrderQuery.defaults().withAssetClasses(null));
    }

    @Test
    void retainsCopiesInsteadOfCallerOwnedCollections() {
        var original = new ArrayList<>(List.of("AAPL"));
        OrderQuery query = OrderQuery.defaults().withSymbols(original);
        original.add("BTCUSD");

        assertThat(query.symbols()).containsExactly("AAPL").isUnmodifiable();
        assertThat(query.toQueryParameters()).containsEntry("symbols", "AAPL")
                .isUnmodifiable();
    }
}
