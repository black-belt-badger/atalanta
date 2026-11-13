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

class OrderDecoderTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private JsonNode fixture(String name) throws IOException {
        try (var stream = getClass().getResourceAsStream("/fixtures/" + name)) {
            if (stream == null) throw new IllegalStateException("Fixture missing: " + name);
            return mapper.readTree(stream);
        }
    }

    private Result<List<Order>> parse(String json) throws IOException {
        return OrderDecoder.decode(mapper.readTree(json));
    }

    private Order one(String fixtureName) throws IOException {
        ArrayNode root = mapper.createArrayNode().add(fixture(fixtureName));
        Result<List<Order>> decoded = OrderDecoder.decode(root);
        assertThat(decoded).isInstanceOf(Result.Ok.class);
        return ((Result.Ok<List<Order>>) decoded).value().get(0);
    }

    @Test
    void mixedOrdersIncludeAllSixProviderShapesInOriginalOrder() throws Exception {
        Result<List<Order>> result = OrderDecoder.decode(fixture("orders-mixed.json"));
        assertThat(result).isInstanceOf(Result.Ok.class);
        List<Order> orders = ((Result.Ok<List<Order>>) result).value();

        assertThat(orders).hasSize(6).isUnmodifiable();
        assertThat(orders).extracting(Order::assetClass)
                .containsExactly("us_equity", "crypto", "us_option", "us_equity", "crypto",
                        "FUTURE_CLASS");
        assertThat(orders).extracting(Order::status)
                .containsExactly("partially_filled", "filled", "filled", "filled", "new",
                        "FUTURE_STATUS");
        assertThat(orders.get(2).legs()).hasSize(2);
        assertThat(orders.get(3).legs()).hasSize(1);
    }

    @Test
    void partialEquityFillPreservesAllPricesQuantitiesTimestampsAndProviderValues() throws Exception {
        Order order = one("order-equity.json");

        assertThat(order.id())
                .isEqualTo(UUID.fromString("11111111-2222-4333-8444-555555555555"));
        assertThat(order.clientOrderId()).isEqualTo("SYNTHETIC-EQUITY-ORDER-1");
        assertThat(order.symbol()).isEqualTo("AAPL");
        assertThat(order.positionIntent()).isEqualTo("buy_to_open");
        assertThat(order.side()).isEqualTo("buy");
        assertThat(order.status()).isEqualTo("partially_filled");
        assertThat(order.orderClass()).isEqualTo("simple");
        assertThat(order.type()).isEqualTo("limit");
        assertThat(order.orderType()).isEqualTo("limit");
        assertThat(order.timeInForce()).isEqualTo("day");
        assertThat(order.extendedHours()).isFalse();
        assertThat(order.qty()).isEqualByComparingTo(
                new BigDecimal("5.123456789012345678901"));
        assertThat(order.filledQty()).isEqualByComparingTo(
                new BigDecimal("2.000000000000000001"));
        assertThat(order.filledAvgPrice()).isEqualByComparingTo(
                new BigDecimal("192.00000000000000000001"));
        assertThat(order.limitPrice()).isEqualByComparingTo(
                new BigDecimal("193.1234567890123456789"));
        assertThat(order.createdAt()).isEqualTo("2026-11-13T00:10:01.123456789Z");
        assertThat(order.filledAt()).isNull();
        assertThat(order.supplied("filled_at")).isTrue();
        assertThat(order.supplied("undefined_provider_field")).isFalse();
        assertThat(order.notional()).isNull();
        assertThat(order.supplied("notional")).isTrue();

        Map<String,Object> future = order.additionalFields();
        assertThat(future).containsKeys("provider_future_enum", "provider_future_value",
                        "provider_future_decimal")
                .isUnmodifiable();
        @SuppressWarnings("unchecked")
        Map<String,Object> nested =
                (Map<String,Object>) future.get("provider_future_value");
        assertThat(nested).isUnmodifiable();
        assertThat(nested.get("notes")).asList().isUnmodifiable();
        assertThat(future.get("provider_future_decimal")).isInstanceOf(BigDecimal.class);
        assertThat((BigDecimal) future.get("provider_future_decimal")).isEqualByComparingTo(
                new BigDecimal("0.123456789012345678901234567890123456789"));
        assertThat(order.toString()).doesNotContain("AAPL", "193.12", "11111111");
    }

    @Test
    void cryptoNotionalOrdersAcceptNullQuantityAndEmptyLegs() throws Exception {
        Order crypto = one("order-crypto.json");
        assertThat(crypto.assetClass()).isEqualTo("crypto");
        assertThat(crypto.symbol()).isEqualTo("BTCUSD");
        assertThat(crypto.status()).isEqualTo("filled");
        assertThat(crypto.type()).isEqualTo("market");
        assertThat(crypto.qty()).isNull();
        assertThat(crypto.supplied("qty")).isTrue();
        assertThat(crypto.notional()).isEqualByComparingTo(
                new BigDecimal("25.1234567890123456789012345"));
        assertThat(crypto.filledQty()).isEqualByComparingTo(
                new BigDecimal("0.000375291823456789012345"));
        assertThat(crypto.filledAt()).isEqualTo("2026-11-13T01:01:02.000000123Z");
        assertThat(crypto.legs()).isEmpty();
        assertThat(crypto.legs()).isUnmodifiable();
        assertThat(crypto.supplied("legs")).isTrue();
    }

    @Test
    void multiLegOptionsAreTypedAndAllowNullParentSide() throws Exception {
        Order multi = one("order-mleg.json");
        assertThat(multi.assetClass()).isEqualTo("us_option");
        assertThat(multi.orderClass()).isEqualTo("mleg");
        assertThat(multi.side()).isNull();
        assertThat(multi.supplied("side")).isTrue();
        assertThat(multi.legs()).hasSize(2).isUnmodifiable();
        assertThat(multi.legs()).extracting(Order::side)
                .containsExactly("buy", "sell");
        assertThat(multi.legs()).extracting(Order::positionIntent)
                .containsExactly("buy_to_open", "sell_to_open");
        assertThat(multi.legs().get(0).ratioQty())
                .isEqualByComparingTo(new BigDecimal("1.000000000000000001"));
        assertThat(multi.legs().get(1).ratioQty())
                .isEqualByComparingTo(new BigDecimal("2.000000000000000002"));
        assertThat(multi.legs().get(0).limitPrice())
                .isEqualByComparingTo(new BigDecimal("1.2500000001"));
        assertThat(multi.legs().get(1).symbol())
                .isEqualTo("AAPL261120C00210000");
        assertThat(multi.legs().get(0).legs()).isNull();
    }

    @Test
    void bracketOrderHasOptionalStopAndNestedLeg() throws Exception {
        Order bracket = one("order-bracket.json");

        assertThat(bracket.orderClass()).isEqualTo("bracket");
        assertThat(bracket.legs()).hasSize(1);
        Order stop = bracket.legs().get(0);
        assertThat(stop.type()).isEqualTo("stop");
        assertThat(stop.side()).isEqualTo("sell");
        assertThat(stop.stopPrice()).isEqualByComparingTo(new BigDecimal("400.50"));
        assertThat(stop.filledQty()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void trailingAndFutureEnumsRemainForwardCompatible() throws Exception {
        Order trailing = one("order-trailing.json");
        assertThat(trailing.type()).isEqualTo("trailing_stop");
        assertThat(trailing.trailPrice()).isEqualByComparingTo(new BigDecimal("2.5"));
        assertThat(trailing.trailPercent()).isNull();
        assertThat(trailing.supplied("trail_percent")).isTrue();
        assertThat(trailing.hwm()).isEqualByComparingTo(new BigDecimal("252.123456789"));

        Order future = one("order-future.json");
        assertThat(future.status()).isEqualTo("FUTURE_STATUS");
        assertThat(future.orderType()).isNull();
        assertThat(future.side()).isEqualTo("FUTURE_SIDE");
        assertThat(future.timeInForce()).isEqualTo("FUTURE_TIF");
        assertThat(future.additionalFields()).containsKey("future_field");
    }

    @Test
    void validEmptyArrayReturnsSuccessWithoutInventingOrders() throws Exception {
        assertThat(parse("[]")).isEqualTo(Result.ok(List.of()));
        assertThat(((Result.Ok<List<Order>>) parse("[]")).value()).isUnmodifiable();
    }

    @Test
    void invalidTopLevelShapesAndMembersBecomeTypedErrors() throws Exception {
        for (String invalid : List.of("{}", "[null]", "[1]", "[true]", "[\"nope\"]",
                "[[]]")) {
            Result<List<Order>> result = parse(invalid);
            assertThat(result).isInstanceOf(Result.Err.class);
            assertThat(((Result.Err<List<Order>>) result).error())
                    .isInstanceOf(AlpacaError.InvalidResponse.class);
        }
    }

    @Test
    void malformedOrderIdentifiersStatusesAndPricesAreRejectedSafely() throws Exception {
        for (String required : List.of("id", "status")) {
            ObjectNode root = ((ObjectNode) fixture("order-equity.json")).deepCopy();
            root.remove(required);
            assertThat(OrderDecoder.decode(mapper.createArrayNode().add(root)))
                    .isInstanceOf(Result.Err.class);
        }

        ObjectNode badId = ((ObjectNode) fixture("order-equity.json")).deepCopy();
        badId.put("id", "PRIVATE_INVALID_UUID");
        assertThat(OrderDecoder.decode(mapper.createArrayNode().add(badId)))
                .isEqualTo(Result.err(
                        new AlpacaError.InvalidResponse("Invalid order field or type")));

        for (String field : List.of("qty", "notional", "filled_qty", "limit_price",
                "filled_avg_price", "stop_price", "trail_price", "trail_percent", "hwm")) {
            ObjectNode root = ((ObjectNode) fixture("order-equity.json")).deepCopy();
            root.put(field, "PRIVATE_MALFORMED");
            Result<List<Order>> result = OrderDecoder.decode(mapper.createArrayNode().add(root));
            assertThat(result).isInstanceOf(Result.Err.class);
            assertThat(result.toString()).doesNotContain("PRIVATE_MALFORMED");
        }

        ObjectNode wrongFlag = ((ObjectNode) fixture("order-equity.json")).deepCopy();
        wrongFlag.put("extended_hours", "false");
        assertThat(OrderDecoder.decode(mapper.createArrayNode().add(wrongFlag)))
                .isInstanceOf(Result.Err.class);
    }

    @Test
    void malformedLegArraysAndUnexpectedNestedLegsAreRejected() throws Exception {
        ObjectNode malformed = ((ObjectNode) fixture("order-mleg.json")).deepCopy();
        malformed.put("legs", "not-an-array");
        assertThat(OrderDecoder.decode(mapper.createArrayNode().add(malformed)))
                .isInstanceOf(Result.Err.class);

        ObjectNode nested = ((ObjectNode) fixture("order-mleg.json")).deepCopy();
        ObjectNode grandchild = ((ObjectNode) fixture("order-equity.json")).deepCopy();
        ArrayNode grandchildren = mapper.createArrayNode().add(grandchild);
        ObjectNode child = (ObjectNode) nested.withArray("legs").get(0);
        child.set("legs", grandchildren);
        assertThat(OrderDecoder.decode(mapper.createArrayNode().add(nested)))
                .isInstanceOf(Result.Err.class);

        ObjectNode childNotAnObject = ((ObjectNode) fixture("order-mleg.json")).deepCopy();
        childNotAnObject.withArray("legs").add("not an order");
        assertThat(OrderDecoder.decode(mapper.createArrayNode().add(childNotAnObject)))
                .isInstanceOf(Result.Err.class);
    }

    @Test
    void publicOrderRecordDoesNotExposeJackson() {
        assertThat(Order.class.isRecord()).isTrue();
        assertThat(Order.class.getRecordComponents()).allSatisfy(component ->
                assertThat(component.getType().getPackageName())
                        .doesNotStartWith("com.fasterxml.jackson"));
    }
}
