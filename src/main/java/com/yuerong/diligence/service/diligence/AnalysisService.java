package com.yuerong.diligence.service.diligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuerong.diligence.ai.model.AgentRequest;
import com.yuerong.diligence.ai.port.AgentPort;
import com.yuerong.diligence.common.Diagnostics;
import com.yuerong.diligence.common.Fault;
import com.yuerong.diligence.common.Json;
import com.yuerong.diligence.common.stream.EventSink;
import com.yuerong.diligence.common.stream.EventStreamTask;
import com.yuerong.diligence.model.diligence.Contracts;
import com.yuerong.diligence.repository.ApplicationStorePort;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 征信 / 流水分析窗口（文答场景）的会话、材料与对话服务。
 *
 * <p>会话与尽调会话分开存储（kind=analysis-session / analysis-file），复用平台 AgentPort 与
 * SSE 传输；归属校验、运行中互斥与"结果待核实"（unknown_run）语义与尽调一致。断流后前端通过
 * GET 会话读取已落库的 assistant 消息（status=SUCCEEDED/FAILED）恢复结果。
 */
@Service
public class AnalysisService {
  private static final String SESSION_KIND = "analysis-session";
  private static final String FILE_KIND = "analysis-file";
  private static final int MAX_TEXT_CHARS = 12000;
  private static final int MAX_ATTACHMENTS = 5;
  private static final int HISTORY_LIMIT = 50;
  private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

  private final ApplicationStorePort store;
  private final AgentPort agent;
  public final Contracts contracts;
  private final Path filesRoot;
  private final String namespace;

  public AnalysisService(
      ApplicationStorePort store,
      AgentPort agent,
      Contracts contracts,
      @Value("${diligence.files.root:./storage/diligence}") String filesRoot,
      @Value("${analysis.platform.namespace:analysis}") String namespace)
      throws IOException {
    this.store = store;
    this.agent = agent;
    this.contracts = contracts;
    this.filesRoot = Paths.get(filesRoot).toAbsolutePath().normalize();
    this.namespace = namespace;
    java.nio.file.Files.createDirectories(this.filesRoot);
  }

  // ---------- 会话 ----------

  public ObjectNode create(String user) {
    return create(user, null);
  }

  /** 新建会话；body.kind 区分窗口场景（credit=征信 / bankflow=流水），供历史列表过滤。 */
  public ObjectNode create(String user, JsonNode body) {
    String kind = body == null ? "" : body.path("kind").asText("").trim();
    if (!kind.isEmpty() && !kind.matches("[A-Za-z0-9_-]{1,32}"))
      throw new Fault("INVALID_ARGUMENT", "会话类型无效", 400);
    String id = Json.id();
    ObjectNode session =
        Json.obj(
            "task_id",
            id,
            "owner",
            user,
            "kind",
            kind,
            "title",
            "新会话",
            "running",
            false,
            "unknown_run",
            false,
            "platform_sessions",
            Json.obj(),
            "messages",
            Json.arr(),
            "files",
            Json.arr());
    session.put("created_at", Json.now());
    session.put("last_used_at", session.path("created_at").asText());
    store.create(SESSION_KIND, id, user, session);
    return contracts.response(dto(internal(id)));
  }

  public ObjectNode session(String user, String task) {
    return contracts.response(dto(owned(user, task)));
  }

  /** 当前用户的历史会话（按 kind 过滤，最近使用在前，最多 50 条）。 */
  public ObjectNode list(String user, String kind) {
    String filter = kind == null ? "" : kind.trim();
    List<ObjectNode> visible = new ArrayList<>();
    for (ObjectNode s : store.list(SESSION_KIND, user)) {
      if (s.path("deleted").asBoolean()) continue;
      if (!filter.isEmpty() && !filter.equals(s.path("kind").asText(""))) continue;
      visible.add(s);
    }
    visible.sort(Comparator.comparing(AnalysisService::lastUsed).reversed());
    ArrayNode out = Json.arr();
    for (ObjectNode s : visible) {
      if (out.size() >= HISTORY_LIMIT) break;
      out.add(summary(s));
    }
    return contracts.response(Json.obj("sessions", out));
  }

