package com.yuerong.diligence.service.diligence;

import com.yuerong.diligence.common.Diagnostics;
import com.yuerong.diligence.common.Fault;
import com.yuerong.diligence.common.Json;
import com.yuerong.diligence.model.diligence.Cards;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Complete deterministic business workflows. The model only extracts or explains supplied facts. */
@Service
public class BusinessCapabilities {
  private final DiligenceService biz;
  private final FinancialService finance;
  private final FilesService files;
  private final DiligenceModelService model;
  private final JsonNode schemas, financialFields;

  public BusinessCapabilities(DiligenceService biz, FinancialService finance, FilesService files, DiligenceModelService model) {
    this.biz = biz; this.finance = finance; this.files = files; this.model = model;
    schemas = Json.parse(resource("model-schemas.json"));
    financialFields = Json.parse(resource("financial-fields.json"));
  }

  public void validate(String task, String action, JsonNode args) {
    if ("investigation.get".equals(action)) {
      biz.contracts.check("InvestigationGetInput", args);
      biz.bindSubject(task, biz.subject(args.path("credit_code").asText()));
      return;
    }
    if (action.startsWith("financial.")) {
      biz.contracts.check("FinancialOperationInput", args);
      String expected = "financial.review".equals(action) ? "IMPORT" : "financial.analyze".equals(action) ? "ANALYZE" : "";
      if (!expected.equals(args.path("mode").asText())) throw new Fault("INVALID_ARGUMENT", "财报能力与模式不匹配");
    } else if ("credit.review".equals(action)) biz.contracts.check("CreditOperationInput", args);
    else throw new Fault("INVALID_ARGUMENT", "未登记业务能力");
    biz.verifySubject(args.get("subject"));
    String code = args.at("/subject/credit_code").asText();
    biz.requireInvestigation(task, code);
    for (JsonNode id : args.path("file_ids")) {
      ObjectNode file = biz.owned("file", id.asText(), task);
      if (!code.equals(file.path("credit_code").asText())
          || !(action.startsWith("financial.") ? "FINANCIAL" : "CREDIT").equals(file.path("role").asText()))
        throw new Fault("ENTERPRISE_MISMATCH", "材料企业或用途与业务不一致", 403);
    }
    if ("financial.analyze".equals(action)) {
      ObjectNode result = biz.read(task, Json.obj("result_id", args.get("result_id"), "version", args.get("version")));
      if (!result.path("subject").equals(args.get("subject"))) throw new Fault("ENTERPRISE_MISMATCH", "分析结果企业不一致", 403);
      if (!args.path("version").asText().equals(biz.currentVersion(task, code)))
        throw new Fault("VERSION_CONFLICT", "分析须基于当前采用版本", 409);
    }
  }

  public Supplier<ObjectNode> execute(CapabilityJobs.Run run) {
    switch (run.action) {
      case "investigation.get": return investigate(run);
      case "financial.review": return financialReview(run);
      case "financial.analyze": return financialAnalyze(run);
      case "credit.review": return creditReview(run);
      default: throw new Fault("INVALID_ARGUMENT", "未登记业务能力");
    }
  }

