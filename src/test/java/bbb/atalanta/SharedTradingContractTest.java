package bbb.atalanta;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executes the portable contract pack against the real public Atalanta API.
 *
 * <p>Only the test harness knows how to invoke Java methods and project records
 * into observable, language-neutral facts. The portable manifest has no
 * Java/Haskell/Rust implementation type names or executable code.
 */
class SharedTradingContractTest {

    private static final URI LOOPBACK = URI.create("http://127.0.0.1:8765");
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @TestFactory
    Stream<DynamicTest> portableEndpointCases() throws IOException {
        JsonNode cases = manifest().required("cases");
        var tests = new ArrayList<DynamicTest>();
        for (JsonNode fixture : cases) {
            tests.add(DynamicTest.dynamicTest(fixture.required("id").asText(),
                    () -> verifyCase(fixture)));
        }
        return tests.stream();
    }

    @Test
    void portableFixtureHashesMatchManifestAndFiles() throws Exception {
        String checksums;
        try (InputStream source = resource("SHA256SUMS")) {
            checksums = new String(source.readAllBytes(), StandardCharsets.UTF_8);
        }
        var listed = new HashSet<String>();
        for (String line : checksums.lines().toList()) {
            if (line.isBlank()) continue;
            String[] parts = line.split("  ", 2);
            assertThat(parts).hasSize(2);
            assertThat(listed.add(parts[1])).isTrue();
            byte[] bytes;
            try (InputStream source = resource(parts[1])) {
                bytes = source.readAllBytes();
            }
            String hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
            assertThat(hash).as(parts[1]).isEqualTo(parts[0]);
        }
        assertThat(listed).contains("manifest.json", "fixtures/account-full.json",
                "fixtures/assets-mixed.json", "fixtures/positions-mixed.json",
                "fixtures/orders-mixed.json", "fixtures/order-mleg.json");
        assertThat(listed.size()).isGreaterThanOrEqualTo(25);
    }

    @Test
    void portableFormatIsVersionedCompleteAndHasUniqueIdentifiers() throws IOException {
        JsonNode manifest = manifest();
        assertThat(manifest.required("formatVersion").asInt()).isEqualTo(1);
        assertThat(manifest.required("revision").asText()).isEqualTo("1.0.0");
        assertThat(manifest.required("fixtureSet").asText()).isEqualTo("alpaca-trading-read-only");

        JsonNode cases = manifest.required("cases");
        assertThat(cases.size()).isGreaterThanOrEqualTo(20);

        var ids = new HashSet<String>();
        var endpoints = new HashSet<String>();
        for (JsonNode fixture : cases) {
            assertThat(ids.add(fixture.required("id").asText())).isTrue();
            endpoints.add(fixture.required("operation").asText());
            JsonNode request = fixture.required("request");
            assertThat(request.required("method").asText()).isEqualTo("GET");
            assertThat(request.required("path").asText()).startsWith("/v2/");
            assertThat(fixture.required("response").required("status").asInt())
                    .isBetween(100, 599);
            String body = fixture.required("response").required("body").asText();
            assertThat(body).startsWith("fixtures/").doesNotContain("..");
            assertThat(readResource(body)).isNotBlank();
        }
        assertThat(endpoints).containsExactlyInAnyOrder(
                "account", "assets", "positions", "orders", "order_by_id");
    }

