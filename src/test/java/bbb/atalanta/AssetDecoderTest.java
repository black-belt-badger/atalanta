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

class AssetDecoderTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private JsonNode fixture(String name) throws IOException {
        try (var stream = getClass().getResourceAsStream("/fixtures/" + name)) {
            if (stream == null) {
                throw new IllegalStateException("Missing fixture: " + name);
            }
            return mapper.readTree(stream);
        }
    }

    private Result<List<Asset>> parse(String json) throws IOException {
        return AssetDecoder.decode(mapper.readTree(json));
    }

    private Asset fromFixture(String name) throws Exception {
        ArrayNode array = mapper.createArrayNode();
        array.add(fixture(name));
        Result<List<Asset>> result = AssetDecoder.decode(array);
        assertThat(result).isInstanceOf(Result.Ok.class);
        return ((Result.Ok<List<Asset>>) result).value().get(0);
    }

    @Test
    void decodesMultipleAssetClassesPreservingOrderAndNewEnumValues() throws Exception {
        Result<List<Asset>> result = AssetDecoder.decode(fixture("assets-mixed.json"));
        assertThat(result).isInstanceOf(Result.Ok.class);
        List<Asset> assets = ((Result.Ok<List<Asset>>) result).value();

        assertThat(assets).hasSize(4).isUnmodifiable();
        assertThat(assets).extracting(Asset::assetClass)
                .containsExactly("us_equity", "crypto", "us_index", "crypto_perp");
        assertThat(assets).extracting(Asset::exchange)
                .containsExactly("NASDAQ", "CRYPTO", "INDEX", "ASCX");
        assertThat(assets.get(2).status()).isEqualTo("FUTURE_STATUS");
        assertThat(assets.get(3).symbol()).isEqualTo("ETH/USD");
    }

    @Test
    void decodesFullEquityIncludingNewBorrowStatusDeprecatedAndUnknownFields() throws Exception {
        Asset asset = fromFixture("asset-equity.json");

        assertThat(asset.id())
                .isEqualTo(UUID.fromString("11111111-2222-4333-8444-555555555555"));
        assertThat(asset.name()).isEqualTo("Example Equity");
        assertThat(asset.assetClass()).isEqualTo("us_equity");
        assertThat(asset.tradable()).isTrue();
        assertThat(asset.marginable()).isTrue();
        assertThat(asset.shortable()).isTrue();
        assertThat(asset.fractionable()).isTrue();
        assertThat(asset.easyToBorrow()).isTrue();
        assertThat(asset.borrowStatus()).isEqualTo("easy_to_borrow");
        assertThat(asset.cusip()).isNull();
        assertThat(asset.supplied("cusip")).isTrue();
        assertThat(asset.attributes()).containsExactly(
                "has_options", "overnight_tradable", "future_attribute");
        assertThat(asset.attributes()).isUnmodifiable();
        assertThat(asset.marginRequirementShort())
                .isEqualByComparingTo(new BigDecimal("30.00000000000000000001"));
        assertThat(asset.maintenanceMarginRequirement())
                .isEqualByComparingTo(new BigDecimal("29.12500000000000000000001"));

        @SuppressWarnings("unchecked")
        Map<String, Object> future = (Map<String, Object>) asset.additionalFields().get("future");
        assertThat(future.get("decimals")).isInstanceOf(BigDecimal.class);
        assertThat((BigDecimal) future.get("decimals"))
                .isEqualByComparingTo(new BigDecimal("0.12345678901234567890123456789012345"));
        assertThat(future.get("nested")).isInstanceOf(List.class);
        assertThat(future).isUnmodifiable();
        assertThat(future.get("nested")).asList().isUnmodifiable();
        assertThat(asset.additionalFields()).containsEntry("future_null", null).isUnmodifiable();
        assertThat(asset.presentFields()).isUnmodifiable();
        assertThat(asset.additionalFields().values()).noneMatch(item -> item instanceof JsonNode);
    }

    @Test
    void cryptoHasPreciseTradeAndPriceIncrements() throws Exception {
        Asset asset = fromFixture("asset-crypto.json");
        assertThat(asset.symbol()).isEqualTo("BTC/USD");
        assertThat(asset.exchange()).isEqualTo("CRYPTO");
        assertThat(asset.marginable()).isFalse();
        assertThat(asset.minOrderSize())
                .isEqualByComparingTo(new BigDecimal("0.000000010000000001"));
        assertThat(asset.minTradeIncrement())
                .isEqualByComparingTo(new BigDecimal("0.00000000000001"));
        assertThat(asset.priceIncrement()).isEqualByComparingTo(new BigDecimal("0.01"));
        assertThat(asset.attributes()).isEmpty();
        assertThat(asset.supplied("borrow_status")).isFalse();
        assertThat(asset.borrowStatus()).isNull();
    }

    @Test
    void missingNullEmptyAndZeroAreDifferent() throws Exception {
        Asset index = fromFixture("asset-index.json");
        assertThat(index.attributes()).isNull();
        assertThat(index.supplied("attributes")).isTrue();
        assertThat(index.borrowStatus()).isNull();
        assertThat(index.supplied("borrow_status")).isTrue();
        assertThat(index.supplied("cusip")).isFalse();

        ObjectNode zero = (ObjectNode) fixture("asset-crypto.json");
        zero.put("min_order_size", "0.0000000000");
        ArrayNode array = mapper.createArrayNode().add(zero);
        Asset result = ((Result.Ok<List<Asset>>) AssetDecoder.decode(array)).value().get(0);
        assertThat(result.minOrderSize()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.supplied("min_order_size")).isTrue();
    }

    @Test
    void emptyArrayIsAValidReadOnlyResult() throws Exception {
        assertThat(parse("[]")).isEqualTo(Result.ok(List.of()));
    }

    @Test
    void rejectsWrongRootAndNonObjectEntries() throws Exception {
        for (String json : List.of("{}", "[null]", "[1]", "[true]", "[\"a\"]", "[[]]")) {
            Result<List<Asset>> result = parse(json);
            assertThat(result).isInstanceOf(Result.Err.class);
            assertThat(((Result.Err<List<Asset>>) result).error())
                    .isInstanceOf(AlpacaError.InvalidResponse.class);
        }
    }

    @Test
    void validatesRequiredFieldsWithoutLeakingRawJson() throws Exception {
        for (String field : List.of(
                "id", "class", "exchange", "symbol", "name", "status",
                "tradable", "marginable", "shortable", "fractionable")) {
            ObjectNode root = ((ObjectNode) fixture("asset-crypto.json")).deepCopy();
            root.remove(field);
            Result<List<Asset>> result = AssetDecoder.decode(mapper.createArrayNode().add(root));
            assertThat(result).isInstanceOf(Result.Err.class);
            assertThat(result.toString()).doesNotContain("BTC/USD", "aaaaaaaa");
        }

        ObjectNode root = ((ObjectNode) fixture("asset-crypto.json")).deepCopy();
        root.put("id", "THIS_IS_NOT_A_UUID_PRIVATE_VALUE");
        assertThat(AssetDecoder.decode(mapper.createArrayNode().add(root)))
                .isInstanceOf(Result.Err.class);
    }

    @Test
    void rejectsMalformedOptionalValuesAndUnknownNestedAttributeTypes() throws Exception {
        String[] badFields = {
                "min_order_size", "margin_requirement_long", "price_increment",
                "maintenance_margin_requirement"
        };
        for (String field : badFields) {
            ObjectNode root = ((ObjectNode) fixture("asset-crypto.json")).deepCopy();
            root.put(field, "not-a-number-SECRET");
            assertThat(AssetDecoder.decode(mapper.createArrayNode().add(root)))
                    .isInstanceOf(Result.Err.class);
        }

        ObjectNode invalidBoolean = ((ObjectNode) fixture("asset-equity.json")).deepCopy();
        invalidBoolean.put("easy_to_borrow", "true");
        assertThat(AssetDecoder.decode(mapper.createArrayNode().add(invalidBoolean)))
                .isInstanceOf(Result.Err.class);

        ObjectNode invalidAttributes = ((ObjectNode) fixture("asset-equity.json")).deepCopy();
        invalidAttributes.putArray("attributes").add("has_options").addObject().put("bad", true);
        assertThat(AssetDecoder.decode(mapper.createArrayNode().add(invalidAttributes)))
                .isInstanceOf(Result.Err.class);
    }

    @Test
    void publicAssetRecordDoesNotExposeJacksonInAnyField() {
        assertThat(Asset.class.isRecord()).isTrue();
        assertThat(Asset.class.getRecordComponents())
                .allSatisfy(component -> assertThat(component.getType().getPackageName())
                        .doesNotStartWith("com.fasterxml.jackson"));
    }
}
