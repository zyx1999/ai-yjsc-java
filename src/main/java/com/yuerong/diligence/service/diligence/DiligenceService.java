package com.yuerong.diligence.service.diligence;

import com.yuerong.diligence.common.Fault;
import com.yuerong.diligence.common.Json;
import com.yuerong.diligence.model.diligence.Cards;
import com.yuerong.diligence.model.diligence.Contracts;
import com.yuerong.diligence.repository.ApplicationStorePort;
import com.yuerong.diligence.repository.EnterpriseDataPort;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class DiligenceService {
  public final ApplicationStorePort store;
  public final EnterpriseDataPort source;
  public final Contracts contracts;
  private final RuleService rules;

  public DiligenceService(
      ApplicationStorePort store,
      EnterpriseDataPort source,
      Contracts contracts,
      RuleService rules) {
    this.store = store;
    this.source = source;
    this.contracts = contracts;
    this.rules = rules;
  }

  public ObjectNode session(String task, String user) {
    ObjectNode s = internalSession(task);
    if (!s.path("owner").asText().equals(user)) throw new Fault("FORBIDDEN", "无权访问会话", 403);
    return s;
  }

  /** The internal gateway trusts its network caller; task IDs must already exist. */
  public ObjectNode internalSession(String task) {
    ObjectNode s = store.get("session", task);
    if (s == null || s.path("deleted").asBoolean()) throw new Fault("NOT_FOUND", "会话不存在", 404);
    return s;
  }

  public ObjectNode create(String user) {
    return create(user, Json.id());
  }

  ObjectNode create(String user, String id) {
    ObjectNode s =
        Json.obj(
            "task_id",
            id,
            "owner",
            user,
            "title",
            "新会话",
            "messages",
            Json.arr(),
            "results",
            Json.arr(),
            "bound_subject",
            null,
            "investigation_ready",
            false,
            "platform_sessions",
            Json.obj());
    store.create("session", id, user, s);
    return s;
  }

  public ObjectNode resolve(JsonNode args) {
    contracts.check("ResolveInput", args);
    ObjectNode r = source.resolve(args);
    contracts.check("ResolveData", r);
    return r;
  }

  public ObjectNode resolveForSession(String task, JsonNode args) {
    ObjectNode resolved = resolve(args);
    if ("MATCHED".equals(resolved.path("resolution").asText()))
      bindSubject(task, resolved.get("subject"));
    return resolved;
  }

  public void bindSubject(String task, JsonNode subject) {
    String code = subject.path("credit_code").asText();
    store.update("session", task, s -> {
      String bound = s.path("bound_subject").path("credit_code").asText();
      if (!bound.isEmpty() && !bound.equals(code))
        throw new Fault("ENTERPRISE_MISMATCH", "当前会话已关联另一家企业，请新建会话", 409);
      for (JsonNode ref : s.path("results")) {
        ObjectNode previous = store.get("result", ref.asText());
        if (previous != null && !code.equals(previous.path("current").path("subject").path("credit_code").asText()))
          throw new Fault("ENTERPRISE_MISMATCH", "历史会话已有另一家企业，请新建会话", 409);
      }
      if (bound.isEmpty()) s.set("bound_subject", subject.deepCopy());
      return s;
    });
  }

  public void requireInvestigation(String task, String code) {
    ObjectNode s = store.get("session", task);
    if (s == null || !code.equals(s.path("bound_subject").path("credit_code").asText()))
      throw new Fault("ENTERPRISE_MISMATCH", "请先提供企业名称或统一社会信用代码并生成调查表", 409);
    if (!s.path("investigation_ready").asBoolean())
      throw new Fault("INVESTIGATION_REQUIRED", "请先生成调查表，再使用财报或征信等能力", 409);
  }

  public void markInvestigationReady(String task, ObjectNode operation) {
    if (!"investigation".equals(operation.path("stage").asText())
        && !"INVESTIGATION_SAVED".equals(operation.path("stage").asText())) return;
    if (!("SUCCEEDED".equals(operation.path("status").asText())
        || "PARTIAL".equals(operation.path("status").asText()))) return;
    String resultId = operation.path("result_id").asText();
    if (resultId.isEmpty()) return;
    ObjectNode result = owned("result", resultId, task);
    if (result.path("current").path("dimensions").size() != 7) return;
    store.update("session", task, s -> {
      s.put("investigation_ready", true);
      return s;
    });
  }

  public JsonNode subject(String code) {
    ObjectNode r = resolve(Json.obj("credit_code", code));
    if (!"MATCHED".equals(r.path("resolution").asText()))
      throw new Fault("ENTERPRISE_AMBIGUOUS", "无法唯一确定企业");
    return r.get("subject");
  }

  public void verifySubject(JsonNode s) {
    contracts.check("Subject", s);
    if (!subject(s.path("credit_code").asText()).equals(s))
      throw new Fault("ENTERPRISE_MISMATCH", "企业名称与信用代码不一致");
  }

  public ObjectNode owned(String kind, String id, String task) {
    ObjectNode r = store.get(kind, id);
    if (r == null) throw new Fault("NOT_FOUND", "对象不存在", 404);
    if (!task.equals(r.path("task_id").asText())) throw new Fault("FORBIDDEN", "对象不属于当前会话", 403);
    return r;
  }

  private String resultId(String task, String code) {
    return "result-" + Json.hash(Json.arr(task, code));
  }

  private ObjectNode ensure(String task, JsonNode subject) {
    String id = resultId(task, subject.path("credit_code").asText());
    ObjectNode doc = store.get("result", id);
    if (doc == null) {
      try {
        store.create(
            "result",
            id,
            task,
            Json.obj(
                "task_id",
                task,
                "current",
                Json.obj(
                    "result_id", id, "version", "0", "subject", subject, "dimensions", Json.arr()),
                "versions",
                Json.obj()));
      } catch (Fault e) {
        if (!"VERSION_CONFLICT".equals(e.code)) throw e;
      }
      store.update(
          "session",
          task,
          s -> {
            ArrayNode refs = (ArrayNode) s.get("results");
            boolean found = false;
            for (JsonNode n : refs) found |= id.equals(n.asText());
            if (!found) refs.add(id);
            return s;
          });
      doc = store.get("result", id);
    }
    return doc;
  }

  private ObjectNode saveCard(String task, JsonNode subject, ObjectNode card, String expected) {
    return saveCard(task, subject, card, expected, null);
  }

  private ObjectNode saveCard(
      String task, JsonNode subject, ObjectNode card, String expected, String sourceBaseline) {
    ObjectNode doc = ensure(task, subject);
    String id = doc.path("current").path("result_id").asText();
    ObjectNode updated =
        store.update(
            "result",
            id,
            d -> {
              ObjectNode current = (ObjectNode) d.get("current");
              if (expected != null && !expected.equals(current.path("version").asText()))
                throw new Fault("VERSION_CONFLICT", "结果已变化，请刷新", 409);
              String version =
                  String.valueOf(Integer.parseInt(current.path("version").asText()) + 1);
              card.put("input_version", version);
              ArrayNode cards = (ArrayNode) current.get("dimensions");
              boolean replaced = false;
              for (int i = 0; i < cards.size(); i++)
                if (cards.get(i).path("dimension").equals(card.get("dimension"))) {
                  cards.set(i, card);
                  replaced = true;
                }
              if (!replaced) cards.add(card);
              current.put("version", version);
              contracts.check("Result", current);
              ((ObjectNode) d.get("versions")).set(version, current.deepCopy());
              ObjectNode baselines =
                  d.has("baselines") ? (ObjectNode) d.get("baselines") : Json.obj();
              String previous = String.valueOf(Integer.parseInt(version) - 1);
              ObjectNode perVersion =
                  baselines.has(previous)
                      ? Json.object(baselines.get(previous).deepCopy())
                      : Json.obj();
              if (sourceBaseline != null)
                perVersion.put(card.path("dimension").asText(), sourceBaseline);
              baselines.set(version, perVersion);
              d.set("baselines", baselines);
              return d;
            });
    JsonNode r = updated.get("current");
    return Json.obj("result_id", id, "version", r.get("version"), "subject", subject, "card", card);
  }

  public ObjectNode query(String task, JsonNode args) {
    contracts.check("QueryInput", args);
    if (args.has("period")
        && args.at("/period/start").asText().compareTo(args.at("/period/end").asText()) > 0)
      throw new Fault("INVALID_ARGUMENT", "期间起止顺序无效");
    String code = args.path("credit_code").asText();
    JsonNode subject = subject(code);
    bindSubject(task, subject);
    ObjectNode card;
    String sourceBaseline =
        "FINANCIALS".equals(args.path("dimension").asText()) ? source.baseline(code) : null;
    if (args.has("cursor")) {
      ObjectNode page = owned("cursor", args.path("cursor").asText(), task);
      if (!page.path("binding").equals(binding(args))) throw new Fault("CURSOR_INVALID", "分页条件已变化");
      if (System.currentTimeMillis() > page.path("expires").asLong())
        throw new Fault("CURSOR_INVALID", "查询快照已过期");
      card = Json.object(page.get("card").deepCopy());
      paginate(task, args, card, page.path("offset").asInt());
      card = mergePage(task, subject, card);
    } else {
      card = source.query(code, args.path("dimension").asText());
      filterPeriod(card, args);
      if ("LITIGATION".equals(args.path("topic").asText())) {
        ArrayNode groups = (ArrayNode) card.get("groups");
        for (int i = groups.size() - 1; i >= 0; i--)
          if (!"source.8".equals(groups.get(i).path("group_key").asText())) groups.remove(i);
      }
      rules.apply(card);
      paginate(task, args, card, 0);
    }
    if (sourceBaseline != null && !sourceBaseline.equals(source.baseline(code)))
      throw new Fault("VERSION_CONFLICT", "查询期间源数据变化", 409);
    return saveCard(task, subject, card, null, sourceBaseline);
  }

  private ObjectNode mergePage(String task, JsonNode subject, ObjectNode page) {
    ObjectNode current =
        read(task, Json.obj("result_id", resultId(task, subject.path("credit_code").asText())));
    ObjectNode full = null;
    for (JsonNode c : current.path("dimensions"))
      if (c.path("dimension").equals(page.get("dimension"))) full = Json.object(c.deepCopy());
    if (full == null) return page;
    for (JsonNode incoming : page.path("groups"))
      for (JsonNode existing : full.path("groups"))
        if (existing.path("group_key").equals(incoming.get("group_key"))) {
          LinkedHashMap<String, JsonNode> all = new LinkedHashMap<>();
          for (JsonNode row : existing.path("rows")) all.put(row.path("record_id").asText(), row);
          for (JsonNode row : incoming.path("rows")) all.put(row.path("record_id").asText(), row);
          ArrayNode rows = Json.arr();
          for (JsonNode row : all.values()) rows.add(row);
          ((ObjectNode) existing).set("rows", rows);
          ((ObjectNode) existing).set("next_cursor", incoming.get("next_cursor"));
          ((ObjectNode) existing)
              .put(
                  "complete",
                  incoming.path("next_cursor").isNull()
                      && rows.size() == incoming.path("total_count").asInt(-1));
        }
    Set<String> refs = new HashSet<>();
    for (JsonNode e : full.path("evidence")) refs.add(e.path("evidence_id").asText());
    for (JsonNode e : page.path("evidence"))
      if (refs.add(e.path("evidence_id").asText())) ((ArrayNode) full.get("evidence")).add(e);
    return full;
  }

  private JsonNode binding(JsonNode args) {
    ObjectNode b = Json.object(args.deepCopy());
    b.remove("cursor");
    b.remove("page_size");
    if (!b.has("topic")) b.put("topic", "ALL");
    return b;
  }

  private void filterPeriod(ObjectNode card, JsonNode args) {
    if (!args.has("period")) return;
    for (JsonNode g : card.path("groups")) {
      ArrayNode rows = (ArrayNode) g.get("rows");
      for (int i = rows.size() - 1; i >= 0; i--) {
        ArrayNode fields = (ArrayNode) rows.get(i).get("fields");
        for (int j = fields.size() - 1; j >= 0; j--)
          if (!args.get("period").equals(fields.get(j).at("/context/period"))) fields.remove(j);
        if (fields.isEmpty()) rows.remove(i);
      }
      ((ObjectNode) g).put("total_count", rows.size());
    }
  }

  private void paginate(String task, JsonNode args, ObjectNode card, int offset) {
    ArrayNode groups = (ArrayNode) card.get("groups");
    int size = args.path("page_size").asInt(50);
    Set<String> refs = new HashSet<>();
    for (String section : Arrays.asList("metrics", "summaries"))
      for (JsonNode field : card.path(section))
        for (JsonNode ref : field.path("evidence_refs")) refs.add(ref.asText());
    for (int j = groups.size() - 1; j >= 0; j--) {
      ObjectNode g = (ObjectNode) groups.get(j);
      if (args.has("group_key") && !g.path("group_key").equals(args.get("group_key"))) {
        groups.remove(j);
        continue;
      }
      ArrayNode all = (ArrayNode) g.get("rows");
      int end = Math.min(offset + size, all.size());
      ArrayNode page = Json.arr();
      for (int i = offset; i < end; i++) page.add(all.get(i));
      if (end < all.size()) {
        String cursor = Json.id();
        ObjectNode bound = Json.object(binding(args));
        bound.set("group_key", g.get("group_key"));
        store.create(
            "cursor",
            cursor,
            task,
            Json.obj(
                "task_id",
                task,
                "binding",
                bound,
                "offset",
                end,
                "card",
                card.deepCopy(),
                "expires",
                System.currentTimeMillis() + 900000));
        g.put("next_cursor", cursor);
        g.put("complete", false);
      } else g.putNull("next_cursor");
      g.set("rows", page);
      for (JsonNode row : page)
        for (JsonNode field : row.path("fields"))
          for (JsonNode ref : field.path("evidence_refs")) refs.add(ref.asText());
    }
    ArrayNode evidence = (ArrayNode) card.get("evidence");
    for (int i = evidence.size() - 1; i >= 0; i--)
      if (!refs.contains(evidence.get(i).path("evidence_id").asText())) evidence.remove(i);
  }

  public ObjectNode read(String task, JsonNode args) {
    contracts.check("ReadInput", args);
    ObjectNode d = owned("result", args.path("result_id").asText(), task);
    JsonNode r =
        args.has("version")
            ? d.path("versions").get(args.path("version").asText())
            : d.get("current");
    if (r == null) throw new Fault("NOT_FOUND", "结果版本不存在", 404);
    ObjectNode copy = Json.object(r.deepCopy());
    if (args.has("dimensions")) {
      ArrayNode a = (ArrayNode) copy.get("dimensions");
      for (int i = a.size() - 1; i >= 0; i--) {
        boolean match = false;
        for (JsonNode dim : args.get("dimensions")) match |= dim.equals(a.get(i).get("dimension"));
        if (!match) a.remove(i);
      }
    }
    if (copy.path("dimensions").isEmpty()) throw new Fault("NOT_FOUND", "所选维度尚未生成", 404);
    return copy;
  }

  public ArrayNode results(String task) {
    ArrayNode a = Json.arr();
    for (ObjectNode d : store.list("result", task)) a.add(d.get("current"));
    return a;
  }

  /** Query and compute without publishing a partial new result. The job owns this snapshot. */
  public ObjectNode collectCard(String code, String dimension) {
    ObjectNode card = source.query(code, dimension);
    rules.apply(card);
    return card;
  }

  public String currentVersion(String task, String code) {
    ObjectNode doc = store.get("result", resultId(task, code));
    return doc == null ? "0" : doc.at("/current/version").asText();
  }

  /** Called inside the fenced job transaction: all seven dimensions publish as one version. */
  public ObjectNode publishInvestigation(String task, JsonNode subject, ArrayNode input,
      String expectedVersion, String financialBaseline) {
    if (input.size() != 7) throw new Fault("INVALID_ARGUMENT", "调查表须包含完整七维");
    String code = subject.path("credit_code").asText();
    if (!financialBaseline.equals(source.baseline(code)))
      throw new Fault("VERSION_CONFLICT", "生成期间财务源基准已变化，请重新生成", 409);
    bindSubject(task, subject);
    String id = ensure(task, subject).at("/current/result_id").asText();
    ObjectNode updated = store.update("result", id, d -> {
      if (!expectedVersion.equals(d.at("/current/version").asText()))
        throw new Fault("VERSION_CONFLICT", "生成期间调查表已变化，未覆盖新版本", 409);
      String version = String.valueOf(Long.parseLong(expectedVersion) + 1);
      ArrayNode cards = input.deepCopy();
      Set<String> dimensions = new HashSet<>();
      for (JsonNode node : cards) {
        ObjectNode card = (ObjectNode) node;
        if (!dimensions.add(card.path("dimension").asText()))
          throw new Fault("INVALID_ARGUMENT", "调查维度重复");
        paginate(task, Json.obj("credit_code", code, "dimension", card.get("dimension"), "page_size", 50), card, 0);
        card.put("input_version", version);
      }
      ObjectNode result = Json.obj("result_id", id, "version", version, "subject", subject, "dimensions", cards);
      contracts.check("Result", result);
      d.set("current", result);
      ((ObjectNode) d.get("versions")).set(version, result.deepCopy());
      ObjectNode baselines = d.has("baselines") ? (ObjectNode) d.get("baselines") : Json.obj();
      baselines.set(version, Json.obj("FINANCIALS", financialBaseline));
      d.set("baselines", baselines);
      return d;
    });
    return Json.object(updated.get("current").deepCopy());
  }

  public ObjectNode operation(String task, String id) {
    return Json.object(owned("operation", id, task).get("data").deepCopy());
  }

  public ObjectNode newOperation(String task, String stage) {
    String id = Json.id();
    ObjectNode o =
        Json.obj(
            "operation_id",
            id,
            "status",
            "RUNNING",
            "stage",
            stage,
            "result_id",
            null,
            "result_version",
            null,
            "proposal_id",
            null,
            "file_id",
            null,
            "error",
            null);
    store.create("operation", id, task, Json.obj("task_id", task, "data", o));
    return o;
  }

  public ObjectNode finish(String task, ObjectNode o) {
    contracts.check("Operation", o);
    String id = o.path("operation_id").asText();
    owned("operation", id, task);
    store.update(
        "operation",
        id,
        d -> {
          d.set("data", o);
          return d;
        });
    markInvestigationReady(task, o);
    return o;
  }

  public ObjectNode register(String task, JsonNode args, Set<String> allowedDimensions) {
    contracts.check("AnalysisRegistrationInput", args);
    String dedup = Json.hash(Json.arr(task, args.get("idempotency_key")));
    ObjectNode previous = store.get("analysis-request", dedup);
    if (previous != null) {
      if (!previous.path("digest").asText().equals(Json.hash(args)))
        throw new Fault("IDEMPOTENCY_CONFLICT", "分析请求内容改变", 409);
      return Json.object(previous.get("response").deepCopy());
    }
    String dim = args.path("dimension").asText();
    if (!allowedDimensions.contains(dim)) throw new Fault("FORBIDDEN", "运行无权登记该维度", 403);
    ObjectNode result = read(task, Json.obj("result_id", args.get("result_id")));
    if (!args.path("base_version").equals(result.get("version")))
      throw new Fault("VERSION_CONFLICT", "分析依赖版本已变化", 409);
    ObjectNode card = null;
    for (JsonNode c : result.path("dimensions"))
      if (dim.equals(c.path("dimension").asText())) card = Json.object(c.deepCopy());
    if (card == null) throw new Fault("INVALID_ARGUMENT", "分析维度尚未查询");
    Set<String> evidence = new HashSet<>();
    for (JsonNode e : card.path("evidence")) evidence.add(e.path("evidence_id").asText());
    for (JsonNode e : args.path("evidence_refs"))
      if (!evidence.contains(e.asText())) throw new Fault("EVIDENCE_INVALID", "分析引用未登记证据");
    Set<String> keys = new HashSet<>();
    boolean riskSummary = "ai-risk-v1".equals(args.path("rule_version").asText());
    Set<String> riskKeys = new HashSet<>();
    if (riskSummary) {
      if ("PROFILE".equals(dim)) riskKeys.addAll(Arrays.asList("ai.A01", "ai.A02"));
      else if ("CREDIT".equals(dim)) riskKeys.addAll(Arrays.asList("ai.A03", "ai.A04"));
      else throw new Fault("INVALID_ARGUMENT", "该维度没有登记AI风险总结");
    }
    for (JsonNode f : args.path("summaries")) {
      String key = f.path("field_key").asText();
      if (!(riskSummary ? riskKeys.contains(key) && "AI_SUMMARY".equals(f.path("origin").asText())
              : key.equals(dim.toLowerCase() + ".analysis"))
          || !keys.add(key)
          || !"TEXT".equals(f.path("value_type").asText()))
        throw new Fault("INVALID_ARGUMENT", "分析字段不在受控白名单");
      for (JsonNode e : f.path("evidence_refs"))
        if (!evidence.contains(e.asText())) throw new Fault("EVIDENCE_INVALID", "总结引用未登记证据");
    }
    if (riskSummary && !keys.equals(riskKeys))
      throw new Fault("INVALID_ARGUMENT", "AI风险总结必须完整登记两项");
    if (!riskSummary && !"analysis-v1".equals(args.path("rule_version").asText()))
      throw new Fault("INVALID_ARGUMENT", "未登记规则版本");
    if (riskSummary) {
      ArrayNode merged = Json.arr();
      for (JsonNode old : card.path("summaries"))
        if (!riskKeys.contains(old.path("field_key").asText())) merged.add(old);
      for (JsonNode incoming : args.path("summaries")) merged.add(incoming);
      card.set("summaries", merged);
      ArrayNode limitations = Json.arr();
      Set<String> seen = new HashSet<>();
      for (JsonNode item : card.path("limitations"))
        if (seen.add(item.asText())) limitations.add(item.asText());
      for (JsonNode item : args.path("limitations"))
        if (seen.add(item.asText())) limitations.add(item.asText());
      card.set("limitations", limitations);
    } else {
      card.set("summaries", args.get("summaries"));
      card.set("limitations", args.get("limitations"));
    }
    card.set("analysis_status", args.get("analysis_status"));
    ObjectNode response =
        saveCard(task, result.get("subject"), card, args.path("base_version").asText());
    store.create(
        "analysis-request", dedup, task, Json.obj("digest", Json.hash(args), "response", response));
    return response;
  }

  public ObjectNode financialRefresh(String task, String code) {
    ObjectNode refreshed = query(task, Json.obj("credit_code", code, "dimension", "FINANCIALS"));
    ObjectNode result = read(task, Json.obj("result_id", refreshed.get("result_id")));
    for (JsonNode c : result.path("dimensions"))
      if (!"FINANCIALS".equals(c.path("dimension").asText())
          && "CURRENT".equals(c.path("analysis_status").asText())) {
        ObjectNode stale = Json.object(c.deepCopy());
        stale.put("analysis_status", "STALE");
        refreshed = saveCard(task, result.get("subject"), stale, null);
      }
    return refreshed;
  }

  private String creditLabel(String key) {
    switch (key) {
      case "institution":
        return "授信机构";
      case "account":
        return "账号或合同号";
      case "currency":
        return "币种";
      case "balance":
        return "余额（原文）";
      case "as_of":
        return "报告日期";
      default:
        return key;
    }
  }

  public ObjectNode materials(String task, JsonNode args, JsonNode grant) {
    return materials(task, args, grant, null);
  }

  public ObjectNode materials(String task, JsonNode args, JsonNode grant, ObjectNode sourceCard) {
    if (!"enterprise-credit".equals(grant.path("capability").asText())
        || !"CREDIT".equals(args.path("dimension").asText()))
      throw new Fault("FORBIDDEN", "当前运行只能登记征信材料", 403);
    ObjectNode result = read(task, Json.obj("result_id", args.get("result_id")));
    if (!result.at("/subject/credit_code").equals(grant.get("credit_code")))
      throw new Fault("FORBIDDEN", "企业不匹配", 403);
    if (!args.path("records").isArray() || args.path("records").size() > 10000)
      throw new Fault("INVALID_ARGUMENT", "提取记录数量无效");
    ObjectNode card = null;
    for (JsonNode c : result.path("dimensions"))
      if ("CREDIT".equals(c.path("dimension").asText())) card = Json.object(c.deepCopy());
    if (sourceCard != null) {
      card = sourceCard.deepCopy();
      paginate(task, Json.obj("credit_code", grant.get("credit_code"), "dimension", "CREDIT", "page_size", 50), card, 0);
    }
    if (card == null) throw new Fault("INVALID_ARGUMENT", "征信维度未查询");
    ArrayNode evidence = (ArrayNode) card.get("evidence");
    Set<String> allowed = new HashSet<>();
    for (JsonNode file : args.path("file_ids")) {
      boolean granted = false;
      for (JsonNode f : grant.path("files")) granted |= f.equals(file);
      if (!granted) throw new Fault("FORBIDDEN", "文件不属于运行", 403);
      ObjectNode meta = owned("file", file.asText(), task);
      for (int page = 1; page <= meta.path("pages").asInt(); page++) {
        String id = file.asText() + "-p" + page;
        allowed.add(id);
        evidence.add(
            Json.obj(
                "evidence_id",
                id,
                "source_id",
                file,
                "source_kind",
                "FILE",
                "locator",
                "page:" + page,
                "page",
                page,
                "data_time",
                null));
      }
    }
    ObjectNode group = Cards.group("credit.uploaded", "上传征信提取（只读，未更新源库）");
    ArrayNode rows = Json.arr();
    int index = 0;
    for (JsonNode record : args.path("records")) {
      if (index++ >= 100) break;
      ArrayNode refs = Json.arr();
      for (JsonNode ref : record.path("evidence_refs")) {
        if (!allowed.contains(ref.asText())) throw new Fault("EVIDENCE_INVALID", "提取记录证据无效");
        refs.add(ref);
      }
      if (refs.isEmpty()) throw new Fault("EVIDENCE_INVALID", "提取记录缺少证据");
      ArrayNode fields = Json.arr();
      for (String key : Arrays.asList("institution", "account", "currency", "balance", "as_of")) {
        ObjectNode f =
            Cards.field(
                "credit.extracted." + key,
                creditLabel(key),
                record.path(key).isNull() ? null : record.path(key).asText(),
                Cards.context(),
                false,
                null);
        f.put("origin", "AI_SUMMARY");
        f.set("evidence_refs", refs);
        fields.add(f);
      }
      rows.add(
          Json.obj(
              "record_id",
              "extracted-" + index,
              "subject_name",
              result.at("/subject/enterprise_name"),
              "subject_relation",
              "TARGET",
              "fields",
              fields));
    }
    group.set("rows", rows);
    group.put("complete", args.path("records").size() <= 100);
    group.put("total_count", args.path("records").size());
    group.put("data_status", rows.isEmpty() ? "PARTIAL" : "AVAILABLE");
    group.set("limitations", Json.arr("提取内容仅供核对，不作为库内采用值"));
    ArrayNode groups = (ArrayNode) card.get("groups");
    for (int i = groups.size() - 1; i >= 0; i--)
      if ("credit.uploaded".equals(groups.get(i).path("group_key").asText())) groups.remove(i);
    groups.add(group);
    ArrayNode limits = Json.arr();
    for (JsonNode l : args.path("limitations")) limits.add(l);
    if (args.path("records").size() > 100) limits.add("提取超过100笔，卡片仅展示前100笔；完整结果已登记");
    card.set("limitations", limits);
    card.put("analysis_status", "CURRENT");
    card.put("data_status", "PARTIAL");
    String text = "已提取" + args.path("records").size() + "笔上传记录。";
    JsonNode comparison = args.path("comparison");
    if (comparison.isNull() || comparison.isMissingNode()) text += "尚未完成库内逐笔比对。";
    else
      text +=
          "匹配"
              + comparison.path("matched").size()
              + "笔；仅上传方"
              + comparison.path("upload_only").size()
              + "笔；仅库内"
              + comparison.path("baseline_only").size()
              + "笔；待核实"
              + comparison.path("ambiguous").size()
              + "笔。";
    ObjectNode summary =
        Cards.field("credit.analysis", "征信只读比较", text, Cards.context(), false, null);
    summary.put("origin", "STRUCTURED_SUMMARY");
    card.set("summaries", Json.arr(summary));
    for (int i = groups.size() - 1; i >= 0; i--)
      if (groups.get(i).path("group_key").asText().startsWith("credit.comparison."))
        groups.remove(i);
    if (comparison.isObject())
      for (String category :
          Arrays.asList("matched", "upload_only", "baseline_only", "ambiguous")) {
        String label =
            "matched".equals(category)
                ? "已匹配记录差异"
                : "upload_only".equals(category)
                    ? "仅上传方记录"
                    : "baseline_only".equals(category) ? "仅库内记录" : "待核实记录";
        ObjectNode compared = Cards.group("credit.comparison." + category, label);
        ArrayNode comparedRows = Json.arr();
        int n = 0;
        for (JsonNode item : comparison.path(category)) {
          if (n >= 100) break;
          ArrayNode fields = Json.arr();
          if ("matched".equals(category)) {
            for (String side : Arrays.asList("uploaded", "baseline"))
              for (String key : Arrays.asList("account", "balance", "currency", "as_of")) {
                JsonNode value = item.path(side).path(key);
                ObjectNode field =
                    Cards.field(
                        "credit." + category + "." + side + "." + key,
                        ("uploaded".equals(side) ? "上传方 · " : "库内 · ") + creditLabel(key),
                        value.isNull() ? null : value.asText(),
                        Cards.context(),
                        false,
                        null);
                field.put("origin", "STRUCTURED_SUMMARY");
                fields.add(field);
              }
            ObjectNode diff =
                Cards.field(
                    "credit.matched.difference",
                    "比较结果",
                    item.path("different").asBoolean() ? "余额有差异" : "余额一致",
                    Cards.context(),
                    false,
                    null);
            diff.put("origin", "STRUCTURED_SUMMARY");
            fields.add(diff);
          } else
            for (String key :
                Arrays.asList("institution", "account", "balance", "currency", "as_of")) {
              JsonNode value = item.path(key);
              ObjectNode field =
                  Cards.field(
                      "credit." + category + "." + key,
                      creditLabel(key),
                      value.isNull() ? null : value.asText(),
                      Cards.context(),
                      false,
                      null);
              field.put("origin", "STRUCTURED_SUMMARY");
              fields.add(field);
            }
          comparedRows.add(
              Json.obj(
                  "record_id",
                  category + "-" + (++n),
                  "subject_name",
                  result.at("/subject/enterprise_name"),
                  "subject_relation",
                  "TARGET",
                  "fields",
                  fields));
        }
        compared.set("rows", comparedRows);
        compared.put("total_count", comparison.path(category).size());
        compared.put("complete", comparison.path(category).size() <= 100);
        compared.put("data_status", comparedRows.isEmpty() ? "EMPTY" : "AVAILABLE");
        compared.set("limitations", Json.arr("程序匹配结果，仅供核对，不修改源库"));
        groups.add(compared);
      }
    String materialId = Json.id();
    store.create("material-result", materialId, task, Json.obj("task_id", task, "data", args));
    return saveCard(task, result.get("subject"), card, args.path("base_version").asText());
  }
}
