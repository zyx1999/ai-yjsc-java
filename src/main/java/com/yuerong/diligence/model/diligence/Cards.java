package com.yuerong.diligence.model.diligence;

import com.yuerong.diligence.common.Json;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

public final class Cards {
  public static ObjectNode context() {
    return Json.obj(
        "period", null, "scope", "NOT_APPLICABLE", "currency", null, "unit", null, "as_of", null);
  }

  public static ObjectNode field(
      String key, String label, String value, JsonNode context, boolean editable, String evidence) {
    return Json.obj(
        "field_key",
        key,
        "label",
        label,
        "value",
        value,
        "value_type",
        "TEXT",
        "origin",
        "SOURCE",
        "editable",
        editable,
        "missing_reason",
        value == null ? "来源未提供" : null,
        "evidence_refs",
        evidence == null ? Json.arr() : Json.arr(evidence),
        "context",
        context);
  }

  public static ObjectNode group(String key, String title) {
    return Json.obj(
        "group_key",
        key,
        "title",
        title,
        "data_status",
        "UNAVAILABLE",
        "rows",
        Json.arr(),
        "next_cursor",
        null,
        "total_count",
        null,
        "complete",
        false,
        "limitations",
        Json.arr("来源尚未接通"));
  }

  public static ObjectNode card(String dimension) {
    return Json.obj(
        "dimension",
        dimension,
        "data_status",
        "UNAVAILABLE",
        "analysis_status",
        "NOT_GENERATED",
        "input_version",
        "0",
        "groups",
        Json.arr(),
        "metrics",
        Json.arr(),
        "summaries",
        Json.arr(),
        "evidence",
        Json.arr(),
        "limitations",
        Json.arr(),
        "available_actions",
        Json.arr("VIEW", "QUERY"));
  }
}
