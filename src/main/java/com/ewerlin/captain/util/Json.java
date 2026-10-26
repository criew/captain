package com.ewerlin.captain.util;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** JSON-Hilfen: ein gemeinsamer Mapper und nachsichtige Feldzugriffe wie ``dict.get`` in Python. */
public final class Json {

    public static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Für Dateien: sortierte Schlüssel, eingerückt (wie ``json.dump(indent=2, sort_keys=True)``). */
    public static final JsonMapper PRETTY = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private Json() {
    }

    public static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    public static JsonNode parse(String text) {
        return MAPPER.readTree(text);
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static JsonNode tree(Object value) {
        return MAPPER.valueToTree(value);
    }

    /** Textwert eines Feldes; ``""`` für fehlend, ``null`` oder Nicht-Skalare. */
    public static String str(JsonNode node, String field) {
        return node == null ? "" : scalar(node.get(field));
    }

    /** Skalar als Text (Boolean → ``true``/``false``, Zahl → Ziffern), sonst ``""``. */
    public static String scalar(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode() || value.isContainer()) {
            return "";
        }
        return value.asString();
    }

    /** Ganzzahl eines Feldes; 0 für fehlend oder nicht numerisch. */
    public static long lng(JsonNode node, String field) {
        if (node == null) {
            return 0;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return 0;
        }
        if (value.isNumber()) {
            return value.asLong();
        }
        if (value.isString()) {
            try {
                return Long.parseLong(value.asString().strip());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /** Wahrheitswert wie in Python: fehlend/leer/0/false → falsch. */
    public static boolean truthy(JsonNode node, String field) {
        if (node == null) {
            return false;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return false;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isNumber()) {
            return value.asDouble() != 0;
        }
        if (value.isString()) {
            return !value.asString().isEmpty();
        }
        return !value.isEmpty();
    }

    /** Elemente eines Arrays (leer, wenn kein Array). */
    public static List<JsonNode> list(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(out::add);
        }
        return out;
    }

    /** Werte eines Objekts in Einfügereihenfolge (leer, wenn kein Objekt). */
    public static List<JsonNode> values(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        if (node != null && node.isObject()) {
            node.forEach(out::add);
        }
        return out;
    }
}
