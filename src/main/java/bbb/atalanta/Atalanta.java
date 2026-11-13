package bbb.atalanta;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Read-only Java 17 client for Alpaca's paper Trading API.
 *
 * <p>Remote operations are asynchronous and return functional Results:
 * ordinary provider/network failures are typed {@link Result.Err} values.
 * Unexpected programming faults or cancellation may complete exceptionally.
 *
 * <p>Only the account endpoint is implemented at present. No order placement
 * or live-trading methods are available.
 */
public final class Atalanta {

    private final AlpacaHttpTransport transport;

    /** Create a client locked to Alpaca's paper trading endpoint. */
    public static Atalanta paper(String apiKeyId, String secretKey) {
        return new Atalanta(AlpacaHttpTransport.paper(apiKeyId, secretKey));
    }

    /** Package-private constructor for isolated contract tests. */
    Atalanta(AlpacaHttpTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    /** Fetch the complete, read-only paper-account snapshot (GET /v2/account). */
    public CompletionStage<Result<Account>> account() {
        return transport.get(List.of("v2", "account"), Map.of())
                .thenApply(json -> json.flatMap(AccountDecoder::decode));
    }

    /** Avoid accidental diagnostic printing of credentials or account data. */
    @Override
    public String toString() {
        return "Atalanta[paper]";
    }
}
