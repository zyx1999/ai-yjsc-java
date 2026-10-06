package com.yuerong.diligence.repository.source.mysql;

import com.yuerong.diligence.common.Fault;
import com.yuerong.diligence.common.Json;
import com.yuerong.diligence.model.diligence.Cards;
import com.yuerong.diligence.repository.EnterpriseDataPort;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Only server-reviewed mapping SQL can execute. No identifiers or SQL are accepted from callers.
 */
public class MysqlSourceAdapter implements EnterpriseDataPort {
  private final NamedParameterJdbcTemplate jdbc;
  private final JsonNode mapping;

  public MysqlSourceAdapter(NamedParameterJdbcTemplate jdbc, String path) {
    this.jdbc = jdbc;
    try {
      if (path.isEmpty()) mapping = Json.obj();
      else {
        try (InputStream input = path.startsWith("classpath:")
            ? new org.springframework.core.io.DefaultResourceLoader().getResource(path).getInputStream()
            : Files.newInputStream(Paths.get(path))) {
          mapping = Json.M.readTree(input);
        }
      }
    } catch (Exception e) {
      throw new IllegalStateException("Source mapping cannot be loaded", e);
    }
  }

  public ObjectNode resolve(JsonNode args) {
    boolean nameSearch = args.has("enterprise_name") && !args.has("credit_code");
    String sql = mapping.path(nameSearch ? "enterprise_search_sql" : "enterprise_sql").asText("");
    if (sql.isEmpty()) throw new Fault("SOURCE_UNAVAILABLE", "尚未配置企业源查询", 503);
    Map<String, Object> params = new HashMap<>();
    params.put("credit_code", args.path("credit_code").asText(null));
    params.put("enterprise_name", args.path("enterprise_name").asText(null));
    if (nameSearch)
      params.put("enterprise_name_pattern", "%" + args.path("enterprise_name").asText()
          .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
    List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
    ArrayNode candidates = Json.arr();
    for (Map<String, Object> row : rows) {
      ObjectNode subject =
          Json.obj(
              "credit_code", row.get("credit_code"), "enterprise_name", row.get("enterprise_name"));
      if (args.has("credit_code") && !args.get("credit_code").equals(subject.get("credit_code")))
        continue;
      if (args.has("enterprise_name")) {
        String expected = args.path("enterprise_name").asText();
        String actual = subject.path("enterprise_name").asText();
        if (nameSearch ? !actual.contains(expected) : !actual.equals(expected)) continue;
      }
      if (!contains(candidates, subject)) candidates.add(subject);
    }
    if (candidates.size() > 10) throw new Fault("ENTERPRISE_AMBIGUOUS", "同名企业过多，请提供信用代码");
    return Json.obj(
        "resolution",
        candidates.size() == 1 ? "MATCHED" : candidates.size() > 1 ? "AMBIGUOUS" : "NOT_FOUND",
        "subject",
        candidates.size() == 1 ? candidates.get(0) : null,
        "candidates",
        candidates.size() > 1 ? candidates : Json.arr());
  }

  private boolean contains(ArrayNode a, JsonNode value) {
    for (JsonNode x : a) if (x.equals(value)) return true;
    return false;
  }

  public ObjectNode query(String code, String dimension) {
    ObjectNode card = Cards.card(dimension);
    ArrayNode groups = (ArrayNode) card.get("groups");
    ArrayNode evidence = (ArrayNode) card.get("evidence");
    for (JsonNode g : mapping.path("groups")) {
      if (!dimension.equals(g.path("dimension").asText())) continue;
      ObjectNode group = Cards.group(g.path("group_key").asText(), g.path("title").asText());
      groups.add(group);
      if (g.has("limitations")) group.set("limitations", g.path("limitations").deepCopy());
      for (JsonNode limit : group.path("limitations")) ((ArrayNode) card.get("limitations")).add(limit);
      if (!g.hasNonNull("sql")) continue;
      List<Map<String, Object>> rows =
          jdbc.queryForList(g.get("sql").asText(), Collections.singletonMap("credit_code", code));
      if (rows.size() > 10000) throw new Fault("SOURCE_LIMIT", "源查询超过10000行，请收窄源查询");
      ArrayNode out = (ArrayNode) group.get("rows");
      int i = 0;
      for (Map<String, Object> row : rows) {
        String id = String.valueOf(row.get("record_id"));
        if (id.equals("null")) throw new Fault("SOURCE_MAPPING_INVALID", "源查询缺少稳定record_id", 503);
        String ev = "db-" + Json.hash(code + g.path("group_key").asText() + id);
        ArrayNode fields = Json.arr();
        for (JsonNode f : g.path("fields")) {
          Object value = column(row, f.path("column").asText());
          ObjectNode field =
              Cards.field(
                  f.path("field_key").asText(),
                  f.path("label").asText(),
                  value == null ? null : String.valueOf(value),
                  fieldContext(g, f, row),
                  false,
                  ev);
          field.put("value_type", f.path("value_type").asText("TEXT"));
          fields.add(field);
        }
        out.add(
            Json.obj(
                "record_id",
                id,
                "subject_name",
                row.get("enterprise_name"),
                "subject_relation",
                "TARGET",
                "fields",
                fields));
        evidence.add(
            Json.obj(
                "evidence_id",
                ev,
                "source_id",
                g.path("group_key").asText(),
                "source_kind",
                "DATABASE",
                "locator",
                id,
                "data_time",
                g.has("data_time_column") ? column(row, g.path("data_time_column").asText()) : null));
        i++;
      }
      group.put("data_status", rows.isEmpty() ? "EMPTY" : "AVAILABLE");
      group.put("total_count", rows.size());
      group.put("complete", true);
      if (!g.has("limitations")) group.set("limitations", Json.arr());
    }
    boolean available = false, missing = groups.isEmpty(), empty = true;
    for (JsonNode g : groups) {
      missing |= !g.path("complete").asBoolean();
      available |= "AVAILABLE".equals(g.path("data_status").asText());
      empty &= "EMPTY".equals(g.path("data_status").asText());
    }
    card.put(
        "data_status",
        missing ? (available ? "PARTIAL" : "UNAVAILABLE") : (empty ? "EMPTY" : "AVAILABLE"));
    ((ArrayNode) card.get("limitations")).add("物理映射由数据负责人配置；未配置分组不可用；源写入映射尚未获批");
    return card;
  }

  /** Keep period/unit metadata complete while mapping source columns per environment. */
  private static ObjectNode fieldContext(JsonNode group, JsonNode field, Map<String, Object> row) {
    ObjectNode context = Cards.context();
    for (JsonNode config : Arrays.asList(group, field)) {
      if (config.path("context").isObject()) context.setAll((ObjectNode) config.get("context"));
      config.path("context_columns").fields().forEachRemaining(entry -> {
        if (!Arrays.asList("scope", "currency", "unit", "as_of").contains(entry.getKey()))
          throw new Fault("SOURCE_MAPPING_INVALID", "源映射包含未知口径属性", 503);
        Object value = column(row, entry.getValue().asText());
        if (value == null) context.putNull(entry.getKey());
        else context.put(entry.getKey(), String.valueOf(value));
      });
    }
    return context;
  }

  private static Object column(Map<String, Object> row, String name) {
    if (!row.containsKey(name)) throw new Fault("SOURCE_MAPPING_INVALID", "源查询缺少映射列：" + name, 503);
    return row.get(name);
  }

  public boolean writable(String dimension, String field) {
    return false;
  }

  public String baseline(String code) {
    return Json.hash(query(code, "FINANCIALS"));
  }

  public void apply(String code, String baseline, String operation, ArrayNode changes) {
    throw new Fault("WRITE_MAPPING_MISSING", "正式源受控写映射尚未配置，未执行写入", 409);
  }
}
