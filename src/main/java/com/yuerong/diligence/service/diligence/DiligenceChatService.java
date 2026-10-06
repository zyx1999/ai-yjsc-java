package com.yuerong.diligence.service.diligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.yuerong.diligence.ai.port.AgentPort;
import com.yuerong.diligence.common.*;
import com.yuerong.diligence.common.stream.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Owns the diligence conversation lifecycle and public event contract, not SSE transport. */
@Service
public class DiligenceChatService {
  private final DiligenceService biz;
  private final FilesService files;
  private final AgentPort agent;
  private final String namespace;

  public DiligenceChatService(DiligenceService biz, FilesService files, AgentPort agent,
      @Value("${diligence.platform.namespace:v2}") String namespace) {
    this.biz = biz; this.files = files; this.agent = agent; this.namespace = namespace;
  }

  public EventStreamTask prepare(String task, String user, JsonNode body) {
    biz.session(task, user);
    String text = body.path("text").asText();
    validateText(text);
    ObjectNode operation = biz.newOperation(task, "AGENT");
    JsonNode attachment = body.path("attachment");
    AtomicInteger sequence = new AtomicInteger();
    String httpRequest = Diagnostics.current("http_request_id");
    String operationId = operation.path("operation_id").asText();
    long started = System.nanoTime();
    Diagnostics.info("chat.queued", "task_id", task, "operation_id", operationId,
        "file_id", attachment.path("file_id").asText());
    return new EventStreamTask() {
      public Object[] diagnosticContext() {
        return new Object[]{"http_request_id", httpRequest, "task_id", task,
            "operation_id", operationId, "file_id", attachment.path("file_id").asText()};
      }
      public void rejected() {
        operation.put("status", "FAILED");
        operation.set("error", Json.obj("code", "RUN_BUSY", "message", "服务繁忙", "retryable", true));
        biz.finish(task, operation);
      }
      public void execute(EventSink sink) throws Exception {
        try (Diagnostics.Scope ignored = Diagnostics.scope("http_request_id", httpRequest,
            "task_id", task, "operation_id", operationId, "file_id", attachment.path("file_id").asText())) {
          Diagnostics.info("chat.started");
          try {
            send(sink, operation, sequence, "run.started", operation);
            send(sink, operation, sequence, "run.progress", operation);
            JsonNode before = biz.results(task);
            JsonNode response =
                executeChat(task, user, text, attachment, operation.path("operation_id").asText());
            send(
                sink,
                operation,
                sequence,
                "answer.completed",
                Json.obj("text", response.get("answer")));
            if (response.hasNonNull("proposal_id"))
              send(sink, operation, sequence, "confirmation.required",
                  Json.obj("proposal_id", response.get("proposal_id"),
                      "credit_code", biz.session(task, user).at("/bound_subject/credit_code")));
            for (JsonNode result : biz.results(task)) {
              boolean changed = true;
              for (JsonNode old : before) if (old.equals(result)) changed = false;
              if (!changed) continue;
              ArrayNode dimensions = Json.arr();
              for (JsonNode card : result.path("dimensions"))
                dimensions.add(card.get("dimension"));
              send(
                  sink,
                  operation,
                  sequence,
                  "card.updated",
                  Json.obj(
                      "credit_code",
                      result.at("/subject/credit_code"),
                      "result_id",
                      result.get("result_id"),
                      "version",
                      result.get("version"),
                      "dimensions",
                      dimensions));
            }
            operation.put("status", "SUCCEEDED");
            biz.finish(task, operation);
            send(sink, operation, sequence, "run.completed", operation);
            Diagnostics.info("chat.completed", "status", "SUCCEEDED", "elapsed_ms", Diagnostics.elapsed(started));

          } catch (Exception error) {
            Diagnostics.failure("chat.failed", error, "elapsed_ms", Diagnostics.elapsed(started));
            String code = error instanceof Fault ? ((Fault) error).code : "RESULT_UNKNOWN";
            boolean unknown = "RESULT_UNKNOWN".equals(code);
            JsonNode detail =
                Json.obj(
                    "code",
                    code,
                    "message",
                    error instanceof Fault ? error.getMessage() : "运行结果待核实",
                    "retryable",
                    false);
            operation.put("status", unknown ? "UNKNOWN" : "FAILED");
            operation.set("error", detail);
            biz.finish(task, operation);
            try {
              send(
                  sink,
                  operation,
                  sequence,
                  unknown ? "run.unknown" : "run.failed",
                  Json.obj("operation", operation, "error", detail));

            } catch (IOException disconnected) {
              Diagnostics.failure("chat.delivery_failed", disconnected);
              throw disconnected;
            }
          }
        } catch (RuntimeException persistenceError) {
          Diagnostics.failure("chat.failure_save_failed", persistenceError, "http_request_id", httpRequest,
              "task_id", task, "operation_id", operationId);
          throw persistenceError;
        }
      }
    };
  }

