package com.yuerong.diligence.common.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuerong.diligence.common.Fault;
import java.util.*;

/** Validates a caller-supplied schema; never loads a business contract. */
public class JsonSchemaValidator {
  private final JsonNode schema;
  public JsonSchemaValidator(JsonNode schema) { this.schema = schema; }

  private boolean matches(JsonNode s, JsonNode v) {
    try {
      validate(s, v, "");
      return true;
    } catch (Fault e) {
      return false;
    }
  }

  private void fail(String path) {
    throw new Violation(path, path);
  }

  /** Known schema fields only; unexpected caller-supplied keys must not enter diagnostic logs. */
  public static final class Violation extends Fault {
    public final String schemaPath;
    private Violation(String path, String schemaPath) {
      super("INVALID_ARGUMENT", "参数或结果不符合契约：" + path);
      this.schemaPath = schemaPath;
    }
  }

  private boolean type(String t, JsonNode v) {
    if (v == null) return false;
    switch (t) {
      case "object":
        return v.isObject();
      case "array":
        return v.isArray();
      case "string":
        return v.isTextual();
      case "integer":
        return v.isIntegralNumber();
      case "number":
        return v.isNumber();
      case "boolean":
        return v.isBoolean();
      case "null":
        return v.isNull();
      default:
        throw new IllegalStateException("Unsupported schema type");
    }
  }

  public void validate(JsonNode s, JsonNode v, String path) {
    if (s.isMissingNode()) throw new IllegalStateException("Missing schema");
    if (s.has("$ref")) {
      String r = s.get("$ref").asText();
      if (!r.startsWith("#/$defs/")) throw new IllegalStateException("External schema reference");
      validate(schema.at(r.substring(1)), v, path);
    }
    if (s.has("type")) {
      JsonNode t = s.get("type");
      boolean ok = t.isArray() ? false : type(t.asText(), v);
      if (t.isArray()) for (JsonNode x : t) ok |= type(x.asText(), v);
      if (!ok) fail(path);
    }
    if (s.has("const") && !s.get("const").equals(v)) fail(path);
    if (s.has("enum")) {
      boolean ok = false;
      for (JsonNode x : s.get("enum")) ok |= x.equals(v);
      if (!ok) fail(path);
    }
    if (s.has("allOf")) for (JsonNode x : s.get("allOf")) validate(x, v, path);
    if (s.has("anyOf")) {
      boolean ok = false;
      for (JsonNode x : s.get("anyOf")) ok |= matches(x, v);
      if (!ok) fail(path);
    }
    if (s.has("not") && matches(s.get("not"), v)) fail(path);
    if (s.has("if")) {
      JsonNode branch = s.get(matches(s.get("if"), v) ? "then" : "else");
      if (branch != null) validate(branch, v, path);
    }
    if (v == null) return;
    if (v.isObject()) {
      for (JsonNode key : s.path("required"))
        if (!v.has(key.asText())) fail(path + "." + key.asText());
      Iterator<String> keys = v.fieldNames();
      while (keys.hasNext()) {
        String key = keys.next();
        JsonNode prop = s.path("properties").get(key);
        if (prop != null) validate(prop, v.get(key), path + "." + key);
        else if (s.has("additionalProperties") && !s.get("additionalProperties").asBoolean())
          throw new Violation(path + "." + key, path + ".<unexpected>");
      }
    }
    if (v.isArray()) {
      if (v.size() < s.path("minItems").asInt(0)
          || v.size() > s.path("maxItems").asInt(Integer.MAX_VALUE)) fail(path);
      Set<JsonNode> seen = new HashSet<>();
      for (JsonNode x : v) {
        if (s.path("uniqueItems").asBoolean() && !seen.add(x)) fail(path);
        if (s.has("items")) validate(s.get("items"), x, path + "[]");
      }
    }
    if (v.isTextual()) {
      String t = v.asText();
      if (t.length() < s.path("minLength").asInt(0)
          || t.length() > s.path("maxLength").asInt(Integer.MAX_VALUE)) fail(path);
      if (s.has("pattern")
          && !java.util.regex.Pattern.compile(s.get("pattern").asText()).matcher(t).find())
        fail(path);
      try {
        if ("date".equals(s.path("format").asText())) java.time.LocalDate.parse(t);
        if ("date-time".equals(s.path("format").asText())) java.time.OffsetDateTime.parse(t);
      } catch (Exception e) {
        fail(path);
      }
    }
    if (v.isNumber()
        && ((s.has("minimum") && v.decimalValue().compareTo(s.get("minimum").decimalValue()) < 0)
            || (s.has("maximum")
                && v.decimalValue().compareTo(s.get("maximum").decimalValue()) > 0))) fail(path);
  }
}