    private void verifyCase(JsonNode fixture) throws IOException {
        String operation = fixture.required("operation").asText();
        JsonNode args = fixture.required("input");
        JsonNode requestSpec = fixture.required("request");
        JsonNode responseSpec = fixture.required("response");

        AtomicInteger calls = new AtomicInteger();
        AtomicReference<HttpRequest> request = new AtomicReference<>();
        CompletableFuture<HttpResponse<String>> pending = new CompletableFuture<>();
        var client = new Atalanta(new AlpacaHttpTransport(
                LOOPBACK, "SYNTHETIC-TEST-KEY", "SYNTHETIC-TEST-SECRET", Duration.ofSeconds(5),
                (req, bodyHandler) -> {
                    calls.incrementAndGet();
                    request.set(req);
                    return pending;
                }));

        CompletionStage<? extends Result<?>> stage = invoke(client, operation, args);
        assertThat(stage.toCompletableFuture()).isNotDone();
        assertThat(calls).hasValue(1);

        HttpRequest actualRequest = request.get();
        assertThat(actualRequest.method()).isEqualTo(requestSpec.required("method").asText());
        assertThat(actualRequest.uri().getRawPath())
                .isEqualTo(requestSpec.required("path").asText());
        JsonNode query = requestSpec.required("rawQuery");
        assertThat(actualRequest.uri().getRawQuery())
                .isEqualTo(query.isNull() ? null : query.asText());
        assertThat(actualRequest.headers().firstValue("APCA-API-KEY-ID"))
                .contains("SYNTHETIC-TEST-KEY");
        assertThat(actualRequest.headers().firstValue("APCA-API-SECRET-KEY"))
                .contains("SYNTHETIC-TEST-SECRET");

        pending.complete(new Reply(
                responseSpec.required("status").asInt(),
                readResource(responseSpec.required("body").asText())));

        Result<?> result = stage.toCompletableFuture().join();
        ObjectNode observed = normalize(result, operation);
        JsonNode expected = fixture.required("expected");

        assertThat(observed.required("outcome").asText())
                .isEqualTo(expected.required("outcome").asText());

        if (expected.has("error")) {
            assertThat(observed.required("error").asText())
                    .isEqualTo(expected.required("error").asText());
        }
        if (expected.has("count")) {
            assertThat(observed.required("items").size())
                    .isEqualTo(expected.required("count").asInt());
        }

        for (JsonNode fact : expected.path("checks")) {
            String pointer = fact.required("pointer").asText();
            JsonNode actual = observed.at(pointer);
            JsonNode value = fact.required("value");
            assertThat(actual.isMissingNode())
                    .as("Missing observation %s in portable case %s", pointer, fixture.get("id"))
                    .isFalse();

            if (value.isNull()) {
                assertThat(actual.isNull()).as(pointer).isTrue();
            } else if (value.isBoolean()) {
                assertThat(actual.isBoolean()).as(pointer).isTrue();
                assertThat(actual.booleanValue()).as(pointer)
                        .isEqualTo(value.booleanValue());
            } else if (value.isIntegralNumber()) {
                assertThat(actual.isIntegralNumber()).as(pointer).isTrue();
                assertThat(actual.bigIntegerValue()).as(pointer)
                        .isEqualTo(value.bigIntegerValue());
            } else {
                assertThat(actual.asText()).as(pointer).isEqualTo(value.asText());
            }
        }
    }

    private static CompletionStage<? extends Result<?>> invoke(
            Atalanta client, String operation, JsonNode args) {
        return switch (operation) {
            case "account" -> client.account();
            case "assets" -> client.assets(assetQuery(args));
            case "positions" -> client.positions();
            case "orders" -> client.orders(orderQuery(args));
            case "order_by_id" -> {
                UUID id = UUID.fromString(args.required("id").asText());
                yield args.has("nested")
                        ? client.order(id, args.required("nested").booleanValue())
                        : client.order(id);
            }
            default -> throw new IllegalArgumentException("Unsupported fixture operation");
        };
    }

    private static AssetQuery assetQuery(JsonNode args) {
        var q = AssetQuery.defaults();
        if (args.has("asset_class")) q = q.withAssetClass(args.get("asset_class").asText());
        if (args.has("status")) q = q.withStatus(args.get("status").asText());
        if (args.has("exchanges")) q = q.withExchanges(strings(args.get("exchanges")));
        if (args.has("attributes")) q = q.withAttributes(strings(args.get("attributes")));
        return q;
    }

    private static OrderQuery orderQuery(JsonNode args) {
        var q = OrderQuery.defaults();
        if (args.has("status")) q = q.withStatus(args.get("status").asText());
        if (args.has("limit")) q = q.withLimit(args.get("limit").intValue());
        if (args.has("after")) q = q.withAfter(Instant.parse(args.get("after").asText()));
        if (args.has("until")) q = q.withUntil(Instant.parse(args.get("until").asText()));
        if (args.has("direction")) q = q.withDirection(args.get("direction").asText());
        if (args.has("nested")) q = q.withNested(args.get("nested").booleanValue());
        if (args.has("symbols")) q = q.withSymbols(strings(args.get("symbols")));
        if (args.has("side")) q = q.withSide(args.get("side").asText());
        if (args.has("asset_classes"))
            q = q.withAssetClasses(strings(args.get("asset_classes")));
        if (args.has("before_order_id"))
            q = q.withBeforeOrderId(UUID.fromString(args.get("before_order_id").asText()));
        if (args.has("after_order_id"))
            q = q.withAfterOrderId(UUID.fromString(args.get("after_order_id").asText()));
        return q;
    }

    private static List<String> strings(JsonNode array) {
        var result = new ArrayList<String>();
        for (JsonNode item : array) result.add(item.asText());
        return List.copyOf(result);
    }

