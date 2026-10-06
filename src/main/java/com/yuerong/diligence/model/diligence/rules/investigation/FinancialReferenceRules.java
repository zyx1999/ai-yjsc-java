package com.yuerong.diligence.model.diligence.rules.investigation;

import com.yuerong.diligence.common.Json;
import com.yuerong.diligence.model.diligence.Cards;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Narrow, period-bound reference ratios; disputed profitability formulas remain uncalculated. */
public final class FinancialReferenceRules {
  private FinancialReferenceRules() {}

  public static void apply(ObjectNode card) {
    if (!"FINANCIALS".equals(card.path("dimension").asText())) return;
    JsonNode group = null;
    for (JsonNode candidate : card.path("groups"))
      if ("source.16".equals(candidate.path("group_key").asText())) group = candidate;
    ArrayNode metrics = (ArrayNode) card.get("metrics");
    JsonNode latest = null;
    boolean ambiguous = false;
    if (group != null && group.path("complete").asBoolean()
        && "AVAILABLE".equals(group.path("data_status").asText()))
      for (JsonNode row : group.path("rows")) {
        String end = periodEnd(row);
        if (end.isEmpty()) continue;
        if (latest == null || end.compareTo(periodEnd(latest)) > 0) {
          latest = row;
          ambiguous = false;
        } else if (end.equals(periodEnd(latest))) ambiguous = true;
      }
    if (ambiguous) latest = null;
    BigDecimal debt = amount(latest, "financial.f136");
    BigDecimal assets = amount(latest, "financial.f109");
    BigDecimal currentAssets = amount(latest, "financial.f110");
    BigDecimal currentDebt = amount(latest, "financial.f137");
    BigDecimal leverage = compatible(latest, "financial.f136", "financial.f109")
        ? divide(debt, assets, true) : null;
    BigDecimal liquidity = compatible(latest, "financial.f110", "financial.f137")
        ? divide(currentAssets, currentDebt, false) : null;
    add(metrics, "financial.ratio.debt_asset", "资产负债率（参考测算）", leverage,
        "%", latest, "缺少同期间、同口径的总负债和总资产，或总资产非正");
    add(metrics, "financial.ratio.current", "流动比率（参考测算）", liquidity,
        "倍", latest, "缺少同期间、同口径的流动资产和流动负债，或流动负债非正");
    if (leverage != null || liquidity != null) {
      String text = periodEnd(latest) + "财报："
          + (leverage == null ? "资产负债率未能计算" : "资产负债率" + leverage + "%")
          + "，" + (liquidity == null ? "流动比率未能计算" : "流动比率" + liquidity + "倍")
          + "。以上为同期间报表数值的参考计算；未采用行业标准或据此作授信判断。";
      ObjectNode summary = Cards.field("financial.reference.solvency", "偿债指标说明（参考）",
          text, context(latest, null), false, null);
      summary.put("origin", "STRUCTURED_SUMMARY");
      summary.set("evidence_refs", refs(latest));
      ((ArrayNode) card.get("summaries")).add(summary);
      card.put("analysis_status", "CURRENT");
      ((ArrayNode) card.get("limitations")).add("财务比率为参考计算；速动比率、周转率、收益率及营运资金测算仍待确认口径和所需期间数据");
    }
  }

  private static String periodEnd(JsonNode row) {
    if (row == null) return "";
    for (JsonNode f : row.path("fields"))
      if (f.path("context").path("period").hasNonNull("end"))
        return f.path("context").path("period").path("end").asText();
    return "";
  }

  private static BigDecimal amount(JsonNode row, String key) {
    if (row == null) return null;
    JsonNode baseline = null;
    for (JsonNode f : row.path("fields")) {
      if (!key.equals(f.path("field_key").asText())) continue;
      if (baseline == null) baseline = f;
      else return null;
    }
    if (baseline == null || baseline.path("value").isNull()) return null;
    JsonNode c = baseline.path("context");
    if (periodEnd(row).isEmpty() || !periodEnd(row).equals(c.path("period").path("end").asText())
        || !"CNY".equals(c.path("currency").asText())
        || !"万元".equals(c.path("unit").asText())
        || !Arrays.asList("STANDALONE", "CONSOLIDATED").contains(c.path("scope").asText())) return null;
    try { return new BigDecimal(baseline.path("value").asText()); }
    catch (NumberFormatException e) { return null; }
  }

  private static boolean compatible(JsonNode row, String first, String second) {
    JsonNode a = field(row, first), b = field(row, second);
    if (a == null || b == null) return false;
    JsonNode ac = a.path("context"), bc = b.path("context");
    return ac.path("period").equals(bc.path("period"))
        && ac.path("scope").equals(bc.path("scope"))
        && ac.path("currency").equals(bc.path("currency"))
        && ac.path("unit").equals(bc.path("unit"));
  }

  private static JsonNode field(JsonNode row, String key) {
    if (row == null) return null;
    JsonNode found = null;
    for (JsonNode f : row.path("fields")) if (key.equals(f.path("field_key").asText())) {
      if (found != null) return null;
      found = f;
    }
    return found;
  }

  private static BigDecimal divide(BigDecimal numerator, BigDecimal denominator, boolean percent) {
    if (numerator == null || denominator == null || denominator.signum() <= 0) return null;
    return numerator.multiply(percent ? BigDecimal.valueOf(100) : BigDecimal.ONE)
        .divide(denominator, 2, RoundingMode.HALF_UP);
  }

  private static ObjectNode context(JsonNode row, String unit) {
    ObjectNode c = Cards.context();
    if (row != null) for (JsonNode f : row.path("fields")) {
      JsonNode source = f.path("context");
      if (source.hasNonNull("period")) {
        c.set("period", source.get("period"));
        c.set("scope", source.get("scope"));
        c.set("as_of", source.get("as_of"));
        break;
      }
    }
    if (unit != null) c.put("unit", unit);
    return c;
  }

  private static ArrayNode refs(JsonNode row) {
    Set<String> ids = new LinkedHashSet<>();
    if (row != null) for (JsonNode f : row.path("fields"))
      for (JsonNode id : f.path("evidence_refs"))
        if (ids.size() < 50) ids.add(id.asText());
    ArrayNode result = Json.arr();
    for (String id : ids) result.add(id);
    return result;
  }

  private static void add(ArrayNode metrics, String key, String label, BigDecimal value,
      String unit, JsonNode row, String missing) {
    ObjectNode metric = Cards.field(key, label, value == null ? null : value.toPlainString(),
        context(row, unit), false, null);
    metric.put("origin", "CALCULATED");
    metric.put("value_type", "DECIMAL");
    metric.set("evidence_refs", refs(row));
    if (value == null) metric.put("missing_reason", missing);
    metrics.add(metric);
  }
}
