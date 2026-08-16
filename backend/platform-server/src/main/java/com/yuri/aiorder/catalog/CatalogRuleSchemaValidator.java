package com.yuri.aiorder.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class CatalogRuleSchemaValidator {

    private static final Pattern FIELD_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,95}");
    private static final Set<String> FIELD_TYPES = Set.of(
            "string",
            "text",
            "textarea",
            "single_select",
            "color",
            "tooth",
            "number",
            "quantity",
            "boolean",
            "array",
            "multi_select",
            "object");

    public void validate(String ruleType, JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            throw badRequest("rule_schema must be an object");
        }
        if ("FORM_SCHEMA".equals(ruleType)) {
            validateFormSchema(schema);
        }
    }

    private void validateFormSchema(JsonNode schema) {
        JsonNode fields = schema.get("fields");
        if (fields == null || !fields.isArray()) {
            throw badRequest("FORM_SCHEMA fields must be an array");
        }
        Set<String> keys = new HashSet<>();
        for (JsonNode field : fields) {
            if (!field.isObject()) {
                throw badRequest("FORM_SCHEMA field must be an object");
            }
            String key = field.path("key").asText("").trim();
            if (!FIELD_KEY.matcher(key).matches()) {
                throw badRequest("FORM_SCHEMA field key must be a stable key");
            }
            if (!keys.add(key)) {
                throw badRequest("FORM_SCHEMA field key is duplicated: " + key);
            }
            String type = field.path("type").asText("").trim().toLowerCase(Locale.ROOT);
            if (!FIELD_TYPES.contains(type)) {
                throw badRequest("FORM_SCHEMA field type is unsupported: " + key);
            }
            if (field.has("label") && !field.path("label").isTextual()) {
                throw badRequest("FORM_SCHEMA field label must be text: " + key);
            }
            if (field.has("required") && !field.path("required").isBoolean()) {
                throw badRequest("FORM_SCHEMA field required must be boolean: " + key);
            }
            validateVisibleWhen(key, field.get("visible_when"));
            validateOptions(key, field.get("options"));
            validateNumericBounds(key, type, field);
            validateSizeBounds(key, field, "min_items", "minItems", "max_items", "maxItems");
            validateSizeBounds(key, field, "min_length", "minLength", "max_length", "maxLength");
        }
    }

    private void validateVisibleWhen(String key, JsonNode condition) {
        if (condition == null) {
            return;
        }
        if (!condition.isObject()
                || condition.path("field").asText("").isBlank()
                || !condition.has("equals")) {
            throw badRequest("FORM_SCHEMA visible_when is invalid: " + key);
        }
    }

    private void validateOptions(String key, JsonNode options) {
        if (options == null) {
            return;
        }
        if (!options.isArray()) {
            throw badRequest("FORM_SCHEMA options must be an array: " + key);
        }
        Set<String> values = new HashSet<>();
        for (JsonNode option : options) {
            String value;
            if (option.isTextual()) {
                value = option.asText();
            } else if (option.isObject() && option.path("value").isTextual()) {
                value = option.path("value").asText();
                if (option.has("label") && !option.path("label").isTextual()) {
                    throw badRequest("FORM_SCHEMA option label must be text: " + key);
                }
            } else {
                throw badRequest("FORM_SCHEMA option must be text or contain a text value: " + key);
            }
            if (value.isBlank() || !values.add(value)) {
                throw badRequest("FORM_SCHEMA option is blank or duplicated: " + key);
            }
        }
    }

    private void validateNumericBounds(String key, String type, JsonNode field) {
        JsonNode minimum = first(field, "minimum", "min");
        JsonNode maximum = first(field, "maximum", "max");
        if (minimum != null && !minimum.isNumber()) {
            throw badRequest("FORM_SCHEMA minimum must be numeric: " + key);
        }
        if (maximum != null && !maximum.isNumber()) {
            throw badRequest("FORM_SCHEMA maximum must be numeric: " + key);
        }
        if ("quantity".equals(type)) {
            requireNonNegativeInteger(key, "minimum", minimum);
            requireNonNegativeInteger(key, "maximum", maximum);
        }
        if (minimum != null
                && maximum != null
                && minimum.decimalValue().compareTo(maximum.decimalValue()) > 0) {
            throw badRequest("FORM_SCHEMA minimum cannot exceed maximum: " + key);
        }
    }

    private void requireNonNegativeInteger(String key, String label, JsonNode value) {
        if (value != null && (!value.isIntegralNumber() || value.longValue() < 0)) {
            throw badRequest("FORM_SCHEMA quantity " + label + " must be a non-negative integer: " + key);
        }
    }

    private void validateSizeBounds(
            String key,
            JsonNode field,
            String minimumName,
            String minimumAlias,
            String maximumName,
            String maximumAlias) {
        JsonNode minimum = first(field, minimumName, minimumAlias);
        JsonNode maximum = first(field, maximumName, maximumAlias);
        requireNonNegativeInteger(key, minimumName, minimum);
        requireNonNegativeInteger(key, maximumName, maximum);
        if (minimum != null && maximum != null) {
            BigDecimal lower = minimum.decimalValue();
            BigDecimal upper = maximum.decimalValue();
            if (lower.compareTo(upper) > 0) {
                throw badRequest("FORM_SCHEMA " + minimumName + " cannot exceed " + maximumName + ": " + key);
            }
        }
    }

    private JsonNode first(JsonNode node, String primary, String alias) {
        if (node.has(primary)) {
            return node.get(primary);
        }
        return node.get(alias);
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