  private void send(
      EventSink sink,
      ObjectNode operation,
      AtomicInteger sequence,
      String type,
      JsonNode payload)
      throws IOException {
    int seq = sequence.incrementAndGet();
    String run = operation.path("operation_id").asText();
    ObjectNode event =
        Json.obj(
            "contract_version",
            "DILIGENCE_V1_DRAFT",
            "event_id",
            run + ":" + seq,
            "run_id",
            run,
            "sequence",
            seq,
            "type",
            type,
            "payload",
            payload);
    biz.contracts.check("CardEvent", event);
    sink.send(run + ":" + seq, type, event);
  }

  private void validateText(String text) {
    if (text.trim().isEmpty() || text.length() > 12000)
      throw new Fault("INVALID_ARGUMENT", "消息不能为空且须小于12000字");
  }

  private ObjectNode executeChat(String task, String user, String text, JsonNode attachment, String run) throws Exception {
    ObjectNode state =
        biz.store.update(
            "session",
            task,
            session -> {
              if (session.path("running").asBoolean() || session.path("unknown_run").asBoolean())
                throw new Fault("RUN_BUSY", "会话运行中或上次结果待核实；可新建会话继续查询", 409);
              session.put("running", true);
              session.put("run_id", run);
              if (session.path("messages").isEmpty())
                session.put("title", text.substring(0, Math.min(24, text.length())));
              ((ArrayNode) session.get("messages"))
                  .add(Json.obj("role", "user", "text", text, "run_id", run));
              return session;
            });
    try {
      String sessionKey = agent.provider() + ":" + namespace;
      String platformSession = state.path("platform_sessions").path(sessionKey).asText(null);
      if (platformSession == null || platformSession.isEmpty())
        platformSession = agent.initialSession();
      final String boundSession = platformSession;
      if (!boundSession.isEmpty())
        biz.store.update(
            "session",
            task,
            session -> {
              ((ObjectNode) session.get("platform_sessions")).put(sessionKey, boundSession);
              return session;
            });
      JsonNode platformAttachment = null;
      if (attachment != null && !attachment.isMissingNode() && !attachment.isNull()) {
        if (!attachment.isObject() || attachment.size() != 1
            || !attachment.path("file_id").isTextual())
          throw new Fault("INVALID_ARGUMENT", "附件引用无效");
        String fileId = attachment.path("file_id").asText();
        ObjectNode file = biz.owned("file", fileId, task);
        String role = file.path("role").asText();
        if (!"FINANCIAL".equals(role) && !"CREDIT".equals(role))
          throw new Fault("INVALID_ARGUMENT", "该附件用途尚无可执行业务能力");
        // 本地适配器只用 file_id/role；行内适配器还会上传文件内容并在消息中引用相对路径。
        platformAttachment =
            Json.obj(
                "file_id", fileId,
                "role", role,
                "name", file.path("name").asText("材料.pdf"),
                "content", files.read(task, fileId));
      }
      Diagnostics.info("agent.started", "provider", agent.provider(), "file_count", platformAttachment == null ? 0 : 1);
      long started = System.nanoTime();
      ObjectNode reply;
      try {
        reply = agent.chat(DiligenceAgentContext.request(platformSession, user, text, task, platformAttachment));
        Diagnostics.info("agent.completed", "elapsed_ms", Diagnostics.elapsed(started));
      } catch (Exception error) {
        Diagnostics.failure("agent.failed", error, "elapsed_ms", Diagnostics.elapsed(started)); throw error;
      }
      biz.store.update(
          "session",
          task,
          session -> {
            ((ObjectNode) session.get("platform_sessions"))
                .set(sessionKey, reply.get("session_id"));
            ((ArrayNode) session.get("messages"))
                .add(Json.obj("role", "assistant", "text", reply.get("answer"), "run_id", run));
            return session;
          });
      return reply;
    } catch (Fault error) {
      biz.store.update(
          "session",
          task,
          session -> {
            session.put("unknown_run", "RESULT_UNKNOWN".equals(error.code));
            ((ArrayNode) session.get("messages"))
                .add(Json.obj("role", "assistant", "text", error.getMessage(), "run_id", run));
            return session;
          });
      throw error;
    } finally {
      biz.store.update(
          "session",
          task,
          session -> {
            session.put("running", false);
            return session;
          });

    }
  }
}
