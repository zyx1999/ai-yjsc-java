package com.yuerong.diligence.service.diligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuerong.diligence.ai.model.AgentRequest;
import com.yuerong.diligence.ai.port.AgentPort;
import com.yuerong.diligence.common.Fault;
import com.yuerong.diligence.common.Json;
import com.yuerong.diligence.common.stream.EventStreamTask;
import com.yuerong.diligence.model.diligence.Contracts;
import com.yuerong.diligence.support.InMemoryStore;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** 文答窗口会话、材料与对话的隔离测试；不依赖 Servlet/JDBC，只验证业务编排与事件契约。 */
class AnalysisServiceTest {
  @TempDir Path tempDir;
  InMemoryStore store;
  AgentPort agent;
  AnalysisService analysis;
  String task;

  @BeforeEach void setup() throws Exception {
    store = new InMemoryStore();
    agent = mock(AgentPort.class);
    when(agent.provider()).thenReturn("fixture");
    when(agent.initialSession()).thenReturn("platform-session");
    when(agent.fileCapable()).thenReturn(true);
    when(agent.uploadFile(any(), any(), any())).thenAnswer(call -> call.getArgument(1));
    when(agent.chat(any())).thenReturn(Json.obj("session_id", "platform-session", "answer", "分析完成"));
    analysis = new AnalysisService(store, agent, new Contracts(), tempDir.toString(), "analysis");
    task = analysis.create("owner").at("/data/task_id").asText();
  }

  List<JsonNode> execute(EventStreamTask run) throws Exception {
    List<JsonNode> events = new ArrayList<>();
    run.execute((id, type, event) -> {
      assertEquals(event.path("event_id").asText(), id);
      assertEquals(event.path("type").asText(), type);
      events.add(event.deepCopy());
    });
    return events;
  }

  List<String> types(List<JsonNode> events) {
    List<String> out = new ArrayList<>();
    for (JsonNode event : events) out.add(event.path("type").asText());
    return out;
  }

  @Test void createsAndGuardsSessions() {
    JsonNode loaded = analysis.session("owner", task);
    assertEquals("SUCCEEDED", loaded.path("status").asText());
    assertEquals("新会话", loaded.at("/data/title").asText());
    assertEquals(0, loaded.at("/data/messages").size());
    Fault fault = assertThrows(Fault.class, () -> analysis.session("intruder", task));
    assertEquals("FORBIDDEN", fault.code);
  }

  @Test void chatCompletesAndPersistsMessages() throws Exception {
    List<JsonNode> events =
        execute(analysis.prepare(task, "owner", Json.obj("text", "分析个人征信")));
    assertEquals(
        Arrays.asList("run.started", "run.progress", "answer.completed", "run.completed"),
        types(events));
    for (int i = 0; i < events.size(); i++)
      assertEquals(i + 1, events.get(i).path("sequence").asInt());
    assertEquals("分析完成", events.get(2).path("content").asText());

    JsonNode data = analysis.session("owner", task).path("data");
    JsonNode messages = data.path("messages");
    assertEquals(2, messages.size());
    assertEquals("USER", messages.get(0).path("role").asText());
    assertEquals("分析个人征信", messages.get(0).path("content").asText());
    assertEquals("ASSISTANT", messages.get(1).path("role").asText());
    assertEquals("分析完成", messages.get(1).path("content").asText());
    assertEquals("SUCCEEDED", messages.get(1).path("status").asText());
    assertFalse(data.path("running").asBoolean());
    assertEquals("分析个人征信", data.path("title").asText());
    assertEquals(
        "platform-session",
        store.get("analysis-session", task).at("/platform_sessions/fixture:analysis").asText());
  }

  @Test void uploadsMaterialsAndPassesAllAttachmentsToPlatform() throws Exception {
    String first =
        analysis.upload("owner", task, "征信.pdf", "report".getBytes()).at("/data/file_id").asText();
    String second =
        analysis.upload("owner", task, "流水.xlsx", "flow".getBytes()).at("/data/file_id").asText();
    assertEquals(2, analysis.session("owner", task).at("/data/files").size());

    List<JsonNode> events =
        execute(
            analysis.prepare(
                task,
                "owner",
                Json.obj("text", "分析这两个材料", "attachment_ids", Json.arr(first, second))));
    assertEquals("run.completed", types(events).get(types(events).size() - 1));
    ArgumentCaptor<AgentRequest> request = ArgumentCaptor.forClass(AgentRequest.class);
    verify(agent).chat(request.capture());
    JsonNode attachment = request.getValue().attachment;
    assertTrue(attachment.isArray());
    assertEquals(2, attachment.size());
    assertEquals("征信.pdf", attachment.get(0).path("platform_path").asText());
    assertEquals("流水.xlsx", attachment.get(1).path("platform_path").asText());
    assertEquals("user_upload", attachment.get(0).path("reference_style").asText());
    // 上传登记时即写入平台会话工作区，对话时仅引用路径。
    verify(agent).uploadFile("platform-session", "征信.pdf", "report".getBytes());
    verify(agent).uploadFile("platform-session", "流水.xlsx", "flow".getBytes());
  }

  @Test void uploadRejectedWhenPlatformHasNoFileCapability() {
    when(agent.fileCapable()).thenReturn(false);
    Fault fault =
        assertThrows(Fault.class, () -> analysis.upload("owner", task, "x.pdf", "x".getBytes()));
    assertEquals("PLATFORM_FILE_UNSUPPORTED", fault.code);
    assertEquals(0, analysis.session("owner", task).at("/data/files").size());
  }

