package com.yuerong.diligence.service.diligence;

import com.yuerong.diligence.ai.port.ModelInferencePort;
import com.yuerong.diligence.ai.model.ModelRequest;
import com.yuerong.diligence.common.Diagnostics;
import com.yuerong.diligence.common.Fault;
import com.yuerong.diligence.common.Json;
import com.yuerong.diligence.web.controller.CapabilityGateway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles("test") @AutoConfigureMockMvc
class CapabilityGatewayTest {
  @Autowired DiligenceService biz;
  @Autowired CapabilityJobs jobs;
  @Autowired CapabilityGateway gateway;
  @Autowired GatewayFiles uploads;
  @Autowired FilesService files;
  @Autowired MockMvc mvc;
  @MockBean ModelInferencePort model;
  final String code = "91310000MA00000001";
  String task;

  @BeforeEach void setup() {
    task = biz.create("test-user").path("task_id").asText();
    when(model.visionReady()).thenReturn(true);
    when(model.infer(any(ModelRequest.class))).thenAnswer(inv -> {
      ModelRequest request = inv.getArgument(0); String kind = request.taskCode; JsonNode input = request.input;
      if ("investigation.ai_summary".equals(kind)) {
        ArrayNode items = Json.arr();
        for (JsonNode item : input.path("items")) items.add(Json.obj("code", item.get("code"), "text", "根据所列已登记资料解释，缺失部分仍待核实。", "evidence_refs", item.get("evidence_refs")));
        return Json.obj("items", items);
      }
      String evidence = input.path("fragments").get(0).path("evidence_id").asText();
      if ("financial_report.analyze".equals(kind)) return Json.obj("text", "依据已保存数据进行解释。", "evidence_refs", Json.arr(evidence));
      if ("financial_report.extract".equals(kind)) return Json.obj("subject_match", "MATCHED", "limitations", Json.arr(), "candidates", Json.arr(Json.obj(
          "candidate_id", "model-id", "field_key", "financial.f109", "context", Json.obj("period", Json.obj("start", "2025-01-01", "end", "2025-12-31"), "scope", "STANDALONE", "currency", "CNY", "unit", "元", "as_of", null),
          "raw_value", "1,234.50", "normalized_value", "999", "partial", false, "evidence_refs", Json.arr(evidence))));
      return Json.obj("subject_match", "MATCHED", "evidence_refs", Json.arr(evidence), "records", Json.arr(Json.obj("institution", "测试机构", "account", "account-1", "currency", "CNY", "balance", "1,234.50", "as_of", "2025-12-31")));
    });
  }

  ObjectNode request(String action, JsonNode args, String id) {
    return Json.obj("request_id", id, "action", action, "context", Json.obj("task_id", task), "arguments", args);
  }
  JsonNode call(String action, JsonNode args, String id) {
    ObjectNode reply = gateway.call(request(action, args, id));
    assertNotEquals("FAILED", reply.path("status").asText(), reply.toString());
    return reply.path("data");
  }
  JsonNode run(String action, JsonNode args, String id) {
    JsonNode submitted = call(action, args, id);
    jobs.runOnce(submitted.at("/operation/operation_id").asText());
    return jobs.read(task, submitted.at("/operation/operation_id").asText());
  }
  JsonNode investigate() { return run("investigation.get", Json.obj("credit_code", code), Json.id()); }