  /** 软删除会话：历史列表不再展示；运行中的会话不允许删除。 */
  public ObjectNode delete(String user, String task) {
    owned(user, task);
    store.update(
        SESSION_KIND,
        task,
        s -> {
          if (s.path("running").asBoolean())
            throw new Fault("RUN_BUSY", "分析运行中，请完成后再删除会话", 409);
          s.put("deleted", true);
          return s;
        });
    return contracts.response(Json.obj("task_id", task, "deleted", true));
  }

  /**
   * 当前会话文件列表：平台工作区文件（含模型新建/输出的文件）与后端已登记材料合并，
   * 同名以平台条目为准；平台文件能力不可用时仅返回本地材料。
   */
  public ObjectNode files(String user, String task) {
    ObjectNode s = owned(user, task);
    Map<String, ObjectNode> merged = new LinkedHashMap<>();
    for (JsonNode file : s.path("files")) {
      String name = file.path("name").asText("");
      if (name.isEmpty()) continue;
      merged.put(
          name,
          Json.obj(
              "name", name,
              "size", file.path("size").asLong(),
              "source", "local",
              "file_id", file.path("file_id").asText("")));
    }
    boolean platformAvailable = false;
    String platformError = null;
    String platformSession = s.path("platform_sessions").path(sessionKey()).asText("");
    if (!platformSession.isEmpty()) {
      try {
        JsonNode listed = agent.files(platformSession);
        if (listed != null && listed.isArray()) {
          platformAvailable = true;
          for (JsonNode file : listed) {
            String name = file.path("name").asText("");
            if (name.isEmpty()) continue;
            ObjectNode entry = merged.get(name);
            if (entry == null) entry = Json.obj("name", name, "file_id", "");
            entry.put("size", file.path("size").asLong());
            entry.put("source", "platform");
            entry.put("path", file.path("path").asText(name));
            merged.put(name, entry);
          }
        }
      } catch (Fault error) {
        platformError = error.getMessage();
      }
    }
    ArrayNode out = Json.arr();
    for (ObjectNode entry : merged.values()) out.add(entry);
    ObjectNode response =
        Json.obj(
            "task_id", task,
            "platform_available", platformAvailable,
            "platform_error", platformError,
            "files", out);
    return contracts.response(response);
  }

  // ---------- 材料 ----------

  /** 登记分析材料：先上传平台会话工作区，再落盘并登记元数据；返回 file_id 供对话时引用。 */
  public ObjectNode upload(String user, String task, String name, byte[] content)
      throws IOException {
    owned(user, task);
    if (content == null || content.length == 0)
      throw new Fault("INVALID_ARGUMENT", "文件不能为空", 400);
    if (content.length > MAX_FILE_BYTES)
      throw new Fault("INVALID_ARGUMENT", "单个文件不能超过20MB", 400);
    if (!agent.fileCapable())
      throw new Fault(
          "PLATFORM_FILE_UNSUPPORTED",
          "当前部署未启用平台文件上传（需 oneagent 适配器或配置 diligence.platform.files-url），材料无法送达模型",
          503);
    String fileId = Json.id();
    String fileName = safeName(name);
    // 与消息共用同一平台会话；上传失败时直接报错，不会出现“模型看不到材料”的静默降级。
    String platformSession = ensurePlatformSession(task);
    if (platformSession.isEmpty())
      throw new Fault("PLATFORM_FILE_UNSUPPORTED", "无法建立平台会话，材料未上传", 503);
    String platformPath = agent.uploadFile(platformSession, fileName, content);
    java.nio.file.Files.write(filesRoot.resolve(fileId), content, StandardOpenOption.CREATE_NEW);
    ObjectNode file =
        Json.obj(
            "task_id",
            task,
            "file_id",
            fileId,
            "owner",
            task,
            "name",
            fileName,
            "size",
            content.length,
            "platform_path",
            platformPath);
    file.put("created_at", Json.now());
    store.create(FILE_KIND, fileId, task, file);
    store.update(
        SESSION_KIND,
        task,
        s -> {
          ((ArrayNode) s.get("files"))
              .add(
                  Json.obj(
                      "file_id",
                      fileId,
                      "name",
                      fileName,
                      "size",
                      content.length,
                      "created_at",
                      file.path("created_at").asText()));
          return touch(s);
        });
    return contracts.response(
        Json.obj("task_id", task, "file_id", fileId, "name", fileName, "size", content.length));
  }