  private Supplier<ObjectNode> investigate(CapabilityJobs.Run run) {
    String code = run.input.path("credit_code").asText();
    JsonNode snapshot = run.step("investigation-source-v1", "SOURCE_QUERY", () -> {
      String baseline = biz.source.baseline(code);
      ArrayNode cards = Json.arr();
      for (JsonNode dim : biz.contracts.schema.path("x-dimensions")) cards.add(biz.collectCard(code, dim.path("code").asText()));
      if (!baseline.equals(biz.source.baseline(code))) throw new Fault("VERSION_CONFLICT", "取数期间财务源基准变化", 409);
      return Json.obj("subject", biz.subject(code), "cards", cards, "financial_baseline", baseline, "queried_at", Json.now());
    });
    ArrayNode cards = (ArrayNode) snapshot.get("cards").deepCopy();
    ArrayNode limits = Json.arr();
    for (JsonNode card : cards)
      if (!Arrays.asList("AVAILABLE", "EMPTY").contains(card.path("data_status").asText()))
        addLimit(limits, card.path("dimension").asText() + "：数据存在缺口");
    try { investigationSummary(run, snapshot.get("subject"), cards); }
    catch (Fault e) {
      if ("LEASE_LOST".equals(e.code)) throw e;
      Diagnostics.failure("investigation.summary_failed", e, "stage", "AI_SUMMARY");
      addLimit(limits, "AI总结未完成：" + e.getMessage());
      for (JsonNode card : cards) if (Arrays.asList("PROFILE", "CREDIT").contains(card.path("dimension").asText())) {
        ((ObjectNode) card).put("analysis_status", "NOT_GENERATED");
        addLimit((ArrayNode) card.get("limitations"), "AI总结未完成：" + e.getMessage());
      }
    }
    return () -> {
      ObjectNode saved = biz.publishInvestigation(run.task, snapshot.get("subject"), cards,
          run.baseVersion, snapshot.path("financial_baseline").asText());
      ObjectNode op = run.operation(limits.isEmpty() ? "SUCCEEDED" : "PARTIAL", "INVESTIGATION_SAVED");
      op.set("result_id", saved.get("result_id")); op.set("result_version", saved.get("version"));
      return CapabilityJobs.skillResult(op, CapabilityJobs.dimensions(run.action), limits);
    };
  }

  private void investigationSummary(CapabilityJobs.Run run, JsonNode subject, ArrayNode cards) {
    ObjectNode profile = card(cards, "PROFILE"), credit = card(cards, "CREDIT");
    ArrayNode items = Json.arr();
    List<JsonNode> changes = metrics(profile, "C08", "C09", "C10", "C11", "C12");
    int[] thresholds = {4, 1, 6, 1, 4}; boolean missing = false, hit = false;
    for (int i = 0; i < changes.size(); i++) {
      BigDecimal value = metricNumber(changes.get(i).path("value"));
      missing |= value == null; hit |= value != null && value.compareTo(BigDecimal.valueOf(thresholds[i])) >= 0;
    }
    items.add(riskItem("A01", missing ? "资料不足，无法判断" : hit ? "触发旧版参考规则" : "未触发所列参考规则", changes));
    List<JsonNode> pledges = metrics(profile, "C04", "C06", "C07");
    BigDecimal total = metricNumber(pledges.get(0).path("value")), largest = metricNumber(pledges.get(2).path("value"));
    missing = total == null || largest == null || !pledges.get(1).hasNonNull("value");
    hit = !missing && (total.compareTo(new BigDecimal("25")) >= 0 || largest.compareTo(new BigDecimal("50")) >= 0);
    items.add(riskItem("A02", missing ? "资料不足，无法判断" : hit ? "触发旧版参考规则" : "未触发所列参考规则", pledges));
    for (String code : Arrays.asList("A03", "A04")) {
      ArrayNode facts = Json.arr(), refs = Json.arr();
      String key = "A03".equals(code) ? "source.6" : "source.7";
      for (JsonNode group : credit.path("groups")) if (key.equals(group.path("group_key").asText())
          && group.path("complete").asBoolean() && Arrays.asList("AVAILABLE", "EMPTY").contains(group.path("data_status").asText())) {
        int count = 0;
        for (JsonNode row : group.path("rows")) {
          if (count++ >= 20) break;
          ArrayNode fields = Json.arr();
          for (JsonNode field : row.path("fields")) if (field.hasNonNull("value")) {
            fields.add(Json.obj("name", field.get("label"), "value", field.get("value")));
            addRefs(refs, field.path("evidence_refs"));
          }
          facts.add(fields);
        }
      }
      items.add(Json.obj("code", code, "status", "资料不足，无法判断", "facts", facts, "evidence_refs", refs,
          "missing", "A03".equals(code) ? "融资方式、完整机构范围及逾期／不良依据未确认" : "担保贷款风险分类及逾期／不良依据未取得"));
    }
    Set<String> needed = new HashSet<>();
    for (JsonNode item : items) for (JsonNode ref : item.path("evidence_refs")) needed.add(ref.asText());
    ArrayNode fragments = Json.arr(); Set<String> seen = new HashSet<>();
    for (JsonNode c : cards) for (JsonNode e : c.path("evidence"))
      if (needed.contains(e.path("evidence_id").asText()) && seen.add(e.path("evidence_id").asText()))
        fragments.add(Json.obj("evidence_id", e.get("evidence_id"), "source_id", e.get("source_id"), "locator", e.get("locator")));
    JsonNode result = infer(run, "investigation.ai_summary", Json.obj("subject", subject, "items", items, "fragments", fragments), null);
    Map<String, JsonNode> received = new HashMap<>();
    for (JsonNode item : result.path("items"))
      if (received.put(item.path("code").asText(), item) != null) throw new Fault("MODEL_PROTOCOL_ERROR", "AI总结项目重复");
    if (received.size() != 4) throw new Fault("MODEL_PROTOCOL_ERROR", "AI总结项目不完整");
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put("A01", "工商变更风险提示（参考规则）"); labels.put("A02", "股权出质风险提示（参考规则）");
    labels.put("A03", "企业融资风险提示（待核实）"); labels.put("A04", "对外担保风险提示（待核实）");
    for (JsonNode item : items) {
      String code = item.path("code").asText(); JsonNode explanation = received.get(code);
      if (explanation == null) throw new Fault("MODEL_PROTOCOL_ERROR", "AI总结项目缺失");
      Set<JsonNode> allowed = new HashSet<>(); item.path("evidence_refs").forEach(allowed::add);
      for (JsonNode ref : explanation.path("evidence_refs")) if (!allowed.contains(ref)) throw new Fault("EVIDENCE_INVALID", "AI总结引用了其他项目证据");
      if (!item.path("facts").isEmpty() && explanation.path("evidence_refs").isEmpty()) throw new Fault("EVIDENCE_INVALID", "AI总结缺少事实证据");
      ObjectNode field = Cards.field("ai." + code, labels.get(code), item.path("status").asText() + "。" + explanation.path("text").asText().trim(), Cards.context(), false, null);
      field.put("origin", "AI_SUMMARY"); field.set("evidence_refs", explanation.get("evidence_refs"));
      ObjectNode target = code.compareTo("A03") < 0 ? profile : credit;
      ArrayNode summaries = (ArrayNode) target.get("summaries");
      for (int i = summaries.size() - 1; i >= 0; i--) if (field.path("field_key").equals(summaries.get(i).path("field_key"))) summaries.remove(i);
      summaries.add(field); target.put("analysis_status", "CURRENT");
    }
  }