  @Test void submitsOncePublishesWholeVersionAndReadsThroughPublicSession() throws Exception {
    JsonNode input = Json.obj("credit_code", code);
    JsonNode first = call("investigation.get", input, "same-request");
    assertEquals("RUNNING", first.at("/operation/status").asText());
    assertTrue(biz.results(task).isEmpty());
    assertEquals(first, call("investigation.get", input, "same-request"));
    assertEquals(first, call("investigation.get", input, "same-content"));
    assertEquals("IDEMPOTENCY_CONFLICT", gateway.call(request("investigation.get", Json.obj("credit_code", code, "refresh", true), "same-request")).at("/error/code").asText());
    String id = first.at("/operation/operation_id").asText();
    jobs.runOnce(id); JsonNode done = jobs.read(task, id);
    assertTrue(Arrays.asList("SUCCEEDED", "PARTIAL").contains(done.at("/operation/status").asText()), done.toString());
    assertEquals("1", done.at("/operation/result_version").asText());
    assertEquals(7, biz.results(task).get(0).path("dimensions").size());
    assertEquals(done, call("investigation.get", input, "same-request"));
    jobs.runOnce(id);
    assertEquals("1", biz.currentVersion(task, code));
    verify(model, times(1)).infer(argThat(request -> "investigation.ai_summary".equals(request.taskCode)));
    JsonNode session = Json.parse(mvc.perform(get("/api/v1/diligence/sessions/" + task)).andReturn().getResponse().getContentAsString()).path("data");
    assertTrue(session.path("investigation_ready").asBoolean());
    assertEquals(done.get("operation"), session.path("business_operations").get(0));
    assertFalse(session.toString().contains("run_token"));
  }

  @Test void completedRunCanBeReplayedWithoutRefreshingAuthorization() {
    JsonNode start = call("investigation.get", Json.obj("credit_code", code), "recover");
    String id = start.at("/operation/operation_id").asText();
    jobs.runOnce(id);
    biz.store.update("session", task, value -> { value.put("running", false); return value; });
    JsonNode saved = jobs.read(task, id);
    assertNotEquals("FAILED", saved.at("/operation/status").asText(), saved.toString());
    assertEquals(saved, call("operation.get", Json.obj("operation_id", id), "replay"));
    assertEquals(saved, call("investigation.get", Json.obj("credit_code", code), "recover"));
    assertEquals("test-user", biz.store.get("cap-job", id).path("owner").asText());
  }

  @Test void leasePreventsStaleWorkerPublicationAndExpiredJobCanBeReclaimed() {
    JsonNode started = call("investigation.get", Json.obj("credit_code", code), "lease");
    String id = started.at("/operation/operation_id").asText();
    ObjectNode job = biz.store.update("cap-job", id, j -> {j.put("lease_id", "old");j.put("lease_until",System.currentTimeMillis()+60000);return j;});
    CapabilityJobs.Run old = jobs.new Run(id, "old", job);
    old.step("test-checkpoint", "SOURCE_QUERY", () -> Json.obj("value", "checkpoint"));
    jobs.runOnce(id); assertTrue(biz.results(task).isEmpty());
    biz.store.update("cap-job", id, j -> {j.put("lease_until", 1);return j;});
    jobs.runOnce(id);
    assertNotEquals("RUNNING", jobs.read(task,id).at("/operation/status").asText());
    assertThrows(Fault.class, () -> old.step("late", "LATE", () -> Json.obj()));
    assertNotNull(biz.store.get("cap-step", Json.hash(Json.arr(id,"test-checkpoint"))));
  }

  @Test void modelFailureRemainsPartialAndCachedReuseDoesNotClaimComplete() {
    doThrow(new Fault("MODEL_UNAVAILABLE", "模型不可用")).when(model).infer(any(ModelRequest.class));
    JsonNode result = investigate();
    assertEquals("PARTIAL", result.at("/operation/status").asText());
    assertEquals("PARTIAL", run("investigation.get", Json.obj("credit_code", code), "reuse-partial").at("/operation/status").asText());
  }