  // ---------- 对话 ----------

  /** 校验并登记一次对话运行；SSE 传输由 web 层打开。 */
  public EventStreamTask prepare(String task, String user, JsonNode body) {
    owned(user, task);
    String text = body == null ? "" : body.path("text").asText().trim();
    validateText(text);
    List<ObjectNode> attachments =
        attachments(task, body == null ? null : body.path("attachment_ids"));
    String runId = Json.id();
    Diagnostics.info(
        "analysis.queued", "task_id", task, "file_count", attachments.size());
    return new EventStreamTask() {
      public Object[] diagnosticContext() {
        return new Object[] {"task_id", task, "run_id", runId};
      }

      public void rejected() {
        try {
          appendMessage(
              task, message("ASSISTANT", "服务繁忙，请稍后重试", "FAILED", runId, "RUN_BUSY"));
        } catch (RuntimeException ignored) {
          // 拒绝登记失败时不覆盖运输层的繁忙响应。
        }
      }

      public void execute(EventSink sink) throws Exception {
        executeChat(task, user, text, attachments, runId, sink);
      }
    };
  }

  private void executeChat(
      String task,
      String user,
      String text,
      List<ObjectNode> attachments,
      String runId,
      EventSink sink)
      throws Exception {
    AtomicInteger sequence = new AtomicInteger();
    boolean running = false;
    try {
      store.update(
          SESSION_KIND,
          task,
          s -> {
            if (s.path("running").asBoolean() || s.path("unknown_run").asBoolean())
              throw new Fault("RUN_BUSY", "会话运行中或上次结果待核实；可新建会话继续分析", 409);
            s.put("running", true);
            s.put("run_id", runId);
            if (s.path("messages").isEmpty()) s.put("title", title(text));
            ObjectNode userMessage = message("USER", text, "SUCCEEDED", runId, null);
            if (!attachments.isEmpty()) {
              ArrayNode names = Json.arr();
              for (ObjectNode file : attachments) names.add(file.path("name").asText(""));
              // 记录本轮引用的材料名称，历史会话回放时可展示“我上传的文件”。
              userMessage.set("files", names);
            }
            ((ArrayNode) s.get("messages")).add(userMessage);
            return touch(s);
          });
      running = true;
      send(sink, runId, sequence, "run.started", Json.obj());
      send(
          sink,
          runId,
          sequence,
          "run.progress",
          Json.obj("stage", "MODEL_CALL", "message", "大模型正在分析，请稍候…"));
      String sessionKey = sessionKey();
      String platformSession = ensurePlatformSession(task);
      JsonNode platformAttachment = platformAttachment(attachments);
      long started = System.nanoTime();
      ObjectNode reply;
      try {
        reply = agent.chat(new AgentRequest(platformSession, user, text, variables(task), platformAttachment));
        Diagnostics.info("analysis.completed", "elapsed_ms", Diagnostics.elapsed(started));
      } catch (Exception error) {
        Diagnostics.failure("analysis.failed", error, "elapsed_ms", Diagnostics.elapsed(started));
        throw error;
      }
      String answer = reply.path("answer").asText("");
      if (answer.trim().isEmpty())
        throw new Fault("PLATFORM_OUTPUT_BLOCKED", "平台未返回可显示的用户回答", 502);
      store.update(
          SESSION_KIND,
          task,
          s -> {
            ((ObjectNode) s.get("platform_sessions")).set(sessionKey, reply.get("session_id"));
            ((ArrayNode) s.get("messages")).add(message("ASSISTANT", answer, "SUCCEEDED", runId, null));
            return touch(s);
          });
      send(sink, runId, sequence, "answer.completed", Json.obj("content", answer));
      send(sink, runId, sequence, "run.completed", Json.obj());
    } catch (Fault error) {
      if (running) {
        boolean unknown = "RESULT_UNKNOWN".equals(error.code);
        store.update(
            SESSION_KIND,
            task,
            s -> {
              s.put("unknown_run", unknown);
              ((ArrayNode) s.get("messages"))
                  .add(message("ASSISTANT", error.getMessage(), "FAILED", runId, error.code));
              return touch(s);
            });
        send(
            sink,
            runId,
            sequence,
            unknown ? "run.unknown" : "run.failed",
            Json.obj("code", error.code, "message", error.getMessage(), "retryable", false));
      } else {
        // 运行被并发占用等前置拒绝：会话状态未被改动，仅告知本次请求失败。
        send(
            sink,
            runId,
            sequence,
            "run.failed",
            Json.obj("code", error.code, "message", error.getMessage(), "retryable", true));
      }
    } catch (Exception error) {
      if (!running) throw error;
      store.update(
          SESSION_KIND,
          task,
          s -> {
            s.put("unknown_run", true);
            ((ArrayNode) s.get("messages"))
                .add(message("ASSISTANT", "运行结果待核实", "FAILED", runId, "RESULT_UNKNOWN"));
            return touch(s);
          });
      send(
          sink,
          runId,
          sequence,
          "run.unknown",
          Json.obj("code", "RESULT_UNKNOWN", "message", "运行结果待核实", "retryable", true));
    } finally {
      if (running) {
        store.update(
            SESSION_KIND,
            task,
            s -> {
              s.put("running", false);
              return s;
            });
      }
    }
  }

