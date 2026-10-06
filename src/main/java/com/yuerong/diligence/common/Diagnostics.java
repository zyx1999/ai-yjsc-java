package com.yuerong.diligence.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded metadata only. Never pass request bodies, credentials or document/model text here. */
public final class Diagnostics {
  private static final Logger LOG = LoggerFactory.getLogger("com.yuerong.diligence.diagnostics");
  private static final ThreadLocal<ObjectNode> CONTEXT = new ThreadLocal<>();
  private static final Set<String> KEYS = new HashSet<>(Arrays.asList(
      "http_request_id", "request_id", "task_id", "operation_id", "file_id", "call_id",
      "model_session_id", "platform_session_id", "action", "stage", "status", "error_code", "elapsed_ms", "page",
      "pages", "bytes", "file_count", "count", "limit_count", "attempt", "step_id", "reused",
      "mode", "role", "route", "method", "http_status", "content_type", "timeout_ms",
      "event_index", "event_type", "node_id", "result_node", "result_seen", "result_shape",
      "wrapper_depth", "reason", "provider", "model_task", "async", "enabled"));

  public static Scope scope(Object... fields) {
    ObjectNode previous = CONTEXT.get();
    ObjectNode next = previous == null ? Json.obj() : previous.deepCopy();
    fields(next, fields); CONTEXT.set(next);
    return new Scope(previous);
  }

  public static final class Scope implements AutoCloseable {
    private final ObjectNode previous;
    private Scope(ObjectNode previous) { this.previous = previous; }
    public void close() { if (previous == null) CONTEXT.remove(); else CONTEXT.set(previous); }
  }

  public static String current(String key) {
    ObjectNode value = CONTEXT.get();
    return value == null ? "" : value.path(key).asText();
  }

  public static long elapsed(long started) { return (System.nanoTime() - started) / 1000000; }
  public static String shape(com.fasterxml.jackson.databind.JsonNode value) {
    return value == null ? "MISSING" : value.getNodeType().name();
  }
  public static void info(String event, Object... fields) { emit(false, event, null, fields); }
  public static void failure(String event, Throwable error, Object... fields) { emit(true, event, error, fields); }

  private static void fields(ObjectNode target, Object... fields) {
    for (int i = 0; i + 1 < fields.length; i += 2) {
      String key = String.valueOf(fields[i]);
      if (!KEYS.contains(key)) continue;
      Object value = fields[i + 1];
      if (value instanceof Number || value instanceof Boolean) target.set(key, Json.M.valueToTree(value));
      else if (value instanceof String) {
        String text = (String) value;
        // IDs/enums only: no URLs, paths, JSON, headers or free text.
        target.put(key, text.length() <= 160 && text.matches("[A-Za-z0-9_.;=+:/-]*")
            && !text.contains("://") ? text : "REDACTED");
      }
    }
  }

  private static void emit(boolean failed, String event, Throwable error, Object... fields) {
    ObjectNode context = CONTEXT.get();
    ObjectNode entry = context == null ? Json.obj() : context.deepCopy();
    entry.put("event", event); fields(entry, fields);
    if (error != null) {
      if (error instanceof Fault) entry.put("error_code", ((Fault) error).code);
      ArrayNode causes = Json.arr();
      Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
      for (Throwable cause = error; cause != null && causes.size() < 8 && seen.add(cause); cause = cause.getCause()) {
        ObjectNode detail = Json.obj("type", cause.getClass().getName());
        if (cause instanceof com.yuerong.diligence.common.validation.JsonSchemaValidator.Violation) detail.put("schema_path", ((com.yuerong.diligence.common.validation.JsonSchemaValidator.Violation) cause).schemaPath);
        ArrayNode frames = Json.arr();
        for (StackTraceElement frame : cause.getStackTrace()) {
          if (frames.size() >= 16) break;
          frames.add(frame.toString());
        }
        detail.set("frames", frames);
        if (cause instanceof JsonProcessingException) {
          com.fasterxml.jackson.core.JsonLocation location = ((JsonProcessingException) cause).getLocation();
          if (location != null) { detail.put("line", location.getLineNr()); detail.put("column", location.getColumnNr()); }
        }
        if (cause instanceof java.sql.SQLException) {
          java.sql.SQLException sql = (java.sql.SQLException) cause;
          String state = sql.getSQLState();
          if (state != null && state.matches("[A-Z0-9]{5}")) detail.put("sql_state", state);
          detail.put("vendor_code", sql.getErrorCode());
        }
        causes.add(detail);
      }
      // Exception messages/toString and raw stack-trace rendering can contain tokens or PDF/SQL data.
      entry.set("causes", causes);
    }
    if (failed) LOG.warn("[DILIGENCE] {}", entry); else LOG.info("[DILIGENCE] {}", entry);
  }

  private Diagnostics() {}
}
