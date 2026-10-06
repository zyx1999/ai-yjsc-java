package com.yuerong.diligence.service.diligence;

import com.yuerong.diligence.common.Diagnostics;
import com.yuerong.diligence.common.Fault;
import com.yuerong.diligence.common.Json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;

/** Fixed-URL gateway for trusted internal callers; context identifies an existing session. */
@Service
public class DiligenceGatewayService {
  private final DiligenceService biz;
  private final CapabilityJobs jobs;
  private final FilesService files;
  private final GatewayFiles uploads;

  public DiligenceGatewayService(DiligenceService biz, CapabilityJobs jobs, FilesService files, GatewayFiles uploads) {
    this.biz = biz; this.jobs = jobs; this.files = files; this.uploads = uploads;
  }

  public ObjectNode call(JsonNode request) {
    String requestId = request.path("request_id").asText();
    long started = System.nanoTime();
    try (Diagnostics.Scope ignored = Diagnostics.scope("request_id", requestId,
        "task_id", request.at("/context/task_id").asText(), "action", request.path("action").asText())) {
      Diagnostics.info("gateway.received");
      try {
        if (request.toString().getBytes(StandardCharsets.UTF_8).length > 524288)
          throw new Fault("REQUEST_TOO_LARGE", "业务请求超过512KiB");
        biz.contracts.check("CapabilityRequest", request);
        String action = request.path("action").asText();
        JsonNode context = request.path("context"), args = request.path("arguments");
        JsonNode data;
        String task = context.path("task_id").asText();
        ObjectNode session = biz.internalSession(task);
          switch (action) {
            case "enterprise.resolve": data = biz.resolveForSession(task, args); break;
            case "investigation.get": case "financial.review": case "financial.analyze": case "credit.review":
              data = jobs.submit(task, action, args, requestId); break;
            case "operation.get":
              biz.contracts.check("OperationInput", args); data = jobs.read(task, args.path("operation_id").asText()); break;
            case "session.context":
              if (args.size() != 0) throw new Fault("INVALID_ARGUMENT", "会话上下文不接受额外参数");
              data = context(task, session.path("owner").asText()); break;
            case "result.read": data = read(task, args); break;
            case "result.export": data = export(task, args); break;
            case "files.begin": data = uploads.begin(task, requestId, args); break;
            case "files.part": data = uploads.part(task, args); break;
            case "files.complete": data = uploads.complete(task, args); break;
            default: throw new Fault("INVALID_ARGUMENT", "未登记业务能力");
          }
        ObjectNode response = biz.contracts.response(data); response.put("request_id", requestId);
        if ("RUNNING".equals(data.at("/operation/status").asText())) response.put("status", "ACCEPTED");
        Diagnostics.info("gateway.completed", "status", response.path("status").asText(),
            "operation_id", data.at("/operation/operation_id").asText(), "file_id", data.path("file_id").asText(),
            "elapsed_ms", Diagnostics.elapsed(started));
        return response;
      } catch (Exception error) {
        Diagnostics.failure("gateway.failed", error, "elapsed_ms", Diagnostics.elapsed(started));
        Fault fault = error instanceof Fault ? (Fault) error : new Fault("INTERNAL_ERROR", "业务请求未完成，请按请求标识查询状态", 500);
        // The workflow HTTP node exposes only body: transport succeeds, business failure remains explicit.
        return Json.obj("contract_version", "DILIGENCE_V1_DRAFT", "request_id", requestId.length() <= 128 ? requestId : "",
            "status", "FAILED", "data", null, "error", Json.obj("code", fault.code, "message", fault.getMessage(), "retryable", false));
      }
    }
  }

  private ObjectNode export(String task, JsonNode args) {
    final ObjectNode[] result = {null};
    biz.store.update("session", task, session -> {
      try { result[0] = files.export(task, args); }
      catch (java.io.IOException e) { throw new Fault("EXPORT_FAILED", "导出生成失败，请用原请求重试"); }
      return biz.store.get("session", task);
    });
    return result[0];
  }

  private ObjectNode context(String task, String owner) {
    ObjectNode s = biz.session(task, owner); ArrayNode messages = Json.arr(), results = Json.arr();
    JsonNode all = s.path("messages");
    for (int i = Math.max(0, all.size() - 8); i < all.size(); i++) {
      String text = all.get(i).path("text").asText();
      messages.add(Json.obj("role", all.get(i).get("role"), "text", text.substring(0, Math.min(1200, text.length()))));
    }
    for (JsonNode result : biz.results(task)) results.add(Json.obj("result_id", result.get("result_id"), "version", result.get("version"), "subject", result.get("subject")));
    return Json.obj("bound_subject", s.path("bound_subject"), "investigation_ready", s.path("investigation_ready").asBoolean(),
        "recent_messages", messages, "results", results, "pending_operation_id", s.path("capability_operation"));
  }

  private JsonNode read(String task, JsonNode args) {
    biz.contracts.check("CapabilityReadInput", args);
    if (args.has("proposal_id")) {
      if (args.size() != 1) throw new Fault("INVALID_ARGUMENT", "提案读取参数不能混用");
      return biz.owned("proposal", args.path("proposal_id").asText(), task).get("data");
    }
    boolean summary = "card".equals(args.path("view").asText());
    ObjectNode input = Json.object(args.deepCopy()); input.remove(Arrays.asList("view", "dimension"));
    if (!input.has("result_id")) {
      JsonNode results = biz.results(task);
      if (results.isEmpty()) throw new Fault("INVESTIGATION_REQUIRED", "请先生成调查表", 409);
      input.set("result_id", results.get(0).get("result_id"));
    }
    if (summary) {
      if (!args.has("dimension") || args.has("dimensions")) throw new Fault("INVALID_ARGUMENT", "卡片视图须选择一个维度");
      input.set("dimensions", Json.arr(args.get("dimension")));
    } else if (args.has("dimension")) throw new Fault("INVALID_ARGUMENT", "单维度参数仅用于卡片视图");
    ObjectNode result = biz.read(task, input);
    if (!summary) return result;
    JsonNode card = result.path("dimensions").get(0); ArrayNode groups = Json.arr();
    for (JsonNode group : card.path("groups")) {
      ArrayNode rows = Json.arr(); int count = 0;
      for (JsonNode row : group.path("rows")) { if (count++ >= 8) break; rows.add(row); }
      groups.add(Json.obj("title", group.get("title"), "total_count", group.get("total_count"), "data_status", group.get("data_status"), "rows", rows));
    }
    return Json.obj("subject", result.get("subject"), "version", result.get("version"), "dimension", args.get("dimension"),
        "data_status", card.get("data_status"), "groups", groups, "metrics", card.get("metrics"), "summaries", card.get("summaries"),
        "limitations", card.get("limitations"));
  }
}
