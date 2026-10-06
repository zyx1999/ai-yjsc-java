package com.yuerong.diligence.service.diligence;

import com.yuerong.diligence.common.Fault;
import com.yuerong.diligence.common.Json;
import com.yuerong.diligence.model.diligence.Cards;
import com.yuerong.diligence.model.diligence.Contracts;
import com.yuerong.diligence.repository.source.mysql.MysqlSourceAdapter;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MysqlSourceMappingTest {
  @Test void bundledFieldsFollowCardContract() throws Exception {
    JsonNode mapping = Json.M.readTree(getClass().getResourceAsStream(
        "/adapter/source/mysql/mapping.yuerong.json"));
    Contracts contracts = new Contracts();
    for (JsonNode group : mapping.path("groups")) for (JsonNode f : group.path("fields")) {
      com.fasterxml.jackson.databind.node.ObjectNode field = Cards.field(
          f.path("field_key").asText(), f.path("label").asText(), null,
          Cards.context(), false, "fixture-evidence");
      field.set("value_type", f.get("value_type"));
      contracts.check("Field", field);
    }
  }

  @Test void bundledMappingUsesBusinessTablesAndDeduplicatesIdenticalSubjects() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    Map<String,Object> row = new HashMap<>();
    row.put("credit_code", "91310000MA00000001"); row.put("enterprise_name", "合成企业");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(Arrays.asList(row, row));
    MysqlSourceAdapter adapter = new MysqlSourceAdapter(jdbc,
        "classpath:adapter/source/mysql/mapping.yuerong.json");
    JsonNode result = adapter.resolve(Json.obj("credit_code", "91310000MA00000001"));
    assertEquals("MATCHED", result.path("resolution").asText());
    assertEquals("合成企业", result.at("/subject/enterprise_name").asText());
    assertTrue(result.path("candidates").isEmpty());
    verify(jdbc).queryForList(contains("m_gud_mid_yer_phdc_corp_basic_sj"), anyMap());
  }

  @Test void keepsRowContextEvidenceAndMissingGroupsReadOnly() throws Exception {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    Map<String,Object> row = new HashMap<>();
    row.put("record_id","r1"); row.put("enterprise_name","合成企业"); row.put("amount","12.30");
    row.put("date","2025-12-31T00:00:00+08:00"); row.put("source_time","2026-01-01T00:00:00+08:00");
    when(jdbc.queryForList(eq("fixture"), anyMap())).thenReturn(Arrays.asList(row));
    Path p=Files.createTempFile("source-mapping-", ".json");
    try {
      Files.write(p,Json.obj("groups",Json.arr(
          Json.obj("dimension","FINANCIALS","group_key","source.16","title","财报","sql","fixture",
            "data_time_column","source_time","context_columns",Json.obj("as_of","date"),
            "limitations",Json.arr("口径待确认"),"fields",Json.arr(Json.obj("column","amount","field_key","financial.f109","label","资产","value_type","DECIMAL","context",Json.obj("scope","UNKNOWN")))),
          Json.obj("dimension","FINANCIALS","group_key","missing","title","缺失来源","sql",null)))
          .toString().getBytes("UTF-8"));
      MysqlSourceAdapter a=new MysqlSourceAdapter(jdbc,p.toString()); JsonNode card=a.query("fixture","FINANCIALS");
      assertEquals("PARTIAL",card.path("data_status").asText());
      new Contracts().check("Card", card);
      assertEquals("2025-12-31T00:00:00+08:00",card.at("/groups/0/rows/0/fields/0/context/as_of").asText());
      assertTrue(card.at("/groups/0/rows/0/fields/0/context/unit").isNull());
      assertEquals("UNKNOWN",card.at("/groups/0/rows/0/fields/0/context/scope").asText());
      assertEquals("2026-01-01T00:00:00+08:00",card.at("/evidence/0/data_time").asText());
      assertEquals("口径待确认",card.at("/limitations/0").asText());
      assertFalse(a.writable("FINANCIALS","financial.f109"));
      row.remove("amount");
      assertEquals("SOURCE_MAPPING_INVALID",assertThrows(Fault.class,()->a.query("fixture","FINANCIALS")).code);
    } finally { Files.deleteIfExists(p); }
  }
}
