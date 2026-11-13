package bbb.atalanta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Private, exact-decimal crypto order JSON encoding with a fixed field allowlist. */
final class CryptoOrderEncoder {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CryptoOrderEncoder() {}

    static String json(CryptoOrderRequest order) {
        ObjectNode object = MAPPER.createObjectNode();
        object.put("symbol", order.symbol());
        object.put("side", order.side().wire());
        object.put("type", order.kind().wire());
        object.put("time_in_force", order.timeInForce().wire());
        object.put("client_order_id", order.clientOrderId());
        if (order.quantity() != null) object.put("qty", order.quantity().toPlainString());
        if (order.notional() != null) object.put("notional", order.notional().toPlainString());
        if (order.limitPrice() != null) object.put("limit_price", order.limitPrice().toPlainString());
        return object.toString();
    }
}
