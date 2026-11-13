package bbb.atalanta;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Shared internal conversion for future Alpaca JSON fields without exposing Jackson. */
final class ProviderJson {

    private ProviderJson() {}

    /** Convert provider JSON to standard Java values, preserving exact numbers and nulls. */
    static Object javaValue(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.isIntegralNumber() ? node.bigIntegerValue() : node.decimalValue();
        }
        if (node.isArray()) {
            var list = new ArrayList<Object>();
            node.forEach(value -> list.add(javaValue(value)));
            return Collections.unmodifiableList(list);
        }
        if (node.isObject()) {
            var map = new LinkedHashMap<String, Object>();
            node.properties().forEach(entry -> map.put(entry.getKey(), javaValue(entry.getValue())));
            return Collections.unmodifiableMap(map);
        }
        throw new IllegalArgumentException("Unsupported provider JSON value");
    }

    static Map<String, Object> immutableMap(Map<String, Object> source) {
        Objects.requireNonNull(source, "source");
        var copy = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> copy.put(
                Objects.requireNonNull(key, "field name"), immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            var nested = new LinkedHashMap<String, Object>();
            map.forEach((key, nestedValue) ->
                    nested.put((String) Objects.requireNonNull(key, "nested field"), nestedValue));
            return immutableMap(nested);
        }
        if (value instanceof List<?> list) {
            var copy = new ArrayList<Object>();
            list.forEach(item -> copy.add(immutableValue(item)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