  private Supplier<ObjectNode> financialAnalyze(CapabilityJobs.Run run) {
    JsonNode saved = run.step("financial-analysis-input", "READING_RESULT", () -> biz.read(run.task,
        Json.obj("result_id", run.input.get("result_id"), "version", run.input.get("version"), "dimensions", Json.arr("FINANCIALS"))));
    JsonNode card = saved.path("dimensions").get(0);
    JsonNode result = infer(run, "financial_report.analyze", Json.obj("question", run.input.get("question"), "card", card, "fragments", card.get("evidence")), null);
    return () -> {
      ObjectNode summary = Cards.field("financials.analysis", "财报分析", result.path("text").asText(), Cards.context(), false, null);
      summary.put("origin", "AI_SUMMARY"); summary.set("evidence_refs", result.get("evidence_refs"));
      ObjectNode registered = biz.register(run.task, Json.obj("result_id", saved.get("result_id"), "base_version", saved.get("version"),
          "dimension", "FINANCIALS", "rule_version", "analysis-v1", "summaries", Json.arr(summary), "evidence_refs", result.get("evidence_refs"),
          "analysis_status", "CURRENT", "limitations", Json.arr(), "idempotency_key", run.id), Collections.singleton("FINANCIALS"));
      ObjectNode op = run.operation("SUCCEEDED", "ANALYSIS_SAVED");
      op.set("result_id", registered.get("result_id")); op.set("result_version", registered.get("version"));
      return CapabilityJobs.skillResult(op, Json.arr("FINANCIALS"), Json.arr());
    };
  }

