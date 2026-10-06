package com.yuerong.diligence.service.diligence;

import com.yuerong.diligence.common.Fault;
import com.yuerong.diligence.common.Json;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.stereotype.Service;
import com.yuerong.diligence.model.FileDownload;

@Service
public class DiligenceFacade {
  private final DiligenceService biz;
  private final FinancialService finance;
  private final FilesService files;

  public DiligenceFacade(
      DiligenceService biz,
      FinancialService finance,
      FilesService files) {
    this.biz = biz;
    this.finance = finance;
    this.files = files;
  }

  public JsonNode create(String user) {
    return biz.contracts.response(biz.create(user));
  }

  public JsonNode list(String user) {
    ArrayNode out = Json.arr();
    List<ObjectNode> sessions = biz.store.list("session", user);
    sessions.sort(
        Comparator.comparing(DiligenceFacade::sessionUseTime)
            .reversed()
            .thenComparing(s -> s.path("task_id").asText()));
    for (ObjectNode s : sessions)
      if (!s.path("deleted").asBoolean())
        out.add(Json.obj("task_id", s.get("task_id"), "title", s.get("title"),
            "last_used_at", s.path("last_used_at")));
    return biz.contracts.response(out);
  }

  private static java.time.Instant sessionUseTime(ObjectNode session) {
    String value = session.path("last_used_at").asText(
        session.path("created_at").asText(""));
    try {
      return java.time.Instant.parse(value);
    } catch (java.time.format.DateTimeParseException ignored) {
      return java.time.Instant.MIN;
    }
  }

  public JsonNode session(String user, String task) {
    ObjectNode s = biz.session(task, user);
    ArrayNode visibleMessages = Json.arr();
    for (JsonNode message : s.path("messages")) {
      ObjectNode visible = message.deepCopy();
      if ("assistant".equals(message.path("role").asText())) {
        try {
          visible.put("text", AnswerSafety.visible(message.path("text").asText()));
        } catch (Fault error) {
          visible.put("text", "平台回答不可显示，请重新提问。");
        }
      }
      visibleMessages.add(visible);
    }
    return biz.contracts.response(
        Json.obj(
            "task_id",
            task,
            "title",
            s.get("title"),
            "bound_subject",
            s.path("bound_subject"),
            "investigation_ready",
            s.path("investigation_ready"),
            "messages",
            visibleMessages,
            "results",
            biz.results(task),
            "proposals",
            pending(task),
            "business_operations",
            businessOperations(task)));
  }

  private ArrayNode businessOperations(String task) {
    ArrayNode out = Json.arr();
    for (ObjectNode operation : biz.store.list("operation", task)) {
      JsonNode data = operation.path("data");
      ObjectNode job = biz.store.get("cap-job", data.path("operation_id").asText());
      if (job != null && task.equals(job.path("task_id").asText())) out.add(data);
    }
    return out;
  }

  private ArrayNode pending(String task) {
    ArrayNode out = Json.arr();
    for (ObjectNode p : biz.store.list("proposal", task))
      if (!p.path("confirmed").asBoolean()) out.add(p.get("data"));
    return out;
  }

  public JsonNode proposalFiles(String user, String task, String id) {
    biz.session(task, user);
    ObjectNode p = biz.owned("proposal", id, task);
    ArrayNode out = Json.arr();
    Set<String> seen = new HashSet<>();
    for (JsonNode e : p.path("evidence")) {
      String file = e.path("source_id").asText();
      if (seen.add(file)) {
        ObjectNode f = biz.owned("file", file, task);
        out.add(
            Json.obj(
                "file_id",
                file,
                "name",
                f.path("name").asText("财务报表.pdf"),
                "page",
                e.path("page")));
      }
    }
    return biz.contracts.response(out);
  }

  public JsonNode delete(String user, String task) {
    biz.session(task, user);
    biz.store.update(
        "session",
        task,
        s -> {
          String active = s.path("capability_operation").asText();
          if (!active.isEmpty() && "RUNNING".equals(biz.operation(task, active).path("status").asText()))
            throw new Fault("RUN_BUSY", "业务正在执行，请完成后再删除会话", 409);
          s.put("deleted", true);
          return s;
        });
    return biz.contracts.response(Json.obj("deleted", true));
  }

  public JsonNode call(
      String user, String family, String action, JsonNode body)
      throws Exception {
    String task = body.path("task_id").asText();
    biz.session(task, user);
    JsonNode a = body.path("arguments");
    return biz.contracts.response(dispatch(task, family + "/" + action, a));
  }

  public JsonNode dispatch(String task, String route, JsonNode a) throws Exception {
    switch (route) {
      case "enterprise/resolve":
        return biz.resolveForSession(task, a);
      case "data/query":
        biz.requireInvestigation(task, a.path("credit_code").asText());
        if (!a.hasNonNull("cursor"))
          throw new Fault("FORBIDDEN", "请通过调查表更新完整七维结果", 403);
        return biz.query(task, a);
      case "results/read":
        return biz.read(task, a);
      case "results/export":
        return files.export(task, a);
      case "operations/read":
        biz.contracts.check("OperationInput", a);
        return biz.operation(task, a.path("operation_id").asText());
      case "financial/confirm":
        return finance.confirm(task, a);
      case "source-fields/save":
        return finance.saveSource(task, a);
      default:
        throw new Fault("NOT_FOUND", "未知业务接口", 404);
    }
  }

  public JsonNode proposal(String user, JsonNode body) {
    String task = body.path("task_id").asText();
    biz.session(task, user);
    JsonNode a = body.path("arguments");
    biz.contracts.check("ProposalReadInput", a);
    return biz.contracts.response(
        biz.owned("proposal", a.path("proposal_id").asText(), task).get("data"));
  }

  public JsonNode upload(
      String user, String task,
      String credit_code,
      String role,
      String name, byte[] content)
      throws Exception {
    biz.session(task, user);
    biz.requireInvestigation(task, credit_code);
    return biz.contracts.response(files.upload(task, credit_code, role, name, content));
  }

  public FileDownload download(String user, String task, String id)
      throws Exception {
    biz.session(task, user);
    ObjectNode f = biz.owned("file", id, task);
    return new FileDownload(id + ("EXPORT".equals(f.path("role").asText()) ? ".docx" : ".pdf"),
        f.path("mime").asText(), files.read(task, id));
  }

  public JsonNode catalog() throws Exception {
    return biz.contracts.response(
        Json.M.readTree(getClass().getResourceAsStream("/adapter/source/catalog.json")));
  }

}