  byte[] pdf() throws Exception {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      doc.addPage(new PDPage()); doc.save(out); return out.toByteArray();
    }
  }
  JsonNode upload(String role) throws Exception {
    byte[] content = pdf();
    JsonNode input = Json.obj("credit_code", code, "role", role, "name", "synthetic.pdf", "size", content.length, "sha256", Json.hash(content));
    JsonNode started = call("files.begin", input, role);
    String id = started.path("upload_id").asText();
    JsonNode part = Json.obj("upload_id", id, "index", 0, "base64", Base64.getEncoder().encodeToString(content));
    call("files.part", part, Json.id()); call("files.part", part, Json.id());
    JsonNode end = call("files.complete", Json.obj("upload_id",id), Json.id());
    assertEquals(end, call("files.complete",Json.obj("upload_id",id),Json.id()));
    assertEquals(end, call("files.begin",input,role).path("file"));
    return end;
  }

  @Test void financialReviewOnlyProposesAndAnalyzeUsesSavedVersion() throws Exception {
    investigate(); String baseline = biz.source.baseline(code);
    JsonNode file = upload("FINANCIAL");
    JsonNode multiple = gateway.call(request("financial.review", Json.obj("subject", biz.subject(code),
        "mode", "IMPORT", "file_ids", Json.arr(file.get("file_id"), "another-file")), "multiple-files"));
    assertEquals("FAILED", multiple.path("status").asText());
    assertEquals("INVALID_ARGUMENT", multiple.at("/error/code").asText());
    JsonNode result = run("financial.review", Json.obj("subject", biz.subject(code), "mode", "IMPORT", "file_ids", Json.arr(file.get("file_id"))), "financial-review");
    assertEquals("AWAITING_CONFIRMATION", result.at("/operation/status").asText(), result.toString());
    JsonNode proposal = call("result.read", Json.obj("proposal_id", result.at("/operation/proposal_id")), Json.id());
    assertTrue(proposal.toString().contains("1234.5"));
    assertFalse(proposal.toString().contains("\"normalized_value\":\"999\""));
    assertEquals(baseline, biz.source.baseline(code));
    JsonNode current = biz.results(task).get(0);
    JsonNode input = Json.obj("subject", biz.subject(code), "mode", "ANALYZE", "question", "分析财务资料", "result_id", current.get("result_id"), "version", current.get("version"));
    JsonNode analyzed = run("financial.analyze", input, "analyze");
    assertEquals("SUCCEEDED", analyzed.at("/operation/status").asText(), analyzed.toString());
    assertEquals("2", analyzed.at("/operation/result_version").asText());
    assertEquals(analyzed, call("financial.analyze",input,"analyze"));
    assertEquals(baseline, biz.source.baseline(code));
  }

  @Test void creditReviewIsReadOnlyAndRecordsMissingBaselineAsPartial() throws Exception {
    investigate(); String baseline = biz.source.baseline(code);
    JsonNode file = upload("CREDIT");
    JsonNode result = run("credit.review", Json.obj("subject", biz.subject(code), "file_ids", Json.arr(file.get("file_id"))), "credit");
    assertTrue(Arrays.asList("SUCCEEDED", "PARTIAL").contains(result.at("/operation/status").asText()), result.toString());
    assertEquals("2", result.at("/operation/result_version").asText());
    assertEquals(baseline, biz.source.baseline(code));
  }

  @Test void gatewayCannotSwitchTaskOrRunHumanWriteAndBusySessionCannotDelete() throws Exception {
    String own = task;
    JsonNode started = call("investigation.get",Json.obj("credit_code",code),"own-operation");
    task = biz.create("test-user").path("task_id").asText();
    assertEquals("FORBIDDEN", gateway.call(request("operation.get",Json.obj("operation_id",started.at("/operation/operation_id")),"foreign")).at("/error/code").asText());
    task = own;
    assertEquals("INVALID_ARGUMENT", gateway.call(request("financial.confirm",Json.obj(),"forged-write")).at("/error/code").asText());
    call("investigation.get",Json.obj("credit_code",code),"busy");
    assertEquals(409,mvc.perform(delete("/api/v1/diligence/sessions/"+task)).andReturn().getResponse().getStatus());
    assertEquals("RUN_BUSY", gateway.call(request("investigation.get",Json.obj("credit_code",code,"refresh",true),"different")).at("/error/code").asText());
  }

  @Test void gatewayRequiresExistingTaskAndPublicOwnershipStillApplies() {
    assertThrows(Fault.class, () -> biz.session(task, "other-user"));
    ObjectNode req=request("session.context",Json.obj(),"missing");
    ((ObjectNode)req.get("context")).remove("task_id");
    assertEquals("INVALID_ARGUMENT",gateway.call(req).at("/error/code").asText());
    task="unknown-task";
    assertEquals("NOT_FOUND",gateway.call(request("session.context",Json.obj(),"unknown")).at("/error/code").asText());
    task=biz.create("test-user").path("task_id").asText();
    biz.store.update("session", task, value -> { value.put("deleted",true);return value; });
    assertEquals("NOT_FOUND",gateway.call(request("session.context",Json.obj(),"deleted")).at("/error/code").asText());
  }
  @Test void workerDiagnosticsCarryOriginalRequestAndReportSkippedPagesWithoutContent() throws Exception {
    investigate();
    JsonNode file = upload("FINANCIAL");
    doAnswer(inv -> Json.parse("{PRIVATE_INVALID_MODEL_CONTENT"))
        .when(model).infer(argThat(request -> "financial_report.extract".equals(request.taskCode)));
    ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
        org.slf4j.LoggerFactory.getLogger("com.yuerong.diligence.diagnostics");
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs = new ch.qos.logback.core.read.ListAppender<>();
    logs.start(); logger.addAppender(logs);
    java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      JsonNode submitted = call("financial.review", Json.obj("subject", biz.subject(code), "mode", "IMPORT",
          "file_ids", Json.arr(file.get("file_id"))), "financial-log-request");
      String id = submitted.at("/operation/operation_id").asText();
      worker.submit(() -> jobs.runOnce(id)).get();
      assertEquals("NEEDS_INPUT", jobs.read(task, id).at("/operation/status").asText());
      assertEquals("financial-log-request", biz.store.get("cap-job", id).path("request_id").asText());
      boolean skipped = false, completed = false;
      for (ch.qos.logback.classic.spi.ILoggingEvent entry : logs.list) {
        String text = entry.getFormattedMessage(); assertFalse(text.contains("PRIVATE_INVALID_MODEL_CONTENT"));
        JsonNode log = Json.parse(text.substring(text.indexOf('{')));
        if ("page.skipped".equals(log.path("event").asText())) {
          skipped = true;
          assertEquals("financial-log-request", log.path("request_id").asText());
          assertEquals(id, log.path("operation_id").asText());
          assertEquals(file.get("file_id"), log.get("file_id"));
          assertEquals(1, log.path("page").asInt());
          assertTrue(text.contains("JsonParseException"));
        }
        if ("job.completed".equals(log.path("event").asText())) {
          completed = true; assertEquals("NEEDS_INPUT", log.path("status").asText());
        }
      }
      assertTrue(skipped); assertTrue(completed);
      assertEquals("", worker.submit(() -> Diagnostics.current("request_id")).get());
    } finally { worker.shutdownNow(); logger.detachAppender(logs); logs.stop(); }
  }

  @Test void missingArchivedFileFailsTaskAndRetainsDiagnosticCause() throws Exception {
    investigate();
    JsonNode uploaded = upload("FINANCIAL");
    String missingId = Json.id();
    ObjectNode missing = Json.object(uploaded.deepCopy()); missing.put("file_id", missingId);
    biz.store.create("file", missingId, task, missing);
    ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
        org.slf4j.LoggerFactory.getLogger("com.yuerong.diligence.diagnostics");
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs = new ch.qos.logback.core.read.ListAppender<>();
    logs.start(); logger.addAppender(logs);
    try {
      JsonNode result = run("financial.review", Json.obj("subject", biz.subject(code), "mode", "IMPORT",
          "file_ids", Json.arr(missingId)), "missing-file-request");
      assertEquals("FAILED", result.at("/operation/status").asText());
      assertEquals("FILE_UNAVAILABLE", result.at("/operation/error/code").asText());
      StringBuilder text = new StringBuilder(); logs.list.forEach(e -> text.append(e.getFormattedMessage()));
      assertTrue(text.toString().contains("NoSuchFileException"));
      assertTrue(text.toString().contains("missing-file-request"));
      assertTrue(text.toString().contains("job.failure_saved"));
    } finally { logger.detachAppender(logs); logs.stop(); }
  }

}