  private Supplier<ObjectNode> financialReview(CapabilityJobs.Run run) {
    ArrayNode limits = Json.arr(), evidence = Json.arr();
    Map<String, ObjectNode> grouped = new LinkedHashMap<>(); Set<String> evidenceIds = new HashSet<>();
    Set<String> fieldKeys = new HashSet<>(); collectFieldKeys(financialFields, fieldKeys);
    for (JsonNode id : run.input.path("file_ids")) {
      JsonNode material = material(run, id.asText());
      for (JsonNode fragment : material.path("fragments")) {
        JsonNode parsed = extract(run, "financial_report.extract", fragment,
            Json.obj("subject", run.input.get("subject"), "financial_fields", financialFields, "fragments", Json.arr(fragment)), limits);
        if (parsed == null) continue;
        for (JsonNode limit : parsed.path("limitations")) addLimit(limits, limit.asText());
        ObjectNode ev = Json.obj("evidence_id", fragment.get("evidence_id"), "source_id", fragment.get("source_id"),
            "source_kind", "FILE", "locator", "page:" + fragment.path("page").asInt(), "page", fragment.get("page"), "data_time", null);
        if (evidenceIds.add(ev.path("evidence_id").asText())) evidence.add(ev);
        for (JsonNode raw : parsed.path("candidates")) {
          ObjectNode c = Json.object(raw.deepCopy());
          if (!fieldKeys.contains(c.path("field_key").asText())) throw new Fault("INVALID_ARGUMENT", "提取科目不在受控清单");
          String normalized = decimal(c.get("raw_value"));
          c.set("normalized_value", normalized == null ? NullNode.instance : TextNode.valueOf(normalized));
          ObjectNode groupingContext = Json.object(c.get("context").deepCopy());
          groupingContext.remove("as_of");
          String key = Json.hash(Json.arr(c.get("field_key"), canonical(groupingContext)));
          c.put("candidate_id", "candidate-" + Json.hash(Json.arr(run.id, key)));
          if (grouped.containsKey(key)) {
            ObjectNode old = grouped.get(key);
            if (!numericEqual(old.get("normalized_value"), c.get("normalized_value"))) {
              old.put("partial", true); addLimit(limits, "同期间科目存在重复冲突，未自动选择");
            }
            addRefs((ArrayNode) old.get("evidence_refs"), c.get("evidence_refs"));
            if (old.path("evidence_refs").size() > 30) { old.put("partial", true); while (old.path("evidence_refs").size() > 30) ((ArrayNode) old.get("evidence_refs")).remove(30); }
          } else grouped.put(key, c);
        }
      }
    }
    Diagnostics.info("financial.merged", "count", grouped.size(), "limit_count", limits.size());
    if (grouped.isEmpty()) return needsInput(run, "FINANCIAL_MATERIAL_REQUIRED", limits, "未提取到可核对科目");
    ArrayNode candidates = Json.arr(); grouped.values().forEach(candidates::add);
    JsonNode proposalInput = Json.obj("subject", run.input.get("subject"), "file_ids", run.input.get("file_ids"),
        "candidates", candidates, "evidence", evidence, "idempotency_key", run.id);
    return () -> {
      ObjectNode proposal = finance.propose(run.task, proposalInput);
      ObjectNode op = run.operation("AWAITING_CONFIRMATION", "FINANCIAL_REVIEW"); op.set("proposal_id", proposal.get("proposal_id"));
      return CapabilityJobs.skillResult(op, Json.arr("FINANCIALS"), limits);
    };
  }

