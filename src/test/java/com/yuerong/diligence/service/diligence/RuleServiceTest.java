package com.yuerong.diligence.service.diligence;

import com.yuerong.diligence.common.Json;
import com.yuerong.diligence.model.diligence.Cards;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class RuleServiceTest {
  private ObjectNode sample(String dimension) throws Exception {
    JsonNode companies = Json.M.readTree(getClass().getResourceAsStream("/adapter/source/demo/seed.json"));
    return Json.object(companies.get(0).path("cards").path(dimension).deepCopy());
  }

  private JsonNode metric(JsonNode card, String key) {
    for (JsonNode f : card.path("metrics"))
      if (key.equals(f.path("field_key").asText())) return f;
    throw new AssertionError("missing metric " + key);
  }

  @Test
  void completeSourceRowsProduceReferenceMetricsAndScopedSummaries() throws Exception {
    RuleService service = new RuleService();
    ObjectNode profile = sample("PROFILE");
    ObjectNode credit = sample("CREDIT");
    ObjectNode operations = sample("OPERATIONS");
    ObjectNode patents = sample("IP");
    service.apply(profile);
    service.apply(credit);
    service.apply(operations);
    service.apply(patents);
    assertEquals("2", metric(profile, "calculated.C01").path("value").asText());
    assertEquals("10.00", metric(profile, "calculated.C04").path("value").asText());
    assertFalse(metric(profile, "calculated.C04").path("evidence_refs").isEmpty());
    assertEquals("900", metric(credit, "calculated.C16").path("value").asText());
    assertEquals("620", metric(credit, "calculated.C17").path("value").asText());
    assertEquals("示例银行甲", metric(credit, "calculated.C21").path("value").asText());
    assertTrue(metric(credit, "calculated.C19").path("value").isNull());
    assertTrue(metric(credit, "calculated.C22").path("value").isNull());
    assertTrue(metric(patents, "calculated.C25").path("value").isNull());
    assertTrue(metric(operations, "calculated.C24").path("value").asText().contains("2025年：A"));
    assertEquals("CURRENT", profile.path("analysis_status").asText());
    assertTrue(profile.path("summaries").size() >= 1);
    assertTrue(credit.path("summaries").get(0).path("value").asText().contains("五类主体完整结论"));
  }

  @Test
  void missingSourceNeverBecomesZero() throws Exception {
    ObjectNode profile = sample("PROFILE");
    ArrayNode groups = (ArrayNode) profile.get("groups");
    for (int i = groups.size() - 1; i >= 0; i--)
      if ("source.2".equals(groups.get(i).path("group_key").asText())) groups.remove(i);
    new RuleService().apply(profile);
    assertTrue(metric(profile, "calculated.C01").path("value").isNull());
  }

  @Test
  void financialRatiosUseSamePeriodAndScope() throws Exception {
    JsonNode company = Json.M.readTree(getClass().getResourceAsStream("/adapter/source/demo/seed.json")).get(0);
    ObjectNode financial = Cards.card("FINANCIALS");
    ObjectNode group = Cards.group("source.16", "财务报表");
    ObjectNode row = Json.obj("record_id", "2025", "subject_name", "测试企业",
        "subject_relation", "TARGET", "fields", Json.arr());
    for (JsonNode v : company.path("values")) {
      if (!"2025-12-31".equals(v.at("/context/period/end").asText())) continue;
      ObjectNode f = Cards.field(v.path("field_key").asText(), v.path("label").asText(),
          v.path("value").asText(), v.get("context"), false, v.path("evidence_id").asText());
      ((ArrayNode) row.get("fields")).add(f);
    }
    ((ArrayNode) group.get("rows")).add(row);
    group.put("data_status", "AVAILABLE");
    group.put("complete", true);
    ((ArrayNode) financial.get("groups")).add(group);
    new RuleService().apply(financial);
    assertEquals("41.41", metric(financial, "financial.ratio.debt_asset").path("value").asText());
    assertEquals("2.23", metric(financial, "financial.ratio.current").path("value").asText());
    assertEquals("CURRENT", financial.path("analysis_status").asText());
    for (JsonNode f : row.path("fields"))
      if ("financial.f137".equals(f.path("field_key").asText()))
        ((ObjectNode) f.path("context")).put("scope", "CONSOLIDATED");
    ObjectNode mismatched = Cards.card("FINANCIALS");
    ((ArrayNode) mismatched.get("groups")).add(group);
    new RuleService().apply(mismatched);
    assertTrue(metric(mismatched, "financial.ratio.current").path("value").isNull());
  }
}