  @Test void legacyFileWithoutPlatformPathIsUploadedAtChat() throws Exception {
    String fileId =
        analysis.upload("owner", task, "旧材料.pdf", "legacy".getBytes())
            .at("/data/file_id")
            .asText();
    store.update(
        "analysis-file",
        fileId,
        file -> {
          file.remove("platform_path");
          return file;
        });
    execute(
        analysis.prepare(
            task, "owner", Json.obj("text", "分析", "attachment_ids", Json.arr(fileId))));
    ArgumentCaptor<AgentRequest> request = ArgumentCaptor.forClass(AgentRequest.class);
    verify(agent).chat(request.capture());
    JsonNode attachment = request.getValue().attachment;
    assertTrue(attachment.path("content").isBinary());
    assertEquals("legacy", new String(attachment.path("content").binaryValue()));
    assertTrue(attachment.path("platform_path").asText("").isEmpty());
    // 补传由适配器在发消息时完成：服务层仅在登记时上传过一次（该用例人工移除了平台路径）。
    verify(agent).uploadFile(eq("platform-session"), eq("旧材料.pdf"), any());
  }

  @Test void filesListMergesPlatformAndLocalUploads() throws Exception {
    analysis.upload("owner", task, "陈国雄个人征信.pdf", "report".getBytes());
    when(agent.files(any()))
        .thenReturn(
            Json.arr(
                Json.obj("path", "陈国雄个人征信.pdf", "name", "陈国雄个人征信.pdf", "type", "file", "size", 123),
                Json.obj("path", "分析报告.md", "name", "分析报告.md", "type", "file", "size", 456)));
    // 先触发一次对话，建立平台会话（文件列表按平台会话查询）。
    execute(analysis.prepare(task, "owner", Json.obj("text", "分析个人征信")));
    JsonNode data = analysis.files("owner", task).path("data");
    assertTrue(data.path("platform_available").asBoolean());
    JsonNode files = data.path("files");
    assertEquals(2, files.size());
    assertEquals("陈国雄个人征信.pdf", files.get(0).path("name").asText());
    assertEquals("platform", files.get(0).path("source").asText());
    assertEquals(123, files.get(0).path("size").asLong());
    assertEquals("分析报告.md", files.get(1).path("name").asText());
    assertEquals("platform", files.get(1).path("source").asText());
  }

  @Test void filesListFallsBackToLocalWhenPlatformUnavailable() throws Exception {
    analysis.upload("owner", task, "流水.xlsx", "flow".getBytes());
    JsonNode data = analysis.files("owner", task).path("data");
    assertFalse(data.path("platform_available").asBoolean());
    assertEquals(1, data.path("files").size());
    assertEquals("local", data.path("files").get(0).path("source").asText());
    assertEquals("流水.xlsx", data.path("files").get(0).path("name").asText());
  }

  @Test void unknownResultIsPersistedAndBlocksNextRun() throws Exception {
    when(agent.chat(any())).thenThrow(new Fault("RESULT_UNKNOWN", "Agent流未完整结束", 502));
    List<JsonNode> events = execute(analysis.prepare(task, "owner", Json.obj("text", "继续分析")));
    assertEquals("run.unknown", types(events).get(types(events).size() - 1));
    JsonNode data = analysis.session("owner", task).path("data");
    assertTrue(data.path("unknown_run").asBoolean());
    assertFalse(data.path("running").asBoolean());
    assertEquals("FAILED", data.path("messages").get(1).path("status").asText());

    List<JsonNode> second = execute(analysis.prepare(task, "owner", Json.obj("text", "再次发送")));
    assertEquals(Arrays.asList("run.failed"), types(second));
    assertEquals("RUN_BUSY", second.get(0).path("code").asText());
    verify(agent, times(1)).chat(any());
  }

  @Test void uploadRejectsForeignSessionAndEmptyContent() throws Exception {
    String other = analysis.create("other").at("/data/task_id").asText();
    Fault forbidden =
        assertThrows(Fault.class, () -> analysis.upload("owner", other, "x.pdf", "x".getBytes()));
    assertEquals("FORBIDDEN", forbidden.code);
    Fault empty =
        assertThrows(Fault.class, () -> analysis.upload("owner", task, "x.pdf", new byte[0]));
    assertEquals("INVALID_ARGUMENT", empty.code);
  }

  @Test void rejectsEmptyTextAndUnknownAttachments() throws Exception {
    Fault blank =
        assertThrows(Fault.class, () -> analysis.prepare(task, "owner", Json.obj("text", "  ")));
    assertEquals("INVALID_ARGUMENT", blank.code);
    Fault missing =
        assertThrows(
            Fault.class,
            () ->
                analysis.prepare(
                    task,
                    "owner",
                    Json.obj("text", "分析", "attachment_ids", Json.arr("missing-file"))));
    assertEquals("INVALID_ARGUMENT", missing.code);
  }

  @Test void rejectedRunStoresBusyMessage() {
    EventStreamTask run = analysis.prepare(task, "owner", Json.obj("text", "排队被打断"));
    run.rejected();
    JsonNode messages = analysis.session("owner", task).at("/data/messages");
    assertEquals(1, messages.size());
    assertEquals("ASSISTANT", messages.get(0).path("role").asText());
    assertEquals("FAILED", messages.get(0).path("status").asText());
    assertEquals("RUN_BUSY", messages.get(0).path("code").asText());
  }
}
