package com.yuerong.diligence.service.diligence;

import com.yuerong.diligence.common.Fault;
import com.yuerong.diligence.common.Json;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class FinancialService {
  private final DiligenceService biz;

  public FinancialService(DiligenceService biz) {
    this.biz = biz;
  }

  private String key(JsonNode v) {
    ObjectNode c = Json.object(v.path("context").deepCopy());
    c.remove("as_of");
    return Json.hash(Json.arr(v.get("field_key"), c));
  }

  private Map<String, JsonNode> baseline(String code) {
    Map<String, JsonNode> m = new HashMap<>();
    for (JsonNode g : biz.source.query(code, "FINANCIALS").path("groups"))
      for (JsonNode row : g.path("rows"))
        for (JsonNode f : row.path("fields")) m.put(key(f), f.get("value"));
    return m;
  }

  public ObjectNode propose(String task, JsonNode args) {
    biz.contracts.check("FinancialCandidatesInput", args);
    biz.verifySubject(args.get("subject"));
    String code = args.at("/subject/credit_code").asText();
    String id = "proposal-" + Json.hash(Json.arr(task, args.get("idempotency_key")));
    ObjectNode existing = biz.store.get("proposal", id);
    if (existing != null) {
      if (!existing.path("digest").asText().equals(Json.hash(args)))
        throw new Fault("IDEMPOTENCY_CONFLICT", "同一请求标识不能改变候选内容", 409);
      return Json.object(existing.get("data").deepCopy());
    }
    Set<String> files = new HashSet<>();
    for (JsonNode f : args.path("file_ids")) {
      ObjectNode file = biz.owned("file", f.asText(), task);
      if (!code.equals(file.path("credit_code").asText())
          || !"FINANCIAL".equals(file.path("role").asText()))
        throw new Fault("ENTERPRISE_MISMATCH", "财报材料归属不匹配");
      files.add(f.asText());
    }
    Map<String, JsonNode> evidence = new HashMap<>();
    for (JsonNode e : args.path("evidence")) {
      if (!files.contains(e.path("source_id").asText())
          || !"FILE".equals(e.path("source_kind").asText())
          || !e.has("page")) throw new Fault("EVIDENCE_INVALID", "候选须引用已登记原件页码");
      ObjectNode file = biz.owned("file", e.path("source_id").asText(), task);
      if (e.path("page").asInt() > file.path("pages").asInt())
        throw new Fault("EVIDENCE_INVALID", "证据页码超出原件");
      if (evidence.put(e.path("evidence_id").asText(), e) != null)
        throw new Fault("EVIDENCE_INVALID", "证据编号重复");
    }
    String sourceBaseline = biz.source.baseline(code);
    Map<String, JsonNode> old = baseline(code);
    if (!sourceBaseline.equals(biz.source.baseline(code)))
      throw new Fault("VERSION_CONFLICT", "查询期间源数据发生变化", 409);
    ArrayNode items = Json.arr();
    Set<String> ids = new HashSet<>(), keys = new HashSet<>();
    for (JsonNode c : args.path("candidates")) {
      if (!ids.add(c.path("candidate_id").asText()) || !keys.add(key(c)))
        throw new Fault("INVALID_ARGUMENT", "候选编号或同期间字段重复");
      for (JsonNode ref : c.path("evidence_refs"))
        if (!evidence.containsKey(ref.asText())) throw new Fault("EVIDENCE_INVALID", "候选引用未知证据");
      ArrayNode blockers = Json.arr();
      boolean mapped = biz.source.writable("FINANCIALS", c.path("field_key").asText());
      if (!mapped) blockers.add("无受控写映射或原始口径尚有冲突");
      JsonNode ctx = c.path("context");
      if (ctx.path("period").isNull()
          || ctx.path("currency").isNull()
          || ctx.path("unit").isNull()
          || !Arrays.asList("STANDALONE", "CONSOLIDATED").contains(ctx.path("scope").asText()))
        blockers.add("期间、币种、单位或报表口径缺失");
      if (ctx.hasNonNull("period")
          && ctx.at("/period/start").asText().compareTo(ctx.at("/period/end").asText()) > 0)
        blockers.add("期间起止顺序无效");
      if (c.path("partial").asBoolean()) blockers.add("提取不完整，须重新核对材料");
      JsonNode db = old.get(key(c));
      String diff =
          !mapped
              ? "MISSING_MAPPING"
              : !blockers.isEmpty()
                  ? "UNCOMPARABLE"
                  : c.path("normalized_value").isNull()
                      ? "INVALID"
                      : db == null || db.isNull()
                          ? "NEW"
                          : db.equals(c.get("normalized_value")) ? "UNCHANGED" : "CHANGED";
      items.add(
          Json.obj(
              "candidate",
              c,
              "database_value",
              db,
              "difference",
              diff,
              "writable",
              blockers.isEmpty(),
              "blockers",
              blockers));
    }
    ObjectNode operation = biz.newOperation(task, "FINANCIAL_REVIEW");
    operation.put("status", "AWAITING_CONFIRMATION");
    operation.put("proposal_id", id);
    biz.finish(task, operation);
    ObjectNode data =
        Json.obj(
            "proposal_id",
            id,
            "subject",
            args.get("subject"),
            "baseline_token",
            Json.id(),
            "status",
            "AWAITING_CONFIRMATION",
            "operation_id",
            operation.get("operation_id"),
            "items",
            items);
    biz.contracts.check("FinancialProposalOutput", data);
    biz.store.create(
        "proposal",
        id,
        task,
        Json.obj(
            "task_id",
            task,
            "digest",
            Json.hash(args),
            "data",
            data,
            "source_baseline",
            sourceBaseline,
            "evidence",
            args.get("evidence"),
            "confirmed",
            false));
    return data;
  }

  public ObjectNode confirm(String task, JsonNode args) {
    biz.contracts.check("FinancialConfirmInput", args);
    String proposal = args.path("proposal_id").asText();
    biz.owned("proposal", proposal, task);
    ObjectNode updated =
        biz.store.update(
            "proposal",
            proposal,
            p -> {
              if (p.path("confirmed").asBoolean()) {
                if (!p.path("confirmation_digest").asText().equals(Json.hash(args)))
                  throw new Fault("IDEMPOTENCY_CONFLICT", "提案已确认，不能重复修改", 409);
                return p;
              }
              JsonNode data = p.get("data");
              if (!args.path("baseline_token").equals(data.get("baseline_token")))
                throw new Fault("VERSION_CONFLICT", "提案基准标识不匹配", 409);
              Map<String, JsonNode> items = new HashMap<>(), evidence = new HashMap<>();
              for (JsonNode i : data.path("items"))
                items.put(i.at("/candidate/candidate_id").asText(), i);
              for (JsonNode e : p.path("evidence")) evidence.put(e.path("evidence_id").asText(), e);
              Set<String> seen = new HashSet<>();
              ArrayNode changes = Json.arr();
              for (JsonNode d : args.path("decisions")) {
                String cid = d.path("candidate_id").asText();
                if (!seen.add(cid) || !items.containsKey(cid))
                  throw new Fault("INVALID_ARGUMENT", "确认包含重复或未知候选");
                if ("IGNORE".equals(d.path("action").asText())) continue;
                JsonNode i = items.get(cid), c = i.get("candidate");
                if (!i.path("writable").asBoolean())
                  throw new Fault("WRITE_MAPPING_MISSING", "该项不能写入：请先解决口径或映射问题", 409);
                JsonNode v =
                    "REPLACE".equals(d.path("action").asText())
                        ? d.get("replacement_value")
                        : c.get("normalized_value");
                if (v == null || v.isNull()) throw new Fault("INVALID_ARGUMENT", "空候选必须修正或忽略");
                String ev = c.path("evidence_refs").get(0).asText();
                changes.add(
                    Json.obj(
                        "field_key",
                        c.get("field_key"),
                        "context",
                        c.get("context"),
                        "value",
                        v,
                        "evidence_id",
                        ev,
                        "evidence",
                        evidence.get(ev)));
              }
              if (changes.isEmpty()) throw new Fault("INVALID_ARGUMENT", "没有采用项，未创建财报");
              String code = data.at("/subject/credit_code").asText();
              String op = data.path("operation_id").asText();
              // Adapter must implement source baseline CAS and durable operation idempotency.
              biz.source.apply(code, p.path("source_baseline").asText(), op, changes);
              p.put("confirmed", true);
              p.put("confirmation_digest", Json.hash(args));
              p.put("write_status", "SOURCE_APPLIED");
              return p;
            });
    String opId = updated.at("/data/operation_id").asText();
    ObjectNode op = biz.operation(task, opId);
    if ("SUCCEEDED".equals(op.path("status").asText())) return op;
    try {
      ObjectNode refreshed =
          biz.financialRefresh(task, updated.at("/data/subject/credit_code").asText());
      op.put("status", "SUCCEEDED");
      op.put("stage", "SOURCE_SAVED");
      op.set("result_id", refreshed.get("result_id"));
      op.set("result_version", refreshed.get("version"));
      op.putNull("error");
    } catch (RuntimeException e) {
      op.put("status", "UNKNOWN");
      op.put("stage", "SOURCE_APPLIED_RESULT_PENDING");
      op.set(
          "error",
          Json.obj(
              "code",
              "RESULT_UNKNOWN",
              "message",
              "源已保存，结果刷新待核实；请查询操作，不要重复写入",
              "retryable",
              false));
    }
    return biz.finish(task, op);
  }

  public ObjectNode saveSource(String task, JsonNode args) {
    biz.contracts.check("SourceSaveInput", args);
    String dedup = "save-" + Json.hash(Json.arr(task, args.get("idempotency_key")));
    ObjectNode prior = biz.store.get("write", dedup);
    if (prior != null) {
      if (!prior.path("digest").asText().equals(Json.hash(args)))
        throw new Fault("IDEMPOTENCY_CONFLICT", "请求内容改变", 409);
      return biz.operation(task, prior.path("operation_id").asText());
    }
    ObjectNode result = biz.read(task, Json.obj("result_id", args.get("result_id")));
    if (!args.get("base_version").equals(result.get("version")))
      throw new Fault("VERSION_CONFLICT", "采用版本已变化", 409);
    // Editing follows the same explicit financial confirmation pipeline. No arbitrary table writes.
    Map<String, JsonNode> fields = new HashMap<>();
    for (JsonNode card : result.path("dimensions"))
      for (JsonNode g : card.path("groups"))
        for (JsonNode row : g.path("rows"))
          for (JsonNode f : row.path("fields"))
            fields.put(row.path("record_id").asText() + ":" + f.path("field_key").asText(), f);
    ArrayNode changes = Json.arr();
    Set<String> seen = new HashSet<>();
    String code = result.at("/subject/credit_code").asText();
    for (JsonNode c : args.path("changes")) {
      String key = c.path("record_id").asText() + ":" + c.path("field_key").asText();
      JsonNode field = fields.get(key);
      if (!seen.add(key)
          || field == null
          || !field.path("editable").asBoolean()
          || !biz.source.writable("FINANCIALS", c.path("field_key").asText()))
        throw new Fault("WRITE_MAPPING_MISSING", "记录不存在或字段只读");
      if (!c.path("value").isTextual()
          || !c.path("value").asText().matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?"))
        throw new Fault("INVALID_ARGUMENT", "金额格式无效");
      String ev = "edit-" + Json.id();
      changes.add(
          Json.obj(
              "field_key",
              c.get("field_key"),
              "context",
              field.get("context"),
              "value",
              c.get("value"),
              "evidence_id",
              ev,
              "evidence",
              Json.obj(
                  "evidence_id",
                  ev,
                  "source_id",
                  args.get("result_id"),
                  "source_kind",
                  "BUSINESS_API",
                  "locator",
                  key,
                  "data_time",
                  Json.now())));
    }
    ObjectNode op = biz.newOperation(task, "SOURCE_SAVE");
    biz.store.create(
        "write",
        dedup,
        task,
        Json.obj("digest", Json.hash(args), "operation_id", op.get("operation_id")));
    boolean applied = false;
    try {
      String baseline =
          biz.owned("result", result.path("result_id").asText(), task)
              .path("baselines")
              .path(result.path("version").asText())
              .path("FINANCIALS")
              .asText(null);
      if (baseline == null) throw new Fault("VERSION_CONFLICT", "结果缺少源基准，请重新查询", 409);
      biz.source.apply(code, baseline, op.path("operation_id").asText(), changes);
      applied = true;
      ObjectNode refreshed = biz.financialRefresh(task, code);
      op.put("status", "SUCCEEDED");
      op.set("result_id", refreshed.get("result_id"));
      op.set("result_version", refreshed.get("version"));
    } catch (RuntimeException e) {
      boolean rejected =
          !applied
              && e instanceof Fault
              && java.util.Arrays.asList(
                      "VERSION_CONFLICT",
                      "WRITE_MAPPING_MISSING",
                      "INVALID_ARGUMENT",
                      "FORBIDDEN",
                      "IDEMPOTENCY_CONFLICT")
                  .contains(((Fault) e).code);
      op.put("status", rejected ? "FAILED" : "UNKNOWN");
      op.set(
          "error",
          Json.obj(
              "code",
              rejected ? ((Fault) e).code : "RESULT_UNKNOWN",
              "message",
              rejected ? e.getMessage() : "保存结果待核实，请查询操作状态",
              "retryable",
              false));
    }
    return biz.finish(task, op);
  }
}
