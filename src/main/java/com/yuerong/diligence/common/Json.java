package com.yuerong.diligence.common;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

public final class Json {
  public static final ObjectMapper M = new ObjectMapper();

  public static ObjectNode obj(Object... pairs) {
    ObjectNode n = M.createObjectNode();
    for (int i = 0; i < pairs.length; i += 2) n.set((String) pairs[i], M.valueToTree(pairs[i + 1]));
    return n;
  }

  public static ArrayNode arr(Object... values) {
    ArrayNode n = M.createArrayNode();
    for (Object v : values) n.add(M.valueToTree(v));
    return n;
  }

  public static String id() {
    return UUID.randomUUID().toString();
  }

  public static String now() {
    return java.time.Instant.now().toString();
  }

  public static JsonNode parse(String s) {
    try {
      return M.readTree(s);
    } catch (Exception e) {
      throw new Fault("INVALID_ARGUMENT", "JSON格式无效", 400, e);
    }
  }

  public static ObjectNode object(JsonNode n) {
    if (n == null || !n.isObject()) throw new Fault("INVALID_ARGUMENT", "需要JSON对象");
    return (ObjectNode) n;
  }

  private static JsonNode canonical(JsonNode n) {
    if (n.isObject()) {
      ObjectNode result = obj();
      java.util.TreeSet<String> keys = new java.util.TreeSet<>();
      n.fieldNames().forEachRemaining(keys::add);
      for (String key : keys) result.set(key, canonical(n.get(key)));
      return result;
    }
    if (n.isArray()) {
      ArrayNode result = arr();
      for (JsonNode item : n) result.add(canonical(item));
      return result;
    }
    return n;
  }

  public static String hash(Object n) {
    try {
      byte[] b =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(
                  n instanceof byte[]
                      ? (byte[]) n
                      : M.writeValueAsBytes(canonical(M.valueToTree(n))));
      StringBuilder s = new StringBuilder();
      for (byte v : b) s.append(String.format("%02x", v));
      return s.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