  private Supplier<ObjectNode> creditReview(CapabilityJobs.Run run) {
    JsonNode baseline = run.step("credit-baseline", "SOURCE_QUERY", () -> biz.collectCard(run.input.at("/subject/credit_code").asText(), "CREDIT"));
    ArrayNode records = Json.arr(), limits = Json.arr(), sourceRecords = Json.arr();
    boolean complete = false;
    for (JsonNode group : baseline.path("groups")) if ("credit.details".equals(group.path("group_key").asText())) {
      complete = group.path("complete").asBoolean() && Arrays.asList("AVAILABLE", "EMPTY").contains(group.path("data_status").asText());
      for (JsonNode row : group.path("rows")) {
        ObjectNode record = Json.obj();
        for (JsonNode field : row.path("fields")) if (field.path("field_key").asText().startsWith("credit."))
          record.set(field.path("field_key").asText().substring(7), field.get("value"));
        for (String key : Arrays.asList("account", "currency", "balance", "as_of")) if (!record.has(key)) complete = false;
        sourceRecords.add(record);
      }
    }
    for (JsonNode id : run.input.path("file_ids")) for (JsonNode fragment : material(run, id.asText()).path("fragments")) {
      JsonNode extracted = extract(run, "enterprise_credit.extract", fragment,
          Json.obj("subject", run.input.get("subject"), "fragments", Json.arr(fragment)), limits);
      if (extracted == null) continue;
      for (JsonNode value : extracted.path("records")) {
        ObjectNode record = Json.object(value.deepCopy()); record.set("evidence_refs", Json.arr(fragment.get("evidence_id"))); records.add(record);
      }
    }
    if (records.isEmpty()) return needsInput(run, "CREDIT_MATERIAL_REQUIRED", limits, "未提取到可比对征信记录");
    if (!complete) addLimit(limits, "未取得完整库内逐笔征信基准，尚未完成库内比对");
    JsonNode comparison = complete ? classify(records, sourceRecords) : NullNode.instance;
    if (complete && !comparison.path("ambiguous").isEmpty()) addLimit(limits, "存在匹配条件不足的记录，需人工核实");
    final boolean baselineComplete = complete;
    return () -> {
      String code = run.input.at("/subject/credit_code").asText();
      JsonNode results = biz.results(run.task);
      if (results.isEmpty()) throw new Fault("INVESTIGATION_REQUIRED", "请先生成调查表", 409);
      ObjectNode registered = biz.materials(run.task, Json.obj("result_id", results.get(0).get("result_id"), "base_version", run.baseVersion,
          "dimension", "CREDIT", "file_ids", run.input.get("file_ids"), "records", records, "comparison", comparison, "limitations", limits),
          Json.obj("capability", "enterprise-credit", "credit_code", code, "files", run.input.get("file_ids")), Json.object(baseline.deepCopy()));
      ObjectNode op = run.operation(baselineComplete && limits.isEmpty() ? "SUCCEEDED" : "PARTIAL", "CREDIT_READ_ONLY");
      op.set("result_id", registered.get("result_id")); op.set("result_version", registered.get("version"));
      return CapabilityJobs.skillResult(op, Json.arr("CREDIT"), limits);
    };
  }

  private JsonNode material(CapabilityJobs.Run run, String id) {
    try (Diagnostics.Scope ignored = Diagnostics.scope("file_id", id)) {
      ObjectNode meta = biz.owned("file", id, run.task);
      return run.step("file-" + id + "-" + meta.path("sha256").asText(), "READING_FILES", () -> {
        try { return files.fragments(run.task, id); }
        catch (IOException e) { throw new Fault("FILE_UNAVAILABLE", "无法读取已归档原件", 400, e); }
      });
    }
  }

  private JsonNode extract(CapabilityJobs.Run run, String task, JsonNode fragment, JsonNode input, ArrayNode limits) {
    long started = System.nanoTime();
    try (Diagnostics.Scope ignored = Diagnostics.scope("file_id", fragment.path("source_id").asText(),
        "page", fragment.path("page").asInt(), "model_task", task)) {
      Diagnostics.info("page.started", "mode", fragment.path("text").asText().trim().isEmpty() ? "IMAGE" : "TEXT");
      try {
      JsonNode image = null;
      if (fragment.path("text").asText().trim().isEmpty()) {
        if (!model.visionReady()) {
          Diagnostics.failure("page.skipped", null, "reason", "VISION_NOT_CONFIGURED");
          addLimit(limits, "第" + fragment.path("page").asInt() + "页的视觉文件通道尚未配置"); return null;
        }
        try { image = files.page(run.task, fragment.path("source_id").asText(), fragment.path("page").asInt()); }
        catch (IOException e) { throw new Fault("FILE_UNAVAILABLE", "无法渲染已归档原件", 400, e); }
      }
      JsonNode parsed;
      try { parsed = infer(run, task, input, image); }
      catch (Fault e) {
        if ("LEASE_LOST".equals(e.code)) throw e;
        Diagnostics.failure("page.skipped", e, "reason", "MODEL_RESULT_UNAVAILABLE", "elapsed_ms", Diagnostics.elapsed(started));
        addLimit(limits, "第" + fragment.path("page").asInt() + "页未取得有效提取结果：" + e.getMessage()); return null;
      }
      if ("MISMATCH".equals(parsed.path("subject_match").asText())) throw new Fault("ENTERPRISE_MISMATCH", "材料主体与目标企业不一致");
      if (!"MATCHED".equals(parsed.path("subject_match").asText())) {
        Diagnostics.failure("page.skipped", null, "reason", "SUBJECT_UNKNOWN", "elapsed_ms", Diagnostics.elapsed(started));
        addLimit(limits, "原文尚不能核对企业主体"); return null;
      }
      Diagnostics.info("page.completed", "count", parsed.path("candidates").size() + parsed.path("records").size(),
          "elapsed_ms", Diagnostics.elapsed(started));
      return parsed;
      } catch (RuntimeException error) {
        Diagnostics.failure("page.failed", error, "elapsed_ms", Diagnostics.elapsed(started)); throw error;
      }
    }
  }