  // ---------- 内部方法 ----------

  /** 会话归属校验；unknown_run 会话不允许继续发送（与尽调一致）。 */
  private ObjectNode owned(String user, String task) {
    ObjectNode s = internal(task);
    if (!s.path("owner").asText().equals(user))
      throw new Fault("FORBIDDEN", "无权访问会话", 403);
    return s;
  }

  ObjectNode internal(String task) {
    ObjectNode s = store.get(SESSION_KIND, task);
    if (s == null || s.path("deleted").asBoolean())
      throw new Fault("NOT_FOUND", "会话不存在", 404);
    return s;
  }

  private List<ObjectNode> attachments(String task, JsonNode ids) {
    List<ObjectNode> files = new ArrayList<>();
    if (ids == null || ids.isMissingNode() || ids.isNull()) return files;
    if (!ids.isArray()) throw new Fault("INVALID_ARGUMENT", "附件引用无效", 400);
    if (ids.size() > MAX_ATTACHMENTS)
      throw new Fault("INVALID_ARGUMENT", "单次最多分析5个附件", 400);
    for (JsonNode id : ids) {
      if (!id.isTextual()) throw new Fault("INVALID_ARGUMENT", "附件引用无效", 400);
      ObjectNode file = store.get(FILE_KIND, id.asText());
      if (file == null || !task.equals(file.path("task_id").asText()))
        throw new Fault("INVALID_ARGUMENT", "附件不属于当前会话", 400);
      files.add(file);
    }
    return files;
  }

  private JsonNode platformAttachment(List<ObjectNode> attachments)
      throws IOException {
    if (attachments.isEmpty()) return null;
    ArrayNode items = Json.arr();
    for (ObjectNode file : attachments) {
      String fileId = file.path("file_id").asText();
      ObjectNode item =
          Json.obj(
              "file_id",
              fileId,
              "name",
              file.path("name").asText("材料"),
              "reference_style",
              "user_upload");
      String platformPath = file.path("platform_path").asText("");
      if (platformPath.isEmpty()) {
        // 兼容登记时未上传平台的历史材料：对话时补传。
        item.set(
            "content",
            Json.M.valueToTree(java.nio.file.Files.readAllBytes(filesRoot.resolve(fileId))));
      } else {
        item.put("platform_path", platformPath);
      }
      items.add(item);
    }
    // 单附件保持对象形态；多附件传数组，由平台适配器逐个引用。
    return items.size() == 1 ? items.get(0) : items;
  }

