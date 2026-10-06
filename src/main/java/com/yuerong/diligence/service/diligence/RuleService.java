package com.yuerong.diligence.service.diligence;

import com.yuerong.diligence.common.Json;
import com.yuerong.diligence.model.diligence.Cards;
import com.yuerong.diligence.model.diligence.rules.investigation.DeterministicRules;
import com.yuerong.diligence.model.diligence.rules.investigation.FinancialReferenceRules;
import com.yuerong.diligence.model.diligence.rules.investigation.SourceRuleProjection;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class RuleService {
  private final JsonNode config;

  public RuleService() {
    try {
      config = Json.M.readTree(getClass().getResourceAsStream("/rules/investigation/rules.json"));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public void apply(ObjectNode card) {
    if ("FINANCIALS".equals(card.path("dimension").asText())) {
      FinancialReferenceRules.apply(card);
      return;
    }
    Map<String, DeterministicRules.Domain> domains = SourceRuleProjection.from(card);
    for (JsonNode g : card.path("groups")) {
      String domain = g.path("group_key").asText();
      if (!domain.startsWith("rules.")) continue;
      List<Map<String, Object>> rows = new ArrayList<>();
      for (JsonNode row : g.path("rows")) {
        Map<String, Object> values = new HashMap<>();
        values.put("record_id", row.path("record_id").asText());
        for (JsonNode f : row.path("fields"))
          if (f.hasNonNull("value"))
            values.put(
                f.path("field_key").asText().replaceFirst("^[^.]+[.]", ""),
                f.path("value").asText());
        rows.add(values);
      }
      String status =
          g.path("complete").asBoolean()
              ? ("EMPTY".equals(g.path("data_status").asText())
                  ? "VERIFIED_NONE"
                  : g.path("data_status").asText())
              : "PARTIAL";
      domains.put(domain.substring(6), new DeterministicRules.Domain(status, rows));
    }
    LocalDate asOf = LocalDate.now();
    DeterministicRules.Result computed = new DeterministicRules().evaluate(domains, asOf);
    ArrayNode metrics = (ArrayNode) card.get("metrics");
    int ready = 0;
    for (JsonNode rule : config.path("rules")) {
      if (!rule.path("dimension").equals(card.get("dimension"))) continue;
      String code = rule.path("field_key").asText();
      int number = Integer.parseInt(code.substring(code.lastIndexOf('C') + 1));
      boolean visible = rule.path("approved").asBoolean() || rule.path("show_reference").asBoolean();
      Object value = visible ? computed.values.get(rule.path("engine_key").asText()) : null;
      if (value != null) ready++;
      ObjectNode context = Cards.context();
      context.put("as_of", asOf.toString() + "T00:00:00+08:00");
      String unit = unit(number);
      if (unit != null) context.put("unit", unit);
      ObjectNode f =
          Cards.field(
              code,
              rule.path("label").asText() + (value != null && !rule.path("approved").asBoolean() ? "（参考测算）" : ""),
              value == null ? null : value.toString(),
              context,
              false,
              null);
      f.put("origin", "CALCULATED");
      if (value instanceof Number) f.put("value_type", "DECIMAL");
      if (value instanceof Boolean) {
        f.put("value_type", "BOOLEAN");
        f.put("value", (Boolean) value);
      }
      f.set("evidence_refs", evidence(card, number));
      if (value == null) f.put("missing_reason", reason(number, visible));
      metrics.add(f);
    }
    ArrayNode summaries = (ArrayNode) card.get("summaries");
    for (Map<String, Object> job : computed.template_jobs) {
      if (!"READY".equals(job.get("status"))) continue;
      String field = (String) job.get("field_code");
      int row = Integer.parseInt(field.substring(field.lastIndexOf('r') + 1));
      if (!summaryDimension(row).equals(card.path("dimension").asText())) continue;
      String text = (String) job.get("template");
      @SuppressWarnings("unchecked")
      Map<String, Object> params = (Map<String, Object>) job.get("params");
      for (Map.Entry<String, Object> p : params.entrySet())
        text = text.replace("{" + p.getKey() + "}", String.valueOf(p.getValue()));
      ObjectNode summary = Cards.field("summary.S0" + (row == 243 ? 4 : row - 229),
          summaryLabel(row) + "（参考）", text, Cards.context(), false, null);
      summary.put("origin", "STRUCTURED_SUMMARY");
      summary.set("evidence_refs", evidence(card, row == 230 ? 1 : row == 231 ? 8 : row == 232 ? 3 : 16));
      summaries.add(summary);
    }
    if ("CREDIT".equals(card.path("dimension").asText())
        && summaries.isEmpty()
        && "CURRENT".equals(computed.statuses.get(DeterministicRules.code(235)))) {
      String text = "当前企业融资明细中的贷款余额合计" + computed.values.get(DeterministicRules.code(235))
          + "万元；企业主、配偶、股东及关联企业的全口径融资和担保资料尚未覆盖，不能形成五类主体完整结论。";
      ObjectNode partial = Cards.field("summary.S04", "融资与对外担保情况（已覆盖部分）",
          text, Cards.context(), false, null);
      partial.put("origin", "STRUCTURED_SUMMARY");
      partial.set("evidence_refs", evidence(card, 16));
      summaries.add(partial);
    }
    for (Map<String, Object> signal : computed.reference_signals) {
      @SuppressWarnings("unchecked")
      List<String> deps = (List<String>) signal.get("depends_on");
      int row = Integer.parseInt(deps.get(0).substring(deps.get(0).lastIndexOf('r') + 1));
      String dimension = row >= 222 && row <= 229 || row >= 215 && row <= 221
          ? "PROFILE" : "CREDIT";
      if (!dimension.equals(card.path("dimension").asText())) continue;
      ObjectNode notice = Cards.field("reference." + signal.get("rule_code"),
          "参考风险提示（待业务确认）", (String) signal.get("message"),
          Cards.context(), false, null);
      notice.put("origin", "STRUCTURED_SUMMARY");
      notice.set("evidence_refs", row == 245 ? evidenceGroup(card, "source.7")
          : evidence(card, row >= 222 && row <= 229 ? 8 : row >= 215 && row <= 221 ? 3 : 16));
      summaries.add(notice);
    }
    if ("PROFILE".equals(card.path("dimension").asText())) {
      if (current(computed, 222, 223, 224, 225, 226)
          && !hasSignal(computed, "CHANGE_COUNT", "LEGAL_CHANGE", "EXEC_CHANGE", "ADDRESS_CHANGE", "EQUITY_CHANGE"))
        referenceNote(summaries, "reference.A01", "工商变更参考分析",
            "已计算的近一年工商变更指标未触发旧版参考阈值；阈值待业务确认，不能据此认定无风险。",
            evidence(card, 8));
      if (current(computed, 218, 220, 221)
          && !hasSignal(computed, "PLEDGE_25", "PLEDGE_75", "PLEDGEE_50"))
        referenceNote(summaries, "reference.A02", "股权出质参考分析",
            "已计算的股权出质指标未触发旧版参考阈值；出质比例口径和阈值仍待业务确认。",
            evidence(card, 3));
    }
    if (ready > 0) ((ArrayNode) card.get("limitations")).add("已展示基于完整明细的参考测算；原业务阈值、缺失映射和争议口径仍待确认，不构成授信判断");
    if (!summaries.isEmpty()) card.put("analysis_status", "CURRENT");
  }

  private String summaryDimension(int row) {
    return row == 243 ? "CREDIT" : "PROFILE";
  }

  private boolean current(DeterministicRules.Result result, int... rows) {
    for (int row : rows)
      if (!"CURRENT".equals(result.statuses.get(DeterministicRules.code(row)))) return false;
    return true;
  }

  private boolean hasSignal(DeterministicRules.Result result, String... codes) {
    Set<String> expected = new HashSet<>();
    for (String code : codes) expected.add("REFERENCE_" + code);
    for (Map<String, Object> signal : result.reference_signals)
      if (expected.contains(signal.get("rule_code"))) return true;
    return false;
  }

  private void referenceNote(ArrayNode summaries, String key, String label, String value, ArrayNode refs) {
    ObjectNode note = Cards.field(key, label, value, Cards.context(), false, null);
    note.put("origin", "STRUCTURED_SUMMARY");
    note.set("evidence_refs", refs);
    summaries.add(note);
  }

  private String summaryLabel(int row) {
    switch (row) {
      case 230: return "股权分布情况汇总";
      case 231: return "工商信息变更情况汇总";
      case 232: return "股权出质情况汇总";
      default: return "融资与对外担保情况汇总";
    }
  }

  private String unit(int number) {
    if (Arrays.asList(4, 7, 18, 20).contains(number)) return "%";
    if (Arrays.asList(16, 17, 19, 23).contains(number)) return "万元";
    return null;
  }

  private String reason(int number, boolean visible) {
    if (number == 19 || number == 20) return "缺少逐笔担保方式，混合担保口径待确认";
    if (number == 22 || number == 23) return "风险分类不能代替逾期或不良事实，判定依据待确认";
    if (number == 25) return "缺少按专利号关联的有效质押登记及覆盖状态";
    if (!visible) return "业务口径或输入映射待确认";
    return "缺少完整、可对齐的来源数据或该比例不适用";
  }

  private ArrayNode evidence(ObjectNode card, int number) {
    Set<String> groups = new HashSet<>();
    if (number <= 2) groups.add("source.2");
    else if (number <= 7) groups.addAll(Arrays.asList("source.2", "source.5"));
    else if (number <= 15) groups.add("source.4");
    else if (number <= 23) groups.add("source.6");
    else if (number == 24) groups.add("source.13");
    else groups.add("source.20");
    Set<String> ids = new LinkedHashSet<>();
    for (JsonNode group : card.path("groups")) {
      if (!groups.contains(group.path("group_key").asText())) continue;
      for (JsonNode row : group.path("rows"))
        for (JsonNode f : row.path("fields"))
          for (JsonNode id : f.path("evidence_refs"))
            if (ids.size() < 50) ids.add(id.asText());
    }
    ArrayNode out = Json.arr();
    for (String id : ids) out.add(id);
    return out;
  }

  private ArrayNode evidenceGroup(ObjectNode card, String key) {
    Set<String> ids = new LinkedHashSet<>();
    for (JsonNode group : card.path("groups")) {
      if (!key.equals(group.path("group_key").asText())) continue;
      for (JsonNode row : group.path("rows"))
        for (JsonNode f : row.path("fields"))
          for (JsonNode id : f.path("evidence_refs"))
            if (ids.size() < 50) ids.add(id.asText());
    }
    ArrayNode out = Json.arr();
    for (String id : ids) out.add(id);
    return out;
  }
}
