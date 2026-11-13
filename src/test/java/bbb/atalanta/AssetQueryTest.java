package bbb.atalanta;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AssetQueryTest {

    @Test
    void defaultsPreserveAlpacaEquityAndAllStatusDefaults() {
        AssetQuery query = AssetQuery.defaults();

        assertThat(query.assetClass()).isNull();
        assertThat(query.status()).isNull();
        assertThat(query.exchanges()).isEmpty();
        assertThat(query.attributes()).isEmpty();
        assertThat(query.toQueryParameters()).isEmpty();
        assertThat(query.toQueryParameters()).isUnmodifiable();
    }

    @Test
    void cryptoSelectionIsExplicitAndDoesNotAlterDefault() {
        assertThat(AssetQuery.crypto().toQueryParameters())
                .containsExactly(Map.entry("asset_class", "crypto"));
        assertThat(AssetQuery.defaults().toQueryParameters()).isEmpty();
    }

    @Test
    void serializesAllPublishedParametersAndCommaSeparatedLists() {
        AssetQuery query = AssetQuery.defaults()
                .withAssetClass("us_equity")
                .withStatus("active")
                .withExchanges(List.of("NASDAQ", "NYSE", "TXSE"))
                .withAttributes(List.of("has_options", "overnight_halted", "future_attribute"));

        assertThat(query.toQueryParameters()).containsExactly(
                Map.entry("status", "active"),
                Map.entry("asset_class", "us_equity"),
                Map.entry("exchange", "NASDAQ,NYSE,TXSE"),
                Map.entry("attributes", "has_options,overnight_halted,future_attribute"));
        assertThat(query.attributes()).containsExactly(
                "has_options", "overnight_halted", "future_attribute");
        assertThat(query.exchanges()).isUnmodifiable();
    }

    @Test
    void supportsAllEightCurrentlyDocumentedAttributes() {
        assertThat(AssetQuery.crypto().withAttributes(List.of(
                "ptp_no_exception", "ptp_with_exception", "ipo", "has_options",
                "options_late_close", "fractional_eh_enabled",
                "overnight_tradable", "overnight_halted")).toQueryParameters().get("attributes"))
                .isEqualTo("ptp_no_exception,ptp_with_exception,ipo,has_options,"
                        + "options_late_close,fractional_eh_enabled,overnight_tradable,overnight_halted");
    }

    @Test
    void exchangeFilteringUsesDocumentedClassRestrictions() {
        assertThat(AssetQuery.defaults().withExchanges(List.of("NASDAQ"))
                .toQueryParameters()).containsEntry("exchange", "NASDAQ");

        assertThat(AssetQuery.defaults().withAssetClass("us_index")
                .withExchanges(List.of("INDEX")).toQueryParameters())
                .containsEntry("exchange", "INDEX");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> AssetQuery.crypto().withExchanges(List.of("NASDAQ")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AssetQuery.defaults().withExchanges(List.of("INDEX")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AssetQuery.defaults().withExchanges(List.of("CRYPTO")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AssetQuery.defaults().withAssetClass("us_index")
                        .withExchanges(List.of("NYSE")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AssetQuery.defaults().withAssetClass("crypto_perp")
                        .withExchanges(List.of("INDEX")));
    }

    @Test
    void validatesPublishedStatusCodesAndUnsafeFilterTokens() {
        assertThat(AssetQuery.defaults().withStatus("inactive").status())
                .isEqualTo("inactive");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AssetQuery.defaults().withStatus("ACTIVE"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AssetQuery.defaults().withStatus("unknown"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AssetQuery.defaults().withAssetClass("crypto&status=inactive"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AssetQuery.defaults().withAttributes(List.of("ipo", "malicious,extra")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AssetQuery.defaults().withAttributes(List.of("")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AssetQuery.defaults().withExchanges(List.of("NASDAQ/NYSE")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AssetQuery(null, null, null, List.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AssetQuery.crypto().withAttributes(
                        java.util.Arrays.asList("ipo", null)));
    }

    @Test
    void defensiveCopiesProtectRecordsFromCallerMutations() {
        var values = new java.util.ArrayList<>(List.of("has_options"));
        AssetQuery query = AssetQuery.crypto().withAttributes(values);
        values.add("ipo");

        assertThat(query.attributes()).containsExactly("has_options");
        assertThat(query.attributes()).isUnmodifiable();
        assertThat(query.toQueryParameters()).containsEntry("attributes", "has_options");
    }
}
