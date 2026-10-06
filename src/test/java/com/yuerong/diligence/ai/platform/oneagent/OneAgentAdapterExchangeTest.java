package com.yuerong.diligence.ai.platform.oneagent;

import com.yuerong.diligence.common.Json;
import com.yuerong.diligence.service.diligence.DiligenceAgentContext;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 用本地桩服务核对发往平台的消息体：本地运行变量、debugTrace 与调试事件解码。 */
class OneAgentAdapterExchangeTest {
  private static final String SSE =
      "event: start\ndata: {\"session_id\":\"sess-1\"}\n\n"
          + "event: progress\ndata: {\"type\":\"skill_loaded\",\"session_id\":\"sess-1\"}\n\n"
          + "event: debug.trace\ndata: {\"type\":\"tool_call\",\"session_id\":\"sess-1\"}\n\n"
          + "event: message\ndata: {\"ok\":true,\"status\":\"completed\",\"message\":\"完成\"}\n\n"
          + "event: done\ndata: [DONE]\n\n";

  private static HttpServer startServer(AtomicReference<String> captured) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/message",
        exchange -> {
          captured.set(new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8));
          byte[] body = SSE.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    return server;
  }

  @Test
  void localMessageCarriesRuntimeContextAndDebugTrace() throws Exception {
    AtomicReference<String> captured = new AtomicReference<>();
    HttpServer server = startServer(captured);
    try {
      OneAgentAdapter adapter =
          new OneAgentAdapter(
              "http://127.0.0.1:" + server.getAddress().getPort() + "/message", "{}", 5000, true);
      ObjectNode reply = adapter.chat(DiligenceAgentContext.request("sess-1", "user-1", "你好", "task-1", null));
      assertEquals("完成", reply.path("answer").asText());

      JsonNode request = Json.parse(captured.get());
      assertEquals("sess-1", request.path("sessionId").asText());
      assertEquals("execute", request.path("executionMode").asText());
      assertTrue(request.path("debugTrace").asBoolean());
      JsonNode variables = request.get("config_variables");
      assertEquals("task-1", value(variables, "runtime_task_id"));
      assertNull(value(variables, "runtime_run_token"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void debugTraceCanBeDisabledByConfiguration() throws Exception {
    AtomicReference<String> captured = new AtomicReference<>();
    HttpServer server = startServer(captured);
    try {
      OneAgentAdapter adapter =
          new OneAgentAdapter(
              "http://127.0.0.1:" + server.getAddress().getPort() + "/message", "{}", 5000, false);
      adapter.chat(DiligenceAgentContext.request("sess-1", "user-1", "你好", "task-1", null));
      JsonNode request = Json.parse(captured.get());
      assertFalse(request.path("debugTrace").asBoolean());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void nativePlatformCarriesContextOnlyInRequestVariables() throws Exception {
    AtomicReference<String> captured = new AtomicReference<>();
    HttpServer server = startServer(captured);
    try {
      BankOneAgentAdapter adapter = new BankOneAgentAdapter(
          "http://127.0.0.1:" + server.getAddress().getPort() + "/message", "", "{}", 5000, false);
      adapter.chat(DiligenceAgentContext.request("sess-1", "user-1", "你好", "private-task", null));
      JsonNode request = Json.parse(captured.get());
      assertEquals("private-task", value(request.path("config_variables"), "runtime_task_id"));
      assertNull(value(request.path("config_variables"), "runtime_run_token"));
      assertEquals("你好", request.path("txt").asText());
    } finally { server.stop(0); }
  }

  @Test
  void nativeAttachmentPreservesValidatedFileIdAndPlatformSession() throws Exception {
    AtomicReference<String> captured = new AtomicReference<>();
    AtomicReference<String> uploaded = new AtomicReference<>();
    HttpServer server = startServer(captured);
    server.createContext("/files/upload", exchange -> {
      uploaded.set(new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8));
      byte[] body = "{\"file\":{\"path\":\"material.pdf\"}}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      BankOneAgentAdapter adapter = new BankOneAgentAdapter(base + "/message", base + "/files", "{}", 5000, false);
      for (String role : new String[]{"FINANCIAL", "CREDIT"}) {
        ObjectNode attachment = Json.obj("file_id", "file-1", "role", role, "name", "material.pdf");
        attachment.put("content", "%PDF fixture".getBytes(StandardCharsets.UTF_8));
        adapter.chat(DiligenceAgentContext.request("sess-1", "user-1", "处理材料", "task-1", attachment));
        JsonNode request = Json.parse(captured.get());
        assertEquals("file-1", value(request.path("config_variables"), "input_file_id"));
        assertEquals(role, value(request.path("config_variables"), "input_file_role"));
        assertEquals("task-1", value(request.path("config_variables"), "runtime_task_id"));
        assertEquals("sess-1", request.path("sessionId").asText());
        String text = request.path("txt").asText();
        assertTrue(text.startsWith("处理材料"));
        assertTrue(text.contains("material.pdf"));
        assertTrue(text.contains("FINANCIAL".equals(role) ? "financial-report" : "enterprise-credit"));
        assertTrue(text.contains("current_attachment"));
        assertTrue(text.contains("不要重复调用 upload"));
        assertFalse(text.contains("请先读取该文件"));
        assertTrue(uploaded.get().contains("sess-1"));
      }
      adapter.chat(DiligenceAgentContext.request("sess-1", "user-1", "查询调查表", "task-1", null));
      JsonNode plain = Json.parse(captured.get());
      assertEquals("查询调查表", plain.path("txt").asText());
      assertEquals(1, plain.path("config_variables").size());
      assertNull(value(plain.path("config_variables"), "input_file_id"));
      assertEquals(BankOneAgentAdapter.attachmentReference("a.pdf", "a.pdf", ""),
          BankOneAgentAdapter.attachmentReference("a.pdf", "a.pdf", "请先读取该文件，再继续处理。"));
    } finally { server.stop(0); }
  }

  private static String value(JsonNode variables, String name) {
    for (JsonNode variable : variables)
      if (name.equals(variable.path("name").asText())) return variable.path("value").asText();
    return null;
  }

  private static byte[] readAll(InputStream input) throws java.io.IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    int read;
    while ((read = input.read(buffer)) != -1) out.write(buffer, 0, read);
    return out.toByteArray();
  }
}
