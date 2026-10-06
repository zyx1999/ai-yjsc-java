package com.yuerong.diligence.model.diligence.rules.investigation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Converts complete source groups into the narrow inputs accepted by deterministic rules. */
public final class SourceRuleProjection {
  private SourceRuleProjection() {}

  public static Map<String, DeterministicRules.Domain> from(JsonNode card) {
    Map<String, DeterministicRules.Domain> domains = new HashMap<>();
    for (JsonNode group : card.path("groups")) {
      String key = group.path("group_key").asText();
      String domain = domain(key);
      if (domain == null) continue;
      String status = !group.path("complete").asBoolean()
          ? "PARTIAL"
          : "EMPTY".equals(group.path("data_status").asText())
              ? "VERIFIED_NONE"
              : group.path("data_status").asText();
      List<Map<String, Object>> records = new ArrayList<>();
      for (JsonNode row : group.path("rows")) records.add(record(key, row));
      domains.put(domain, new DeterministicRules.Domain(status, records));
    }
    return domains;
  }

  private static String domain(String key) {
    switch (key) {
      case "source.2": return "shareholders";
      case "source.4": return "changes";
      case "source.5": return "pledges";
      case "source.6": return "loans";
      case "source.13": return "tax_ratings";
      default: return null;
    }
  }

  private static Map<String, Object> record(String group, JsonNode row) {
    Map<String, Object> r = new HashMap<>();
    r.put("record_id", row.path("record_id").asText());
    switch (group) {
      case "source.2":
        copy(r, "name", row, "shareholder_name", "股东名称");
        String percent = value(row, "shareholder_percent", "持股比例");
        if (percent != null && percent.endsWith("%"))
          r.put("percent", percent.substring(0, percent.length() - 1).trim());
        copyAmount(r, "capital", row, "subscribed_capital", "认缴出资");
        String historical = value(row, "historical", "是否历史股东");
        // The demo fixture contains only current shareholders. Formal mappings must provide the flag.
        if (historical == null && demo(row)) historical = "0";
        if ("否".equals(historical)) historical = "0";
        if ("是".equals(historical)) historical = "1";
        if (historical != null) r.put("historical", historical);
        break;
      case "source.4":
        copy(r, "date", row, "change_date", "变更日期");
        String matter = value(row, "change_type", "变更事项");
        if (matter != null) {
          r.put("matter", matter);
          r.put("category", category(matter));
        }
        copy(r, "before", row, "before_content", "变更前");
        copy(r, "after", row, "after_content", "变更后");
        break;
      case "source.5":
        copy(r, "pledgor", row, "pledgor", "出质人");
        copy(r, "pledgee", row, "pledgee", "质权人");
        copyAmount(r, "amount", row, "pledgor_amount", "出质股权数额");
        copy(r, "status", row, "status_code", "登记状态");
        break;
      case "source.6":
        r.put("role", "ENTERPRISE");
        copy(r, "institution", row, "institution", "融资机构");
        copy(r, "product", row, "product", "融资品种");
        copyAmount(r, "balance", row, "balance", "贷款余额");
        copy(r, "guarantee", row, "guarantee", "担保方式");
        copy(r, "risk_class", row, "risk_class", "五级分类");
        copy(r, "bank_group", row, "bank_group", "金融机构标准代码");
        break;
      case "source.13":
        copy(r, "year", row, "year", "所属年度");
        copy(r, "grade", row, "tax_rating", "纳税信用等级");
        break;
      default: break;
    }
    return r;
  }

  private static boolean demo(JsonNode row) {
    for (JsonNode f : row.path("fields"))
      if (f.path("field_key").asText().startsWith("demo.")) return true;
    return false;
  }

  private static String category(String value) {
    if (value.contains("法定代表人")) return "legalRep";
    if (value.contains("高管")) return "executive";
    if (value.contains("注册地址")) return "address";
    if (value.contains("股权")) return "equity";
    if (value.contains("实缴资本")) return "paidCapital";
    if ("注册资本变更".equals(value)) return "other";
    return "UNKNOWN";
  }

  private static void copy(Map<String, Object> target, String name, JsonNode row, String key, String label) {
    String value = value(row, key, label);
    if (value != null) target.put(name, value);
  }

  private static void copyAmount(Map<String, Object> target, String name, JsonNode row, String key, String label) {
    String raw = value(row, key, label);
    if (raw == null) return;
    String number = raw.replace(",", "").replace(" ", "").replace("人民币", "");
    if (number.endsWith("万元")) {
      target.put(name, number.substring(0, number.length() - 2));
      target.put("unit", "CNY_10K");
    } else if (number.matches("[0-9]+(?:\\.[0-9]+)?")) {
      target.put(name, number);
      String unit = value(row, "unit", "金额单位");
      if (unit != null) target.put("unit", unit);
    }
  }

  private static String value(JsonNode row, String key, String label) {
    for (JsonNode field : row.path("fields")) {
      String id = field.path("field_key").asText();
      if (!key.equals(id) && !id.endsWith("." + key) && !label.equals(field.path("label").asText())) continue;
      String value = field.path("value").asText("").trim();
      return value.isEmpty() ? null : value;
    }
    return null;
  }
}