    private ObjectNode normalize(Result<?> result, String operation) {
        var node = mapper.createObjectNode();
        if (result instanceof Result.Err<?> err) {
            node.put("outcome", "error");
            node.put("error", err.error().getClass().getSimpleName());
            return node;
        }

        Object value = ((Result.Ok<?>) result).value();
        if (operation.equals("order_by_id")) {
            @SuppressWarnings("unchecked")
            Optional<Order> found = (Optional<Order>) value;
            if (found.isEmpty()) {
                node.put("outcome", "absent");
                return node;
            }
            node.put("outcome", "ok");
            node.set("value", order(found.orElseThrow()));
            return node;
        }

        node.put("outcome", "ok");
        if (value instanceof Account account) {
            node.set("value", account(account));
        } else if (value instanceof List<?> list) {
            var items = node.putArray("items");
            for (Object item : list) {
                if (item instanceof Asset asset) items.add(asset(asset));
                else if (item instanceof Position position) items.add(position(position));
                else if (item instanceof Order order) items.add(order(order));
                else throw new IllegalArgumentException("Unsupported contract value type");
            }
        } else {
            throw new IllegalArgumentException("Unsupported contract value type");
        }
        return node;
    }

    private ObjectNode account(Account value) {
        var node = mapper.createObjectNode();
        node.put("status", value.status());
        node.put("crypto_status", value.cryptoStatus());
        decimal(node, "cash", value.cash());
        decimal(node, "equity", value.equity());
        var details = node.putObject("details");
        integer(details, "options_approved_level", value.details().optionsApprovedLevel());
        integer(details, "daytrade_count", value.details().daytradeCount());
        optionalBoolean(details, "pattern_day_trader", value.details().patternDayTrader());
        decimal(details, "daytrading_buying_power", value.details().daytradingBuyingPower());
        var supplied = node.putObject("supplied");
        for (String field : List.of("cash", "equity", "daytrading_buying_power"))
            supplied.put(field, value.details().supplied(field));
        node.set("extensions", mapper.valueToTree(value.details().additionalFields()));
        return node;
    }

    private ObjectNode asset(Asset value) {
        var node = mapper.createObjectNode();
        node.put("symbol", value.symbol());
        node.put("asset_class", value.assetClass());
        node.put("status", value.status());
        node.put("borrow_status", value.borrowStatus());
        decimal(node, "min_order_size", value.minOrderSize());
        decimal(node, "min_trade_increment", value.minTradeIncrement());
        var supplied = node.putObject("supplied");
        supplied.put("cusip", value.supplied("cusip"));
        node.set("extensions", mapper.valueToTree(value.additionalFields()));
        return node;
    }

    private ObjectNode position(Position value) {
        var node = mapper.createObjectNode();
        node.put("symbol", value.symbol());
        node.put("asset_class", value.assetClass());
        node.put("side", value.side());
        decimal(node, "qty", value.qty());
        decimal(node, "current_price", value.currentPrice());
        decimal(node, "unrealized_pl", value.unrealizedPl());
        decimal(node, "swap_rate", value.swapRate());
        var supplied = node.putObject("supplied");
        supplied.put("current_price", value.supplied("current_price"));
        if (value.usd() != null) {
            var usd = node.putObject("usd");
            decimal(usd, "market_value", value.usd().marketValue());
        } else {
            node.putNull("usd");
        }
        return node;
    }

    private ObjectNode order(Order value) {
        var node = mapper.createObjectNode();
        node.put("id", value.id().toString());
        node.put("symbol", value.symbol());
        node.put("side", value.side());
        node.put("status", value.status());
        decimal(node, "qty", value.qty());
        decimal(node, "ratio_qty", value.ratioQty());
        decimal(node, "notional", value.notional());
        decimal(node, "filled_qty", value.filledQty());
        if (value.legs() == null) {
            node.putNull("legs");
        } else {
            var legs = node.putArray("legs");
            for (Order leg : value.legs()) legs.add(order(leg));
        }
        return node;
    }

    private static void decimal(ObjectNode out, String field, BigDecimal value) {
        if (value == null) out.putNull(field);
        else out.put(field, value.toPlainString());
    }

    private static void integer(ObjectNode out, String field, Integer value) {
        if (value == null) out.putNull(field);
        else out.put(field, value);
    }

    private static void optionalBoolean(ObjectNode out, String field, Boolean value) {
        if (value == null) out.putNull(field);
        else out.put(field, value);
    }

    private JsonNode manifest() throws IOException {
        try (InputStream source = resource("manifest.json")) {
            return mapper.readTree(source);
        }
    }

    private String readResource(String name) throws IOException {
        if (!name.startsWith("fixtures/") || name.contains("..") || name.contains("\\")) {
            throw new IllegalArgumentException("Invalid fixture path");
        }
        try (InputStream source = resource(name)) {
            return new String(source.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private InputStream resource(String name) {
        InputStream source = getClass().getResourceAsStream("/" + name);
        if (source == null) throw new IllegalArgumentException("Missing contract resource " + name);
        return source;
    }

    private record Reply(int statusCode, String body) implements HttpResponse<String> {
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (n,v) -> true); }
        @Override public URI uri() { return LOOPBACK; }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
    }
}
