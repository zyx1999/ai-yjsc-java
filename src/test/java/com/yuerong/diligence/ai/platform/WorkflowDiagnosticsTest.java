package com.yuerong.diligence.ai.platform;

import com.yuerong.diligence.ai.platform.oneagent.OneAgentSseDecoder;
import com.yuerong.diligence.common.Diagnostics;
import com.yuerong.diligence.common.Fault;
import com.yuerong.diligence.common.Json;
import com.yuerong.diligence.service.diligence.DiligenceModelService;

import static org.junit.jupiter.api.Assertions.*;
import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

class WorkflowDiagnosticsTest {
  Logger logger;
  Level previous;
  ListAppender<ILoggingEvent> logs;
  @BeforeEach void capture() {
    logger = (Logger) LoggerFactory.getLogger("com.yuerong.diligence.diagnostics");
    previous = logger.getLevel(); logger.setLevel(Level.INFO);
    logs = new ListAppender<>(); logs.setContext(logger.getLoggerContext()); logs.start(); logger.addAppender(logs);
  }
  @AfterEach void cleanup() { logger.detachAppender(logs); logs.stop(); logger.setLevel(previous); }
  String output() { StringBuilder out = new StringBuilder(); logs.list.forEach(e -> out.append(e.getFormattedMessage()).append('\n')); return out.toString(); }
  InputStream stream(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
  String result(String value) { return "event: message\ndata: " + Json.obj("additional_kwargs", Json.obj("node_title", "模型结果", "node_id", "end", "node_output", Json.obj("output", value))) + "\n\n"; }

  @Test void distinguishesEventJsonFromResultJsonAndIgnoresDoneData() throws Exception {
    assertThrows(Fault.class, () -> WorkflowModelAdapter.consume(stream("event: message\ndata: {'PRIVATE_TEXT':True}\n\n"), System.currentTimeMillis()+2000));
    assertTrue(output().contains("SSE_EVENT_JSON"));
    assertFalse(output().contains("PRIVATE_TEXT")); logs.list.clear();
    assertThrows(Fault.class, () -> WorkflowModelAdapter.consume(stream(result(Json.obj("output", "{'PRIVATE_TEXT':True}").toString())), System.currentTimeMillis()+2000));
    assertTrue(output().contains("RESULT_UNWRAP_JSON"));
    assertTrue(output().contains("JsonParseException"));
    assertFalse(output().contains("PRIVATE_TEXT")); logs.list.clear();
    WorkflowModelAdapter.consume(stream(result("{\"value\":1}") + "event: done\ndata: PRIVATE_NON_JSON_TERMINATOR\n\n"), System.currentTimeMillis()+2000);
    assertTrue(output().contains("model.sse_done"));
    assertFalse(output().contains("model.sse_failed"));
    assertFalse(output().contains("PRIVATE_NON_JSON_TERMINATOR")); logs.list.clear();
    assertThrows(Fault.class, () -> WorkflowModelAdapter.consume(stream("event: done\n\n"), System.currentTimeMillis()+2000));
    assertTrue(output().contains("\"result_seen\":false"));
  }

  @Test void recordsHttpStatusAndSchemaFailureWithoutBodiesHeadersOrUrls() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/fail", e -> { e.sendResponseHeaders(403, -1); e.close(); });
    server.createContext("/schema", e -> {
      byte[] body = (result("{\"PRIVATE_RESULT\":1}") + "event: done\n\n").getBytes(StandardCharsets.UTF_8);
      e.getResponseHeaders().add("Content-Type", "text/event-stream"); e.sendResponseHeaders(200, body.length);
      e.getResponseBody().write(body); e.close();
    }); server.start();
    try {
      for (String path : new String[]{"fail", "schema"}) {
        logs.list.clear();
        WorkflowModelAdapter adapter = new WorkflowModelAdapter( "http://127.0.0.1:"+server.getAddress().getPort()+"/"+path,
            "", "{\"Authorization\":\"PRIVATE_TOKEN\"}", 2000);
        try (Diagnostics.Scope scope = Diagnostics.scope("request_id", "request-1", "operation_id", "operation-1", "file_id", "file-1", "page", 2)) {
          assertThrows(Fault.class, () -> new DiligenceModelService(adapter).infer("investigation.ai_summary", "PRIVATE_INSTRUCTION", Json.obj("text", "PRIVATE_PDF"),
              Json.obj("type", "array"), null));
        }
        assertTrue(output().contains("model.failed"));
        assertTrue(output().contains("operation-1"));
        assertTrue(output().contains("\"call_id\""));
        assertTrue(output().contains(path.equals("fail") ? "\"http_status\":403" : "SCHEMA_VALIDATE"));
        assertFalse(output().contains("PRIVATE_"));
        assertFalse(output().contains("http://"));
      }
    } finally { server.stop(0); }
  }

  @Test void agentTraceOnlyLogsMetadataAndAlwaysLogsIncompleteStreams() throws Exception {
    String text = "event: start\ndata: {\"session_id\":\"session-1\"}\n\n"
        + "event: message\ndata: {\"session_id\":\"session-1\",\"ok\":true,\"status\":\"completed\",\"message\":\"PRIVATE_CUSTOMER_REPLY\"}\n\n"
        + "event: done\ndata: [DONE]\n\n";
    new OneAgentSseDecoder(true).read(stream(text), "session-1", System.currentTimeMillis()+2000);
    assertTrue(output().contains("agent.sse_completed"));
    assertFalse(output().contains("PRIVATE_CUSTOMER_REPLY"));
    logs.list.clear();
    assertThrows(Fault.class, () -> new OneAgentSseDecoder(false).read(stream(""), "session-1", System.currentTimeMillis()+2000));
    assertTrue(output().contains("agent.sse_failed"));
    assertTrue(output().contains("RESULT_UNKNOWN"));
  }
}
