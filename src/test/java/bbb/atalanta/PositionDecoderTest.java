package bbb.atalanta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PositionDecoderTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private JsonNode fixture(String name) throws IOException {
        try (var stream = getClass().getResourceAsStream("/fixtures/" + name)) {
            if (stream == null) {
                throw new IllegalStateException("Missing fixture " + name);
            }
            return mapper.readTree(stream);
        }
    }

    private Result<List<Position>> parse(String raw) throws IOException {
        return PositionDecoder.decode(mapper.readTree(raw));
    }

    private Position one(String fixtureName) throws IOException {
        JsonNode single = fixture(fixtureName);
        Result<List<Position>> response =
                PositionDecoder.decode(mapper.createArrayNode().add(single));
        assertThat(response).isInstanceOf(Result.Ok.class);
        return ((Result.Ok<List<Position>>) response).value().get(0);
    }

    @Test
    void mixedResponseCoversSixClassesAndMaintainsProviderOrder() throws Exception {
        Result<List<Position>> result = PositionDecoder.decode(fixture("positions-mixed.json"));
        assertThat(result).isInstanceOf(Result.Ok.class);

        List<Position> positions = ((Result.Ok<List<Position>>) result).value();
        assertThat(positions).hasSize(6).isUnmodifiable();
        assertThat(positions).extracting(Position::assetClass)
                .containsExactly("us_equity", "crypto", "us_option", "ipo",
                        "global_equity", "future_class");
        assertThat(positions).extracting(Position::side)
                .containsExactly("long", "long", "short", "long", "long", "future_side");
    }

    @Test
    void equityValuesStayPreciseAndUnknownFieldsStayDeeplyImmutable() throws Exception {
        Position position = one("position-equity.json");
        assertThat(position.assetId())
                .isEqualTo(UUID.fromString("11111111-2222-4333-8444-555555555555"));
        assertThat(position.symbol()).isEqualTo("AAPL");
        assertThat(position.exchange()).isEqualTo("NASDAQ");
        assertThat(position.assetMarginable()).isTrue();
        assertThat(position.qty()).isEqualByComparingTo(
                new BigDecimal("5.0000000000000000001"));
        assertThat(position.qtyAvailable()).isEqualByComparingTo(
                new BigDecimal("4.9000000000000000001"));
        assertThat(position.avgEntryPrice()).isEqualByComparingTo(
                new BigDecimal("100.01234567890123456789"));
        assertThat(position.unrealizedPl()).isEqualByComparingTo(
                new BigDecimal("99.93827160549382716055"));
        assertThat(position.changeToday()).isEqualByComparingTo(
                new BigDecimal("0.008403361344537815126"));

        Map<String, Object> fields = position.additionalFields();
        assertThat(fields).containsKeys("new_provider_field", "future_null");
        assertThat(fields.get("future_null")).isNull();
        assertThat(fields).isUnmodifiable();
        assertThat(position.presentFields()).isUnmodifiable();
        assertThat(position.supplied("future_null")).isTrue();
        assertThat(position.supplied("not_yet_supplied")).isFalse();

        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) fields.get("new_provider_field");
        @SuppressWarnings("unchecked")
        List<Object> elements = (List<Object>) nested.get("positions");
        @SuppressWarnings("unchecked")
        Map<String, Object> decimal = (Map<String, Object>) elements.get(2);

        assertThat(nested).isUnmodifiable();
        assertThat(elements).containsExactly("live", null, elements.get(2)).isUnmodifiable();
        assertThat(decimal).isUnmodifiable();
        assertThat(decimal.get("quantum")).isInstanceOf(BigDecimal.class);
        assertThat((BigDecimal) decimal.get("quantum"))
                .isEqualByComparingTo(new BigDecimal("0.123456789012345678901234567890123456789"));
        assertThat(position.toString()).doesNotContain("AAPL", "99.938", "11111111");
    }

    @Test
    void cryptoKeepsVeryLongQuantityAndNegativeProfitLoss() throws Exception {
        Position crypto = one("position-crypto.json");

        assertThat(crypto.symbol()).isEqualTo("BTC/USD");
        assertThat(crypto.assetClass()).isEqualTo("crypto");
        assertThat(crypto.qty())
                .isEqualByComparingTo(new BigDecimal("0.000997498123456789012345678901"));
        assertThat(crypto.unrealizedIntradayPl())
                .isEqualByComparingTo(new BigDecimal("-0.000000000000001"));
        assertThat(crypto.unrealizedPl()).isNegative();
        assertThat(crypto.usd()).isNull();
        assertThat(crypto.supplied("usd")).isFalse();
    }

    @Test
    void optionCanHaveEmptyExchangeAndShortSideWithoutOtherPricingFields() throws Exception {
        Position option = one("position-option.json");

        assertThat(option.assetClass()).isEqualTo("us_option");
        assertThat(option.exchange()).isEmpty();
        assertThat(option.side()).isEqualTo("short");
        assertThat(option.qty()).isEqualByComparingTo(new BigDecimal("-2"));
        assertThat(option.assetMarginable()).isNull();
        assertThat(option.supplied("asset_marginable")).isTrue();
        assertThat(option.unrealizedIntradayPl()).isNull();
        assertThat(option.supplied("unrealized_intraday_pl")).isFalse();
    }

    @Test
    void ipoAllocationAcceptsDocumentedNullablePricesAndExplicitNulls() throws Exception {
        Position ipo = one("position-ipo.json");

        assertThat(ipo.assetClass()).isEqualTo("ipo");
        assertThat(ipo.exchange()).isEmpty();
        assertThat(ipo.avgEntryPrice()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(ipo.costBasis()).isEqualByComparingTo(new BigDecimal("2000"));
        assertThat(ipo.marketValue()).isNull();
        assertThat(ipo.currentPrice()).isNull();
        assertThat(ipo.changeToday()).isNull();
        assertThat(ipo.supplied("market_value")).isTrue();
        assertThat(ipo.supplied("asset_marginable")).isFalse();
    }

    @Test
    void lctPositionsHaveTypedUsdValuationAndExactFxRates() throws Exception {
        Position position = one("position-lct.json");
        assertThat(position.swapRate()).isEqualByComparingTo(new BigDecimal("146.179"));
        assertThat(position.prevSwapRate()).isEqualByComparingTo(
                new BigDecimal("146.00000000001"));
        assertThat(position.avgEntrySwapRate()).isEqualByComparingTo(
                new BigDecimal("146.1745"));

        PositionUsd usd = position.usd();
        assertThat(usd).isNotNull();
        assertThat(usd.avgEntryPrice()).isEqualByComparingTo(new BigDecimal("225.7685"));
        assertThat(usd.costBasis()).isEqualByComparingTo(
                new BigDecimal("27.0696431500022234"));
        assertThat(usd.changeToday()).isNegative();
        assertThat(usd.supplied("cost_basis")).isTrue();
        assertThat(usd.supplied("future_usd_null")).isTrue();
        assertThat(usd.additionalFields()).containsEntry("future_usd_null", null)
                .isUnmodifiable();

        @SuppressWarnings("unchecked")
        Map<String, Object> extra =
                (Map<String, Object>) usd.additionalFields().get("unknown_usd_field");
        assertThat(extra).isUnmodifiable();
        assertThat((BigDecimal) extra.get("precision"))
                .isEqualByComparingTo(new BigDecimal("0.0000000000000000000000001"));
        assertThat(usd.toString()).doesNotContain("225.7685", "27.069643");
    }

    @Test
    void futureEnumsAndZeroQuantityArePreserved() throws Exception {
        Position unknown = one("position-future.json");

        assertThat(unknown.assetClass()).isEqualTo("future_class");
        assertThat(unknown.side()).isEqualTo("future_side");
        assertThat(unknown.exchange()).isEqualTo("FUTURE_EXCHANGE");
        assertThat(unknown.qty()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(unknown.additionalFields()).containsEntry("new_wire_flag", false);
    }

    @Test
    void emptyPortfolioIsSuccessAndCollectionIsImmutable() throws Exception {
        assertThat(parse("[]")).isEqualTo(Result.ok(List.of()));
        assertThat(((Result.Ok<List<Position>>) parse("[]")).value()).isUnmodifiable();
    }

    @Test
    void wrongRootAndNonObjectListEntriesReturnTypedErrors() throws Exception {
        for (String raw : List.of("{}", "[null]", "[1]", "[true]", "[\"bad\"]", "[[]]")) {
            Result<List<Position>> result = parse(raw);
            assertThat(result).isInstanceOf(Result.Err.class);
            assertThat(((Result.Err<List<Position>>) result).error())
                    .isInstanceOf(AlpacaError.InvalidResponse.class);
        }
    }

    @Test
    void missingRequiredIdentitySideAndQuantityFieldsAreRejected() throws Exception {
        for (String missing : List.of("asset_id", "symbol", "asset_class", "side", "qty")) {
            ObjectNode node = ((ObjectNode) fixture("position-equity.json")).deepCopy();
            node.remove(missing);
            Result<List<Position>> result = PositionDecoder.decode(
                    mapper.createArrayNode().add(node));
            assertThat(result).isInstanceOf(Result.Err.class);
        }

        ObjectNode invalidId = ((ObjectNode) fixture("position-equity.json")).deepCopy();
        invalidId.put("asset_id", "SENSITIVE_ID_NOT_UUID");
        Result<List<Position>> error = PositionDecoder.decode(
                mapper.createArrayNode().add(invalidId));
        assertThat(error).isEqualTo(Result.err(
                new AlpacaError.InvalidResponse("Invalid position field or type")));
        assertThat(error.toString()).doesNotContain("SENSITIVE_ID_NOT_UUID");
    }

    @Test
    void invalidOptionalValuesAndMalformedNestedUsdAreErrors() throws Exception {
        for (String field : List.of("current_price", "cost_basis", "qty_available",
                "swap_rate", "unrealized_plpc", "change_today")) {
            ObjectNode node = ((ObjectNode) fixture("position-equity.json")).deepCopy();
            node.put(field, "PRIVATE_INVALID_DECIMAL");
            Result<List<Position>> result = PositionDecoder.decode(
                    mapper.createArrayNode().add(node));
            assertThat(result).isInstanceOf(Result.Err.class);
            assertThat(result.toString()).doesNotContain("PRIVATE_INVALID_DECIMAL");
        }
        ObjectNode wrongBoolean = ((ObjectNode) fixture("position-equity.json")).deepCopy();
        wrongBoolean.put("asset_marginable", "true");
        assertThat(PositionDecoder.decode(mapper.createArrayNode().add(wrongBoolean)))
                .isInstanceOf(Result.Err.class);

        ObjectNode wrongUsd = ((ObjectNode) fixture("position-lct.json")).deepCopy();
        wrongUsd.put("usd", "PRIVATE_PAYLOAD");
        assertThat(PositionDecoder.decode(mapper.createArrayNode().add(wrongUsd)))
                .isInstanceOf(Result.Err.class);

        ObjectNode malformedUsdValue = ((ObjectNode) fixture("position-lct.json")).deepCopy();
        ((ObjectNode) malformedUsdValue.get("usd")).put("market_value", "not-a-decimal");
        assertThat(PositionDecoder.decode(mapper.createArrayNode().add(malformedUsdValue)))
                .isInstanceOf(Result.Err.class);
    }

    @Test
    void publicRecordComponentsDoNotExposeJackson() {
        assertThat(Position.class.isRecord()).isTrue();
        assertThat(PositionUsd.class.isRecord()).isTrue();
        for (Class<?> type : List.of(Position.class, PositionUsd.class)) {
            assertThat(type.getRecordComponents())
                    .allSatisfy(component -> assertThat(component.getType().getPackageName())
                            .doesNotStartWith("com.fasterxml.jackson"));
        }
    }
}
