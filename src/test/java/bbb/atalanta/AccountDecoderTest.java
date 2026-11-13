package bbb.atalanta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountDecoderTest {

    private final ObjectMapper json = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private JsonNode fixture(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/fixtures/" + name)) {
            if (input == null) {
                throw new IllegalStateException("Missing fixture " + name);
            }
            return json.readTree(input);
        }
    }

    private Result<Account> parse(String text) throws IOException {
        return AccountDecoder.decode(json.readTree(text));
    }

    @Test
    void fullAccountHasTypedExactValuesAndNoJsonTypesInPublicModel() throws Exception {
        Result<Account> result = AccountDecoder.decode(fixture("account-full.json"));
        assertThat(result).isInstanceOf(Result.Ok.class);

        Account a = ((Result.Ok<Account>) result).value();
        assertThat(a.id()).isEqualTo(UUID.fromString("11111111-2222-4333-8444-555555555555"));
        assertThat(a.accountNumber()).isEqualTo("PAPER-EXAMPLE-NOT-REAL");
        assertThat(a.status()).isEqualTo("ACTIVE");
        assertThat(a.cryptoStatus()).isEqualTo("APPROVED");
        assertThat(a.cash()).isEqualByComparingTo(
                new BigDecimal("123456789.123456789012345678901234567890"));
        assertThat(a.buyingPower()).isEqualByComparingTo(
                new BigDecimal("246913578.246913578024691357802469135780"));
        assertThat(a.tradingBlocked()).isFalse();
        assertThat(a.details().optionsApprovedLevel()).isEqualTo(3);
        assertThat(a.details().optionsTradingLevel()).isEqualTo(2);
        assertThat(a.details().cryptoTier()).isEqualTo(1);
        assertThat(a.details().shortingEnabled()).isTrue();
        assertThat(a.details().maintenanceMargin()).isEqualByComparingTo(new BigDecimal("60"));
        assertThat(a.details().nonMarginableBuyingPower())
                .isEqualByComparingTo(new BigDecimal("1000.1000000000000000000001"));
        assertThat(a.details().createdAt()).isEqualTo("2026-01-05T12:00:00.123456789Z");

        assertThat(a.details().supplied("options_approved_level")).isTrue();
        assertThat(a.details().supplied("daytrading_buying_power")).isTrue();
        assertThat(a.details().daytradingBuyingPower()).isNull();
        assertThat(a.details().supplied("unprovided_field")).isFalse();

        assertThat(a.toString()).doesNotContain("PAPER-EXAMPLE", "123456789", "11111111");
        assertThat(a.details().toString()).doesNotContain("PAPER-EXAMPLE");
    }

    @Test
    void unknownNestedFieldsAreImmutableAndPreserveNumbersWithoutJackson() throws Exception {
        Account account = ((Result.Ok<Account>) AccountDecoder.decode(fixture("account-full.json"))).value();
        Map<String, Object> unknown = account.details().additionalFields();
        assertThat(unknown).containsKeys("admin_configurations", "user_configurations", "novel_balance");
        assertThat(unknown.get("user_configurations")).isNull();
        assertThat(unknown.get("novel_balance")).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> novel = (Map<String, Object>) unknown.get("novel_balance");
        assertThat(novel.get("precise")).isInstanceOf(BigDecimal.class);
        assertThat((BigDecimal) novel.get("precise"))
                .isEqualByComparingTo(new BigDecimal("123456789.1234567890123456789"));

        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) novel.get("items");
        assertThat(list).containsExactly(null, true, "future");
        assertThat(unknown).isUnmodifiable();
        assertThat(novel).isUnmodifiable();
        assertThat(list).isUnmodifiable();
        assertThat(account.details().presentFields()).isUnmodifiable();
        assertThat(unknown.values()).noneMatch(value -> value instanceof JsonNode);
    }

    @Test
    void minimalResponsePreservesFutureStatusAndDistinguishesMissingNullAndZero() throws Exception {
        Account a = ((Result.Ok<Account>) AccountDecoder.decode(fixture("account-minimal.json"))).value();
        assertThat(a.status()).isEqualTo("NEW_FUTURE_STATUS");
        assertThat(a.cryptoStatus()).isNull();
        assertThat(a.cash()).isNull();
        assertThat(a.details().supplied("cash")).isTrue();
        assertThat(a.details().supplied("equity")).isFalse();
        assertThat(a.details().supplied("trading_blocked")).isTrue();

        Account zero = ((Result.Ok<Account>) parse("""
                {"id":"11111111-2222-4333-8444-555555555555","status":"ACTIVE",
                 "cash":"0.00000000000000000001","trading_blocked":false}
                """)).value();
        assertThat(zero.cash()).isEqualByComparingTo(new BigDecimal("0.00000000000000000001"));
        assertThat(zero.details().supplied("cash")).isTrue();
    }

    @Test
    void deprecatedLegacyPdtFieldsRemainOptionalAndSupported() throws Exception {
        Account a = ((Result.Ok<Account>) AccountDecoder.decode(fixture("account-legacy.json"))).value();
        assertThat(a.status()).isEqualTo("PAPER_ONLY");
        assertThat(a.details().patternDayTrader()).isTrue();
        assertThat(a.details().daytradeCount()).isEqualTo(3);
        assertThat(a.details().lastDaytradeCount()).isEqualTo(2);
        assertThat(a.details().daytradingBuyingPower())
                .isEqualByComparingTo(new BigDecimal("100.000000000001"));
    }

    @Test
    void rejectMalformedRootAndMissingRequiredFields() throws Exception {
        for (String payload : List.of(
                "[]", "null", "\"invalid\"", "42", "{}",
                "{\"status\":\"ACTIVE\"}",
                "{\"id\":\"11111111-2222-4333-8444-555555555555\"}",
                "{\"id\":\"garbage\",\"status\":\"ACTIVE\"}",
                "{\"id\":\"11111111-2222-4333-8444-555555555555\",\"status\":7}",
                "{\"id\":\"11111111-2222-4333-8444-555555555555\",\"status\":\"\"}"
        )) {
            Result<Account> response = parse(payload);
            assertThat(response).isInstanceOf(Result.Err.class);
            assertThat(((Result.Err<Account>) response).error())
                    .isInstanceOf(AlpacaError.InvalidResponse.class);
        }
    }

    @Test
    void wrongTypeInOptionalFieldsIsAnInvalidResponse() throws Exception {
        for (String field : List.of(
                "\"cash\":{}", "\"cash\":true", "\"cash\":\"not-number\"",
                "\"options_trading_level\":3.5", "\"options_approved_level\":\"3\"",
                "\"trading_blocked\":\"false\"", "\"created_at\":123"
        )) {
            Result<Account> result = parse(
                    "{\"id\":\"11111111-2222-4333-8444-555555555555\",\"status\":\"ACTIVE\","
                            + field + "}");
            assertThat(result).isInstanceOf(Result.Err.class);
        }
    }

    @Test
    void publicRecordsAndApiHaveNoJacksonComponents() throws Exception {
        assertThat(Account.class.isRecord()).isTrue();
        assertThat(AccountDetails.class.isRecord()).isTrue();
        assertThat(Account.class.getRecordComponents())
                .allSatisfy(component -> assertThat(component.getType().getPackageName())
                        .doesNotStartWith("com.fasterxml.jackson"));
        assertThat(AccountDetails.class.getRecordComponents())
                .allSatisfy(component -> assertThat(component.getType().getPackageName())
                        .doesNotStartWith("com.fasterxml.jackson"));
    }
}