  /** 确保会话已绑定平台会话（消息与文件共用同一 sessionId）；返回平台会话 id。 */
  private String ensurePlatformSession(String task) {
    String key = sessionKey();
    ObjectNode updated =
        store.update(
            SESSION_KIND,
            task,
            s -> {
              ObjectNode sessions = (ObjectNode) s.get("platform_sessions");
              if (sessions.path(key).asText("").isEmpty()) {
                String generated = agent.initialSession();
                if (!generated.isEmpty()) sessions.put(key, generated);
              }
              return s;
            });
    return updated.path("platform_sessions").path(key).asText("");
  }

  /** 平台会话存储键：与尽调一致，按 provider+命名空间隔离。 */
  private String sessionKey() {
    return agent.provider() + ":" + namespace;
  }

  private void send(
      EventSink sink, String runId, AtomicInteger sequence, String type, ObjectNode fields)
      throws IOException {
    int seq = sequence.incrementAndGet();
    ObjectNode event =
        Json.obj(
            "contract_version",
            "ANALYSIS_V1",
            "event_id",
            runId + ":" + seq,
            "run_id",
            runId,
            "sequence",
            seq,
            "type",
            type);
    fields.fieldNames().forEachRemaining(name -> event.set(name, fields.get(name)));
    sink.send(runId + ":" + seq, type, event);
  }

  private void appendMessage(String task, ObjectNode msg) {
    store.update(
        SESSION_KIND,
        task,
        s -> {
          ((ArrayNode) s.get("messages")).add(msg);
          return touch(s);
        });
  }

  private void validateText(String text) {
    if (text.isEmpty() || text.length() > MAX_TEXT_CHARS)
      throw new Fault("INVALID_ARGUMENT", "消息不能为空且须小于12000字", 400);
  }

  private static ObjectNode message(
      String role, String content, String status, String runId, String code) {
    ObjectNode msg =
        Json.obj(
            "message_id",
            Json.id(),
            "role",
            role,
            "content",
            content,
            "status",
            status,
            "run_id",
            runId);
    if (code != null) msg.put("code", code);
    msg.put("created_at", Json.now());
    return msg;
  }

  private static ArrayNode variables(String task) {
    return Json.arr(Json.obj("name", "runtime_task_id", "value", task));
  }

  private static String title(String text) {
    return text.length() <= 24 ? text : text.substring(0, 24);
  }

  private static String safeName(String name) {
    String value = name == null ? "" : name.trim();
    if (value.isEmpty()) return "材料";
    try {
      value = Paths.get(value).getFileName().toString();
    } catch (RuntimeException ignored) {
      // 路径非法时保留原始名称，仅做长度截断。
    }
    return value.length() > 200 ? value.substring(value.length() - 200) : value;
  }

  private static ObjectNode touch(ObjectNode s) {
    s.put("last_used_at", Json.now());
    return s;
  }

  private static java.time.Instant lastUsed(ObjectNode s) {
    try {
      String value = s.path("last_used_at").asText("");
      return value.isEmpty() ? java.time.Instant.EPOCH : java.time.Instant.parse(value);
    } catch (RuntimeException ignored) {
      return java.time.Instant.EPOCH;
    }
  }

  private static ObjectNode summary(ObjectNode s) {
    ObjectNode out =
        Json.obj(
            "task_id",
            s.path("task_id").asText(),
            "kind",
            s.path("kind").asText(""),
            "title",
            s.path("title").asText(""),
            "running",
            s.path("running").asBoolean(),
            "unknown_run",
            s.path("unknown_run").asBoolean(),
            "message_count",
            s.path("messages").size());
    out.put("created_at", s.path("created_at").asText(""));
    out.put("last_used_at", s.path("last_used_at").asText(""));
    return out;
  }

  private ObjectNode dto(ObjectNode s) {
    ObjectNode out =
        Json.obj(
            "task_id",
            s.path("task_id").asText(),
            "kind",
            s.path("kind").asText(""),
            "title",
            s.path("title").asText(),
            "running",
            s.path("running").asBoolean(),
            "unknown_run",
            s.path("unknown_run").asBoolean());
    out.put("created_at", s.path("created_at").asText(""));
    out.put("last_used_at", s.path("last_used_at").asText(""));
    out.set("messages", s.path("messages").deepCopy());
    out.set("files", s.path("files").deepCopy());
    return out;
  }
}