  private JsonNode infer(CapabilityJobs.Run run, String task, JsonNode input, JsonNode image) {
    String prompt = resource(task + ".md");
    ObjectNode schema = Json.object(schemas.path(task).deepCopy());
    schema.set("$defs", biz.contracts.schema.get("$defs"));
    String key = "model-" + Json.hash(Json.arr(task, prompt, input, schemas.path(task), image));
    return run.step(key, "MODEL_INFERENCE", () -> {
      JsonNode response = model.infer(task, prompt, input, schema, image);
      biz.contracts.validate(schema, response, "model_output");
      Set<String> refs = new HashSet<>(); for (JsonNode f : input.path("fragments")) refs.add(f.path("evidence_id").asText());
      evidence(response, refs);
      return response;
    });
  }

  private Supplier<ObjectNode> needsInput(CapabilityJobs.Run run, String stage, ArrayNode limits, String message) {
    if (limits.isEmpty()) addLimit(limits, message);
    return () -> CapabilityJobs.skillResult(run.operation("NEEDS_INPUT", stage), CapabilityJobs.dimensions(run.action), limits);
  }

  static ObjectNode classify(JsonNode uploaded, JsonNode baseline) {
    ArrayNode matched = Json.arr(), uploadOnly = Json.arr(), ambiguous = Json.arr(), baselineOnly = Json.arr();
    Set<Integer> used = new HashSet<>(), uncertain = new HashSet<>();
    for (JsonNode row : uploaded) {
      List<Integer> accounts = new ArrayList<>(), hits = new ArrayList<>(); boolean periodAvailable = false;
      for (int i = 0; i < baseline.size(); i++) {
        JsonNode b = baseline.get(i);
        boolean period = Objects.equals(b.get("currency"), row.get("currency")) && Objects.equals(b.get("as_of"), row.get("as_of"));
        periodAvailable |= period;
        if (row.hasNonNull("account") && !row.path("account").asText().isEmpty() && row.get("account").equals(b.get("account"))) {
          accounts.add(i); if (period) hits.add(i);
        }
      }
      if (stable(row) && hits.size() == 1 && stable(baseline.get(hits.get(0))) && !used.contains(hits.get(0))) {
        int i = hits.get(0); used.add(i);
        matched.add(Json.obj("uploaded", row, "baseline", baseline.get(i), "different", !numericEqual(row.get("balance"), baseline.get(i).get("balance"))));
      } else if (!stable(row) || !hits.isEmpty() || !accounts.isEmpty() || (!baseline.isEmpty() && !periodAvailable)) {
        if (!accounts.isEmpty()) uncertain.addAll(accounts); else for (int i = 0; i < baseline.size(); i++) uncertain.add(i);
        ambiguous.add(row);
      } else uploadOnly.add(row);
    }
    for (int i = 0; i < baseline.size(); i++) if (!used.contains(i) && !uncertain.contains(i)) baselineOnly.add(baseline.get(i));
    return Json.obj("matched", matched, "upload_only", uploadOnly, "baseline_only", baselineOnly, "ambiguous", ambiguous);
  }

  private static boolean stable(JsonNode row) {
    String account = row.path("account").asText("");
    return !account.isEmpty() && !account.matches(".*[*×•].*") && !row.path("currency").asText("").isEmpty()
        && !row.path("as_of").asText("").isEmpty() && number(row.get("balance")) != null;
  }

