package bbb.atalanta;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static bbb.atalanta.CryptoOrderRequest.Kind.LIMIT;
import static bbb.atalanta.CryptoOrderRequest.Kind.MARKET;
import static bbb.atalanta.CryptoOrderRequest.Side.BUY;
import static bbb.atalanta.CryptoOrderRequest.Side.SELL;
import static bbb.atalanta.CryptoOrderRequest.TimeInForce.GTC;
import static bbb.atalanta.CryptoOrderRequest.TimeInForce.IOC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoOrderRequestTest {
    private static final String CLIENT_ID = "stable-crypto-order-01";

    @Test
    void createsTypedMarketQuantityForBothSidesAndCryptoTimeInForceOptions() {
        for (var side : List.of(BUY, SELL)) {
            for (var tif : List.of(GTC, IOC)) {
                var order = CryptoOrderRequest.marketQuantity("BTC/USD", side, tif,
                        new BigDecimal("0.000000001"), CLIENT_ID);
                assertThat(order.side()).isEqualTo(side);
                assertThat(order.kind()).isEqualTo(MARKET);
                assertThat(order.timeInForce()).isEqualTo(tif);
                assertThat(order.notional()).isNull();
                assertThat(order.limitPrice()).isNull();
                assertThat(order.quantity()).isEqualByComparingTo("0.000000001");
                assertThat(order.authorize().request()).isEqualTo(order);
            }
        }
    }

    @Test
    void permitsMarketNotionalButOnlyLimitQuantityAndPrice() {
        var notional = CryptoOrderRequest.marketNotional("BTC/USD", BUY, GTC,
                new BigDecimal("25.12"), CLIENT_ID);
        assertThat(notional.quantity()).isNull();
        assertThat(notional.notional()).isEqualByComparingTo("25.12");

        var limit = CryptoOrderRequest.limitQuantity("SOL/USD", SELL, IOC,
                new BigDecimal("0.123456789"), new BigDecimal("102.123456789"), CLIENT_ID);
        assertThat(limit.kind()).isEqualTo(LIMIT);
        assertThat(limit.notional()).isNull();
        assertThat(limit.quantity()).isEqualByComparingTo("0.123456789");
        assertThat(limit.limitPrice()).isEqualByComparingTo("102.123456789");

        assertThatIllegalArgumentException().isThrownBy(() ->
                new CryptoOrderRequest("BTC/USD", BUY, MARKET, GTC, null,
                        null, null, CLIENT_ID));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new CryptoOrderRequest("BTC/USD", BUY, MARKET, GTC, BigDecimal.ONE,
                        BigDecimal.ONE, null, CLIENT_ID));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new CryptoOrderRequest("BTC/USD", BUY, MARKET, GTC, BigDecimal.ONE,
                        null, BigDecimal.TEN, CLIENT_ID));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new CryptoOrderRequest("BTC/USD", BUY, LIMIT, GTC, BigDecimal.ONE,
                        null, null, CLIENT_ID));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new CryptoOrderRequest("BTC/USD", BUY, LIMIT, GTC, null,
                        BigDecimal.TEN, BigDecimal.ONE, CLIENT_ID));
    }

    @Test
    void rejectsInvalidPairsWithoutAcceptingCommaUrlPathOrWhitespace() {
        for (String symbol : List.of("", "BTCUSD", "btc/USD", "BTC/USD,ETH/USD",
                "../orders", "BTC/../../positions", "BTC/USD?execute=true",
                "BTC%2FUSD", "BTC/US D", "/USD", "BTC/", "BTC/USD\n", "BTC/USD/ETH")) {
            assertThatIllegalArgumentException().as(symbol).isThrownBy(() ->
                    CryptoOrderRequest.marketQuantity(symbol, BUY, GTC,
                            new BigDecimal("0.1"), CLIENT_ID));
        }
        assertThatIllegalArgumentException().isThrownBy(() ->
                CryptoOrderRequest.marketQuantity(null, BUY, GTC,
                        new BigDecimal("0.1"), CLIENT_ID));
    }

    @Test
    void rejectsUnsupportedOrNullSideTypeAndTimeInForce() {
        assertThatNullPointerException().isThrownBy(() ->
                CryptoOrderRequest.marketQuantity("BTC/USD", null, GTC, BigDecimal.ONE, CLIENT_ID));
        assertThatNullPointerException().isThrownBy(() ->
                CryptoOrderRequest.marketQuantity("BTC/USD", BUY, null, BigDecimal.ONE, CLIENT_ID));
        assertThatNullPointerException().isThrownBy(() ->
                new CryptoOrderRequest("BTC/USD", BUY, null, GTC, BigDecimal.ONE,
                        null, null, CLIENT_ID));
    }

    @Test
    void rejectsNonpositiveOrOverpreciseValuesWithoutFloatingPoint() {
        for (BigDecimal bad : List.of(
                BigDecimal.ZERO, BigDecimal.ONE.negate(),
                new BigDecimal("0.0000000001"),
                new BigDecimal("12345678901234567890123456789012345678901234567890123456789012345"),
                new BigDecimal("1E+1000000000"))) {
            assertThatIllegalArgumentException().as(bad.toEngineeringString())
                    .isThrownBy(() -> CryptoOrderRequest.marketQuantity("BTC/USD",
                            BUY, GTC, bad, CLIENT_ID));
        }
        assertThatIllegalArgumentException().isThrownBy(() ->
                CryptoOrderRequest.limitQuantity("BTC/USD", BUY, GTC,
                        BigDecimal.ONE, BigDecimal.ZERO, CLIENT_ID));
        assertThatIllegalArgumentException().isThrownBy(() ->
                CryptoOrderRequest.limitQuantity("BTC/USD", BUY, GTC,
                        BigDecimal.ONE, new BigDecimal("100.1234567891"), CLIENT_ID));
    }

    @Test
    void usdNotionalUsesTwoDigitsButNonUsdQuoteCanUseNine() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                CryptoOrderRequest.marketNotional("BTC/USD", BUY, GTC,
                        new BigDecimal("25.123"), CLIENT_ID));
        assertThatIllegalArgumentException().isThrownBy(() ->
                CryptoOrderRequest.marketNotional("BTC/USD", BUY, GTC,
                        new BigDecimal("25.120"), CLIENT_ID));

        var usd = CryptoOrderRequest.marketNotional("BTC/USD", BUY, GTC,
                new BigDecimal("25.12"), CLIENT_ID);
        assertThat(usd.notional()).isEqualByComparingTo("25.12");

        var btc = CryptoOrderRequest.marketNotional("ETH/BTC", BUY, IOC,
                new BigDecimal("0.123456789"), CLIENT_ID);
        assertThat(btc.notional()).isEqualByComparingTo("0.123456789");
    }

    @Test
    void requiresStableExplicitSafeClientId() {
        String maxId = "a".repeat(128);
        assertThat(CryptoOrderRequest.marketQuantity("ETH/USD", BUY, GTC,
                BigDecimal.ONE, maxId).clientOrderId()).isEqualTo(maxId);

        for (String bad : List.of("", " ", "a".repeat(129), "bad id", "../escape",
                "id\nAPCA-API-KEY-ID:SECRET", "order?confirm=true", "eu-€", "таблица")) {
            assertThatIllegalArgumentException().as(bad).isThrownBy(() ->
                    CryptoOrderRequest.marketQuantity("BTC/USD", BUY, GTC,
                            BigDecimal.ONE, bad));
        }
        assertThatIllegalArgumentException().isThrownBy(() ->
                CryptoOrderRequest.marketQuantity("BTC/USD", BUY, GTC, BigDecimal.ONE, null));
        assertThat(CryptoOrderRequest.marketQuantity("BTC/USD", BUY, GTC,
                BigDecimal.ONE, "Order-1_a:b.c").clientOrderId()).isEqualTo("Order-1_a:b.c");
    }

    @Test
    void immutableAuthorizationAndRequestNeverExposeAmountsOrIdentifiersInToString() {
        CryptoOrderRequest request = CryptoOrderRequest.marketNotional(
                "BTC/USD", BUY, IOC, new BigDecimal("12345.12"), "PRIVATE-CLIENT-ID");
        ConfirmedPaperOrder confirmed = request.authorize();
        assertThat(confirmed).isEqualTo(new ConfirmedPaperOrder(request));
        assertThat(confirmed.request()).isSameAs(request);
        assertThat(request.toString()).doesNotContain("BTC/USD", "12345", "PRIVATE-CLIENT-ID");
        assertThat(confirmed.toString()).doesNotContain("BTC/USD", "12345", "PRIVATE-CLIENT-ID");
        assertThatNullPointerException().isThrownBy(() -> new ConfirmedPaperOrder(null));
    }

    @Test
    void encoderUsesExactDecimalStringsAndNoHiddenProviderParameters() throws Exception {
        var req = CryptoOrderRequest.limitQuantity("BTC/USD", BUY, IOC,
                new BigDecimal("0.000000001"), new BigDecimal("35000.123456789"), CLIENT_ID);
        var body = new ObjectMapper().readTree(CryptoOrderEncoder.json(req));
        assertThat(body.size()).isEqualTo(7);
        assertThat(body.path("symbol").textValue()).isEqualTo("BTC/USD");
        assertThat(body.path("type").textValue()).isEqualTo("limit");
        assertThat(body.path("time_in_force").textValue()).isEqualTo("ioc");
        assertThat(body.path("side").textValue()).isEqualTo("buy");
        assertThat(body.path("client_order_id").textValue()).isEqualTo(CLIENT_ID);
        assertThat(body.path("qty").textValue()).isEqualTo("0.000000001");
        assertThat(body.path("limit_price").textValue()).isEqualTo("35000.123456789");
        assertThat(body.has("notional")).isFalse();
        assertThat(body.has("extended_hours")).isFalse();
        assertThat(body.has("order_class")).isFalse();
        assertThat(body.has("confirm")).isFalse();
        assertThat(body.has("live")).isFalse();
    }

    @Test
    void marketNotionalEncoderOmitQtyAndLimitAndNeverUsesExponentNotation() throws Exception {
        var req = CryptoOrderRequest.marketNotional("ETH/BTC", BUY, GTC,
                new BigDecimal("0.000000001"), CLIENT_ID);
        var body = new ObjectMapper().readTree(CryptoOrderEncoder.json(req));
        assertThat(body.size()).isEqualTo(6);
        assertThat(body.path("notional").textValue()).isEqualTo("0.000000001");
        assertThat(body.has("qty")).isFalse();
        assertThat(body.has("limit_price")).isFalse();
        assertThat(CryptoOrderEncoder.json(req)).doesNotContain("1E-9");
    }
}
