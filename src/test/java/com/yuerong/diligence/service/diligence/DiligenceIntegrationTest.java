package com.yuerong.diligence.service.diligence;

import com.yuerong.diligence.common.Fault;
import com.yuerong.diligence.common.Json;
import com.yuerong.diligence.model.diligence.Cards;
import com.yuerong.diligence.model.diligence.Contracts;
import com.yuerong.diligence.web.security.Access;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DiligenceIntegrationTest {
  @Autowired DiligenceService biz;
  @Autowired FinancialService financial;
  @Autowired FilesService files;
  @Autowired Contracts contracts;
  @Autowired Access access;
  @Autowired MockMvc mvc;
  final String code = "91310000MA00000001";

  String session() {
    return biz.create("test-user").path("task_id").asText();
  }

  @Test
  void sessionsFollowLastUseAndExcludeDeleted() throws Exception {
    String first = session();
    String second = session();
    JsonNode created = Json.parse(mvc.perform(get("/api/v1/diligence/sessions"))
        .andReturn().getResponse().getContentAsString()).path("data");
    assertTrue(indexOfSession(created, second) < indexOfSession(created, first));

    biz.store.update("session", first, value -> {
      value.put("title", "继续调查");
      return value;
    });
    JsonNode updated = Json.parse(mvc.perform(get("/api/v1/diligence/sessions"))
        .andReturn().getResponse().getContentAsString()).path("data");
    assertTrue(indexOfSession(updated, first) < indexOfSession(updated, second));
    assertFalse(updated.get(indexOfSession(updated, first)).path("last_used_at").asText().isEmpty());

    mvc.perform(delete("/api/v1/diligence/sessions/" + first)).andExpect(status().isOk());
    JsonNode remaining = Json.parse(mvc.perform(get("/api/v1/diligence/sessions"))
        .andReturn().getResponse().getContentAsString()).path("data");
    assertEquals(-1, indexOfSession(remaining, first));
  }

  private int indexOfSession(JsonNode sessions, String id) {
    for (int i = 0; i < sessions.size(); i++)
      if (id.equals(sessions.get(i).path("task_id").asText())) return i;
    return -1;
  }

  @Test
  void nameKeywordResolvesOnlyWhenUniqueAndSkillAcceptsCodeOnly() {
    JsonNode unique = biz.resolve(Json.obj("enterprise_name", "粤新"));
    assertEquals("MATCHED", unique.path("resolution").asText());
    assertEquals(code, unique.at("/subject/credit_code").asText());
    JsonNode ambiguous = biz.resolve(Json.obj("enterprise_name", "有限公司"));
    assertEquals("AMBIGUOUS", ambiguous.path("resolution").asText());
    assertEquals(2, ambiguous.path("candidates").size());
    contracts.check("InvestigationOperationInput", Json.obj("credit_code", code));
    assertThrows(Fault.class, () -> contracts.check("InvestigationOperationInput",
        Json.obj("credit_code", code, "subject", unique.path("subject"))));
  }

  @Test
  void obsoleteInternalEndpointsAreRemoved() throws Exception {
    mvc.perform(post("/internal/diligence/enterprise/resolve").contentType("application/json").content("{}"))
        .andExpect(status().isNotFound());
    mvc.perform(post("/internal/diligence/runs/start").contentType("application/json").content("{}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void sevenDimensionsReuseAndKeepVersions() {
    String task = session();
    String resultId = null;
    for (JsonNode d : contracts.schema.path("x-dimensions")) {
      ObjectNode r = biz.query(task, Json.obj("credit_code", code, "dimension", d.get("code")));
      contracts.check("QueryData", r);
      if (resultId == null) resultId = r.path("result_id").asText();
      assertEquals(resultId, r.path("result_id").asText());
    }
    ObjectNode saved = biz.read(task, Json.obj("result_id", resultId));
    assertEquals(7, saved.path("dimensions").size());
    assertEquals(
        1,
        biz.read(task, Json.obj("result_id", resultId, "version", "1")).path("dimensions").size());
    assertEquals("ENTERPRISE_MISMATCH", assertThrows(Fault.class, () ->
        biz.query(task, Json.obj("credit_code", "91310000MA00000002", "dimension", "PROFILE"))).code);
    assertEquals(1, biz.results(task).size());
    final String rid = resultId;
    assertEquals(
        "FORBIDDEN",
        assertThrows(Fault.class, () -> biz.read(session(), Json.obj("result_id", rid))).code);
  }

  @Test
  void aiRiskRegistrationPreservesStructuredSummaryAndRejectsUnknownFields() {
    String task = session();
    ObjectNode queried = biz.query(task, Json.obj("credit_code", code, "dimension", "PROFILE"));
    String resultId = queried.path("result_id").asText();
    ObjectNode before = biz.read(task, Json.obj("result_id", resultId));
    JsonNode card = before.path("dimensions").get(0);
    int previous = card.path("summaries").size();
    String evidence = card.path("evidence").get(0).path("evidence_id").asText();
    ArrayNode summaries = Json.arr();
    for (String key : new String[] {"ai.A01", "ai.A02"}) {
      ObjectNode field = Cards.field(key, "参考风险解释", "根据已登记事实需要核实。",
          Cards.context(), false, null);
      field.put("origin", "AI_SUMMARY");
      field.set("evidence_refs", Json.arr(evidence));
      summaries.add(field);
    }
    ObjectNode input = Json.obj("result_id", resultId, "base_version", before.get("version"),
        "dimension", "PROFILE", "rule_version", "ai-risk-v1", "summaries", summaries,
        "evidence_refs", Json.arr(evidence), "analysis_status", "CURRENT",
        "limitations", Json.arr(), "idempotency_key", "ai-test-" + task);
    ObjectNode registered = biz.register(task, input, java.util.Collections.singleton("PROFILE"));
    JsonNode after = biz.read(task, Json.obj("result_id", resultId)).path("dimensions").get(0);
    assertEquals(previous + 2, after.path("summaries").size());
    assertEquals("ai.A01", after.path("summaries").get(previous).path("field_key").asText());
    assertEquals(registered, biz.register(task, input, java.util.Collections.singleton("PROFILE")));
    ((ObjectNode) input.path("summaries").get(0)).put("field_key", "ai.A99");
    input.set("base_version", registered.get("version"));
    input.put("idempotency_key", "ai-bad-" + task);
    assertEquals("INVALID_ARGUMENT", assertThrows(Fault.class, () ->
        biz.register(task, input, java.util.Collections.singleton("PROFILE"))).code);
  }

  @Test
  void schemaRejectsSqlBadTopicsAndBadDates() {
    assertThrows(
        Fault.class,
        () ->
            contracts.check(
                "InvestigationOperationInput",
                Json.obj(
                    "subject",
                    biz.subject(code),
                    "dimensions",
                    Json.arr("PROFILE"))));
    assertThrows(
        Fault.class,
        () ->
            contracts.check(
                "QueryInput",
                Json.obj("credit_code", code, "dimension", "PROFILE", "topic", "PATENTS")));
    assertThrows(
        Fault.class,
        () ->
            contracts.check(
                "QueryInput",
                Json.obj("credit_code", code, "dimension", "PROFILE", "sql", "select 1")));
    assertThrows(Fault.class, () -> contracts.check("ResolveInput", Json.obj()));
    assertThrows(
        Fault.class,
        () -> contracts.check("Period", Json.obj("start", "2026-13-01", "end", "2026-12-01")));
  }

  @Test
  void oldEndpointsGoneAndSessionCannotBeSpoofed() throws Exception {
    mvc.perform(get("/api/v1/tasks")).andExpect(status().isNotFound());
    String task = session();
    mvc.perform(get("/api/v1/diligence/sessions/" + task).principal(() -> "other-user"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/internal/diligence/data/query")
                .contentType("application/json")
                .content(
                    Json.obj(
                            "task_id",
                            task,
                            "arguments",
                            Json.obj("credit_code", code, "dimension", "PROFILE"))
                        .toString()))
        .andExpect(status().isNotFound());
  }

  @Test
  void publicQueryCannotExecuteOneDimension() throws Exception {
    String task = session();
    biz.bindSubject(task, biz.subject(code));
    ObjectNode base = null;
    for (JsonNode d : contracts.schema.path("x-dimensions"))
      base = biz.query(task, Json.obj("credit_code", code, "dimension", d.get("code")));
    ObjectNode investigation = biz.newOperation(task, "investigation");
    investigation.put("status", "SUCCEEDED");
    investigation.put("stage", "INVESTIGATION_SAVED");
    investigation.set("result_id", base.get("result_id"));
    investigation.set("result_version", base.get("version"));
    biz.finish(task, investigation);
    mvc.perform(
            post("/api/v1/diligence/data/query")
                .contentType("application/json")
                .content(
                    Json.obj(
                            "task_id",
                            task,
                            "arguments",
                            Json.obj("credit_code", code, "dimension", "PROFILE"))
                        .toString()))
        .andExpect(status().isForbidden());
  }

  ObjectNode proposal(String task, String amount) throws Exception {
    JsonNode enterprise = biz.subject(code);
    biz.bindSubject(task, enterprise);
    ObjectNode base = null;
    for (JsonNode d : contracts.schema.path("x-dimensions"))
      base = biz.query(task, Json.obj("credit_code", code, "dimension", d.get("code")));
    ObjectNode investigation = biz.newOperation(task, "investigation");
    investigation.put("status", "SUCCEEDED");
    investigation.put("stage", "INVESTIGATION_SAVED");
    investigation.set("result_id", base.get("result_id"));
    investigation.set("result_version", base.get("version"));
    biz.finish(task, investigation);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (PDDocument doc = new PDDocument()) {
      doc.addPage(new PDPage());
      doc.save(bytes);
    }
    ObjectNode file =
        files.upload(
            task,
            code,
            "FINANCIAL",
            "report.pdf", bytes.toByteArray());
    String fid = file.path("file_id").asText(), ev = fid + "-p1";
    ObjectNode context =
        Json.obj(
            "period",
            Json.obj("start", "2025-01-01", "end", "2025-12-31"),
            "scope",
            "STANDALONE",
            "currency",
            "CNY",
            "unit",
            "万元",
            "as_of",
            null);
    ObjectNode candidate =
        Json.obj(
            "candidate_id",
            "c1",
            "field_key",
            "financial.f109",
            "context",
            context,
            "raw_value",
            amount,
            "normalized_value",
            amount,
            "partial",
            false,
            "evidence_refs",
            Json.arr(ev));
    ObjectNode input =
        Json.obj(
            "subject",
            biz.subject(code),
            "file_ids",
            Json.arr(fid),
            "candidates",
            Json.arr(candidate),
            "evidence",
            Json.arr(
                Json.obj(
                    "evidence_id",
                    ev,
                    "source_id",
                    fid,
                    "source_kind",
                    "FILE",
                    "locator",
                    "page:1",
                    "page",
                    1,
                    "data_time",
                    null)),
            "idempotency_key",
            Json.id());
    ObjectNode p = financial.propose(task, input);
    contracts.check("FinancialProposalOutput", p);
    assertEquals(p, financial.propose(task, input));
    return p;
  }

  ObjectNode decision(ObjectNode p) {
    return Json.obj(
        "proposal_id",
        p.get("proposal_id"),
        "baseline_token",
        p.get("baseline_token"),
        "idempotency_key",
        Json.id(),
        "decisions",
        Json.arr(Json.obj("candidate_id", "c1", "action", "ACCEPT")));
  }

  @Test
  void financialConfirmationIdempotencyAndBaselineConflict() throws Exception {
    String task = session();
    ObjectNode p = proposal(task, "123.45"), stale = proposal(task, "789.00");
    JsonNode baseline = biz.source.query(code, "FINANCIALS");
    ObjectNode decision = decision(p);
    ObjectNode op = financial.confirm(task, decision);
    assertEquals("SUCCEEDED", op.path("status").asText());
    assertEquals(op, financial.confirm(task, decision));
    assertNotEquals(baseline, biz.source.query(code, "FINANCIALS"));
    assertEquals(
        "VERSION_CONFLICT",
        assertThrows(Fault.class, () -> financial.confirm(task, decision(stale))).code);
    boolean found = false;
    for (JsonNode row : biz.source.query(code, "FINANCIALS").at("/groups/0/rows"))
      for (JsonNode field : row.path("fields"))
        if ("financial.f109".equals(field.path("field_key").asText())
            && "2025-01-01".equals(field.at("/context/period/start").asText())) {
          assertEquals("123.45", field.path("value").asText());
          found = true;
        }
    assertTrue(found);
  }

  @Test
  void exportUsesPinnedSnapshot() throws Exception {
    String task = session();
    ObjectNode result = null;
    for (JsonNode d : contracts.schema.path("x-dimensions"))
      result = biz.query(task, Json.obj("credit_code", code, "dimension", d.get("code")));
    ObjectNode request =
        Json.obj(
            "result_id",
            result.get("result_id"),
            "version",
            result.get("version"),
            "format",
            "DOCX",
            "idempotency_key",
            Json.id());
    ObjectNode exported = files.export(task, request);
    contracts.check("ExportData", exported);
    assertEquals(exported, files.export(task, request));
    try (org.apache.poi.xwpf.usermodel.XWPFDocument doc =
        new org.apache.poi.xwpf.usermodel.XWPFDocument(
            new ByteArrayInputStream(files.read(task, exported.path("file_id").asText())))) {
      String text = doc.getParagraphs().toString();
      assertFalse(doc.getParagraphs().isEmpty());
    }
  }

  @Test
  void demoSeedCoversAllSourceGroupsAndBothPeriods() {
    java.util.Set<String> groups = new java.util.HashSet<>();
    for (JsonNode dim : contracts.schema.path("x-dimensions")) {
      JsonNode card = biz.source.query("91310000MA00000002", dim.path("code").asText());
      for (JsonNode g : card.path("groups"))
        if (g.path("group_key").asText().startsWith("source.")) {
          groups.add(g.path("group_key").asText());
          assertTrue(g.path("complete").asBoolean());
        }
    }
    assertEquals(21, groups.size());
    JsonNode financial = biz.source.query("91310000MA00000002", "FINANCIALS");
    assertEquals(2, financial.at("/groups/0/rows").size());
    for (JsonNode row : financial.at("/groups/0/rows")) assertEquals(66, row.path("fields").size());
    assertFalse(biz.source.writable("FINANCIALS", "financial.f160"));
    assertFalse(biz.source.writable("CREDIT", "credit.balance"));
  }

  @Test
  void paginationKeepsOtherGroupsAndPreviousRows() {
    String task = session();
    ObjectNode first =
        biz.query(task, Json.obj("credit_code", code, "dimension", "PROFILE", "page_size", 1));
    String cursor = null;
    for (JsonNode g : first.path("card").path("groups"))
      if ("source.2".equals(g.path("group_key").asText())) cursor = g.path("next_cursor").asText();
    assertNotNull(cursor);
    ObjectNode next =
        biz.query(
            task,
            Json.obj(
                "credit_code",
                code,
                "dimension",
                "PROFILE",
                "page_size",
                1,
                "group_key",
                "source.2",
                "cursor",
                cursor));
    assertEquals(5, next.at("/card/groups").size());
    for (JsonNode g : next.at("/card/groups"))
      if ("source.2".equals(g.path("group_key").asText())) {
        assertEquals(2, g.path("rows").size());
        assertTrue(g.path("complete").asBoolean());
      }
  }

  @Test
  void investigationMustPrecedeSpecialistSkill() {
    String task = session();
    JsonNode subject = biz.subject(code);
    Fault missing = assertThrows(Fault.class, () -> biz.requireInvestigation(task, code));
    assertEquals("ENTERPRISE_MISMATCH", missing.code);
    biz.bindSubject(task, subject);
    assertEquals("INVESTIGATION_REQUIRED", assertThrows(Fault.class,
        () -> biz.requireInvestigation(task, code)).code);
    ObjectNode result = null;
    for (JsonNode d : contracts.schema.path("x-dimensions"))
      result = biz.query(task, Json.obj("credit_code", code, "dimension", d.get("code")));
    ObjectNode op = biz.newOperation(task, "investigation");
    op.put("status", "SUCCEEDED");
    op.put("stage", "INVESTIGATION_SAVED");
    op.set("result_id", result.get("result_id"));
    op.set("result_version", result.get("version"));
    biz.finish(task, op);
    assertDoesNotThrow(() -> biz.requireInvestigation(task, code));
  }

  @Test
  void pendingProposalsAndOriginalReferencesSurviveRead() throws Exception {
    String task = session();
    ObjectNode p = proposal(task, "456.78");
    JsonNode snapshot =
        Json.parse(
            mvc.perform(get("/api/v1/diligence/sessions/" + task))
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertEquals(p.get("proposal_id"), snapshot.at("/data/proposals/0/proposal_id"));
    mvc.perform(
            get(
                "/api/v1/diligence/sessions/"
                    + task
                    + "/proposals/"
                    + p.path("proposal_id").asText()
                    + "/files"))
        .andExpect(status().isOk());
    mvc.perform(
            get(
                "/api/v1/diligence/sessions/"
                    + session()
                    + "/proposals/"
                    + p.path("proposal_id").asText()
                    + "/files"))
        .andExpect(status().isForbidden());
  }

  @Test
  void sourceEditIsIdempotentAndCannotWriteCredit() {
    String task = session();
    ObjectNode r = biz.query(task, Json.obj("credit_code", code, "dimension", "FINANCIALS"));
    JsonNode row = r.at("/card/groups/0/rows/0");
    JsonNode f = row.path("fields").get(0);
    ObjectNode request =
        Json.obj(
            "result_id",
            r.get("result_id"),
            "base_version",
            r.get("version"),
            "idempotency_key",
            Json.id(),
            "changes",
            Json.arr(
                Json.obj(
                    "record_id",
                    row.get("record_id"),
                    "field_key",
                    f.get("field_key"),
                    "value",
                    "20000.01")));
    ObjectNode op = financial.saveSource(task, request);
    assertEquals("SUCCEEDED", op.path("status").asText());
    assertEquals(op, financial.saveSource(task, request));
    JsonNode credit = biz.query(task, Json.obj("credit_code", code, "dimension", "CREDIT"));
    ObjectNode illegal =
        Json.obj(
            "result_id",
            credit.get("result_id"),
            "base_version",
            credit.get("version"),
            "idempotency_key",
            Json.id(),
            "changes",
            Json.arr(
                Json.obj("record_id", "MOCK-LOAN-A", "field_key", "credit.balance", "value", "0")));
    assertEquals(
        "WRITE_MAPPING_MISSING",
        assertThrows(Fault.class, () -> financial.saveSource(task, illegal)).code);
  }
}
