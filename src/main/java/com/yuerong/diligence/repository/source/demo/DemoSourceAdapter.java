package com.yuerong.diligence.repository.source.demo;

import com.yuerong.diligence.common.Fault;
import com.yuerong.diligence.common.Json;
import com.yuerong.diligence.model.diligence.Cards;
import com.yuerong.diligence.repository.ApplicationStorePort;
import com.yuerong.diligence.repository.EnterpriseDataPort;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Synthetic fixtures are persisted in the application database, never a real-source fallback. */
public class DemoSourceAdapter implements EnterpriseDataPort {
  private final ApplicationStorePort store;
  private final JsonNode catalog, seed;

  public DemoSourceAdapter(ApplicationStorePort store) {
    this.store = store;
    try {
      catalog = Json.M.readTree(getClass().getResourceAsStream("/adapter/source/catalog.json"));
      seed = Json.M.readTree(getClass().getResourceAsStream("/adapter/source/demo/seed.json"));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    for (JsonNode company : seed) state(company.path("subject").path("credit_code").asText());
  }

  public ObjectNode resolve(JsonNode args) {
    ArrayNode candidates = Json.arr();
    for (ObjectNode company : store.list("demo-source", "demo")) {
      JsonNode s = company.path("subject");
      if (args.has("credit_code") && !s.get("credit_code").equals(args.get("credit_code")))
        continue;
      if (args.has("enterprise_name")) {
        String name = args.get("enterprise_name").asText();
        String actual = s.path("enterprise_name").asText();
        if (args.has("credit_code") ? !actual.equals(name) : !actual.contains(name)) continue;
      }
      candidates.add(s);
    }
    return Json.obj(
        "resolution",
        candidates.size() == 1 ? "MATCHED" : candidates.size() > 1 ? "AMBIGUOUS" : "NOT_FOUND",
        "subject",
        candidates.size() == 1 ? candidates.get(0) : null,
        "candidates",
        candidates.size() > 1 ? candidates : Json.arr());
  }

  private ObjectNode state(String code) {
    ObjectNode s = store.get("demo-source", code);
    if (s == null) {
      JsonNode company = null;
      for (JsonNode item : seed)
        if (code.equals(item.at("/subject/credit_code").asText())) company = item;
      if (company == null) throw new Fault("NOT_FOUND", "示例企业不存在", 404);
      ArrayNode values = (ArrayNode) company.get("values").deepCopy();
      for (JsonNode v : values) {
        ObjectNode ctx = Json.object(v.get("context").deepCopy());
        ctx.remove("as_of");
        ((ObjectNode) v).put("record_id", Json.hash(Json.arr(code, v.get("field_key"), ctx)));
      }
      try {
        store.create(
            "demo-source",
            code,
            "demo",
            Json.obj(
                "revision",
                0,
                "subject",
                company.get("subject"),
                "values",
                values,
                "cards",
                company.get("cards"),
                "applied",
                Json.obj()));
      } catch (Fault e) {
        if (!"VERSION_CONFLICT".equals(e.code)) throw e;
      }
      s = store.get("demo-source", code);
    } else if (!s.has("subject")) {
      JsonNode company = null;
      for (JsonNode item : seed)
        if (code.equals(item.at("/subject/credit_code").asText())) company = item;
      if (company != null) {
        final JsonNode subject = company.get("subject");
        s = store.update("demo-source", code, current -> {
          current.set("subject", subject.deepCopy());
          return current;
        });
      }
    }
    return s;
  }

  public ObjectNode query(String code, String dimension) {
    JsonNode subject = resolve(Json.obj("credit_code", code)).get("subject");
    if (subject.isNull()) throw new Fault("NOT_FOUND", "示例企业不存在", 404);
    ObjectNode source = state(code);
    ObjectNode c = Json.object(source.path("cards").path(dimension).deepCopy());
    if ("FINANCIALS".equals(dimension)) {
      ObjectNode g = (ObjectNode) c.path("groups").get(0);
      ArrayNode rows = Json.arr();
      LinkedHashMap<String, ObjectNode> periods = new LinkedHashMap<>();
      ArrayNode evidence = Json.arr();
      Set<String> refs = new HashSet<>();
      for (JsonNode v : source.path("values")) {
        ObjectNode grouping = Json.object(v.get("context").deepCopy());
        grouping.remove("as_of");
        String period = Json.hash(grouping);
        ObjectNode r = periods.get(period);
        if (r == null) {
          r =
              Json.obj(
                  "record_id",
                  period,
                  "subject_name",
                  subject.path("enterprise_name").asText(),
                  "subject_relation",
                  "TARGET",
                  "fields",
                  Json.arr());
          periods.put(period, r);
        }
        ObjectNode f =
            Cards.field(
                v.path("field_key").asText(),
                v.path("label").asText(),
                v.path("value").asText(),
                v.get("context"),
                writable("FINANCIALS", v.path("field_key").asText()),
                v.path("evidence_id").asText());
        f.put("value_type", "DECIMAL");
        ((ArrayNode) r.get("fields")).add(f);
        if (refs.add(v.path("evidence_id").asText())) evidence.add(v.get("evidence"));
      }
      for (ObjectNode r : periods.values()) rows.add(r);
      g.set("rows", rows);
      g.put("data_status", rows.isEmpty() ? "EMPTY" : "AVAILABLE");
      g.put("total_count", rows.size());
      g.put("complete", true);
      c.set("evidence", evidence);
      c.set("available_actions", Json.arr("VIEW", "QUERY", "UPLOAD_FINANCIAL", "EDIT_SOURCE"));
    }
    return c;
  }

  public boolean writable(String dimension, String key) {
    return "FINANCIALS".equals(dimension)
        && catalog.path("financial_fields").has(key)
        && !catalog.path("financial_fields").path(key).path("blocked").asBoolean();
  }

  public String baseline(String code) {
    return state(code).path("revision").asText();
  }

  public void apply(String code, String baseline, String operation, ArrayNode changes) {
    store.update(
        "demo-source",
        code,
        s -> {
          if (s.path("applied").has(operation)) {
            if (!s.path("applied").path(operation).asText().equals(Json.hash(changes)))
              throw new Fault("IDEMPOTENCY_CONFLICT", "写入内容发生变化", 409);
            return s;
          }
          if (!baseline.equals(s.path("revision").asText()))
            throw new Fault("VERSION_CONFLICT", "源基准已变化，请重新核对", 409);
          ArrayNode values = (ArrayNode) s.get("values");
          for (JsonNode change : changes) {
            String key = change.path("field_key").asText();
            if (!writable("FINANCIALS", key)) throw new Fault("WRITE_MAPPING_MISSING", "字段无写映射");
            ObjectNode context = Json.object(change.get("context").deepCopy());
            context.remove("as_of");
            String row = Json.hash(Json.arr(code, key, context));
            for (int i = values.size() - 1; i >= 0; i--) {
              JsonNode existing = values.get(i);
              ObjectNode previousContext = Json.object(existing.get("context").deepCopy());
              previousContext.remove("as_of");
              if (key.equals(existing.path("field_key").asText())
                  && context.equals(previousContext)) values.remove(i);
            }
            ObjectNode v = Json.object(change.deepCopy());
            v.put("record_id", row);
            v.put("label", catalog.path("financial_fields").path(key).path("label").asText());
            values.add(v);
          }
          s.put("revision", s.path("revision").asInt() + 1);
          ((ObjectNode) s.get("applied")).put(operation, Json.hash(changes));
          return s;
        });
  }
}