  static BigDecimal number(JsonNode raw) {
    if (raw == null || !raw.isTextual() || raw.asText().length() > 100) return null;
    String text = raw.asText().trim().replace(",", "").replace("，", "");
    if (text.startsWith("(") && text.endsWith(")")) text = "-" + text.substring(1, text.length() - 1);
    try { BigDecimal n = new BigDecimal(text); return Math.abs((long) n.scale()) <= 1000 && n.precision() <= 1000 ? n : null; }
    catch (NumberFormatException e) { return null; }
  }

  static String decimal(JsonNode raw) { BigDecimal n = number(raw); return n == null ? null : n.toPlainString(); }
  private static BigDecimal metricNumber(JsonNode raw) {
    return raw != null && raw.isTextual() ? number(TextNode.valueOf(raw.asText().replace("%", ""))) : null;
  }
  static boolean numericEqual(JsonNode a, JsonNode b) {
    BigDecimal x = number(a), y = number(b);
    return x == null || y == null ? Objects.equals(a, b) : x.compareTo(y) == 0;
  }
  private static JsonNode canonical(JsonNode value) {
    if (!value.isObject()) return value;
    ObjectNode out = Json.obj(); List<String> names = new ArrayList<>(); value.fieldNames().forEachRemaining(names::add);
    Collections.sort(names); for (String name : names) out.set(name, canonical(value.get(name))); return out;
  }
  private static void addLimit(ArrayNode limits, String text) {
    String bounded = text.substring(0, Math.min(text.length(), 500));
    for (JsonNode item : limits) if (bounded.equals(item.asText())) return;
    if (limits.size() < 50) limits.add(bounded);
  }
  private static void addRefs(ArrayNode target, JsonNode input) {
    for (JsonNode ref : input) { boolean found = false; for (JsonNode old : target) found |= old.equals(ref); if (!found) target.add(ref); }
  }
  private static ObjectNode card(JsonNode cards, String dim) {
    for (JsonNode card : cards) if (dim.equals(card.path("dimension").asText())) return (ObjectNode) card;
    throw new Fault("INVALID_ARGUMENT", "缺少所需调查维度");
  }
  private static List<JsonNode> metrics(JsonNode card, String... names) {
    List<JsonNode> result = new ArrayList<>();
    for (String name : names) {
      JsonNode found = Json.obj();
      for (JsonNode field : card.path("metrics")) if (("calculated." + name).equals(field.path("field_key").asText())) found = field;
      result.add(found);
    }
    return result;
  }
  private static ObjectNode riskItem(String code, String status, List<JsonNode> fields) {
    ArrayNode facts = Json.arr(), refs = Json.arr();
    for (JsonNode f : fields) { if (f.hasNonNull("value")) facts.add(Json.obj("name", f.get("label"), "value", f.get("value"))); addRefs(refs, f.path("evidence_refs")); }
    return Json.obj("code", code, "status", status, "facts", facts, "evidence_refs", refs);
  }
  private static void evidence(JsonNode node, Set<String> allowed) {
    if (node.isObject()) node.fields().forEachRemaining(e -> {
      if ("evidence_refs".equals(e.getKey())) for (JsonNode ref : e.getValue()) {
        if (!allowed.contains(ref.asText())) throw new Fault("EVIDENCE_INVALID", "模型引用未提供证据");
      } else evidence(e.getValue(), allowed);
    });
    else if (node.isArray()) node.forEach(x -> evidence(x, allowed));
  }
  private static void collectFieldKeys(JsonNode node, Set<String> out) {
    if (node.isObject()) { node.fieldNames().forEachRemaining(k -> { if (k.startsWith("financial.")) out.add(k); }); if (node.has("field_key")) out.add(node.path("field_key").asText()); node.elements().forEachRemaining(x -> collectFieldKeys(x, out)); }
    else if (node.isArray()) node.forEach(x -> collectFieldKeys(x, out));
  }
  private String resource(String name) {
    try (InputStream in = getClass().getResourceAsStream("/capabilities/" + name)) {
      if (in == null) throw new IOException(); ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    } catch (IOException e) { throw new IllegalStateException("Capability resource missing: " + name); }
  }
}
