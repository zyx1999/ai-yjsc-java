package com.yuerong.diligence.ai.platform.oneagent;

import com.yuerong.diligence.common.Fault;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class PlatformDecoderTest {
  InputStream stream(String s) {
    return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void oneAgentRequiresSessionAndTerminator() throws Exception {
    String data =
        "event: start\r\n"
            + "data: {\"session_id\":\"s\"}\r\n\r\n"
            + "event: message\r\n"
            + "data:"
            + " {\"ok\":true,\"status\":\"completed\",\"message\":\"完成\",\"raw\":\"secret\"}\r\n\r\n";
    assertThrows(
        Fault.class,
        () -> new OneAgentSseDecoder().read(stream(data), "s", System.currentTimeMillis() + 2000));
    assertThrows(
        Fault.class,
        () ->
            new OneAgentSseDecoder()
                .read(
                    stream(data + "event: done\ndata: [DONE]\n\n"),
                    "different",
                    System.currentTimeMillis() + 2000));
    assertEquals(
        "完成",
        new OneAgentSseDecoder()
            .read(
                stream(data + "event: done\ndata: [DONE]\n\n"),
                "s",
                System.currentTimeMillis() + 2000)
            .path("answer")
            .asText());
    assertThrows(
        Fault.class,
        () ->
            new OneAgentSseDecoder()
                .read(
                    stream(data + "event: done\ndata: [DONE]"),
                    "s",
                    System.currentTimeMillis() + 2000));
  }

  @Test
  void oneAgentBlockedMessageIsNotSuccess() {
    String blocked =
        "event: start\ndata: {\"session_id\":\"s\"}\n\n"
            + "event: message\ndata: {\"ok\":false,\"status\":\"model_output_blocked\",\"message\":\"已拦截\",\"errorCode\":\"POLICY_SENSITIVE_WORD_BLOCKED\"}\n\n"
            + "event: done\ndata: [DONE]\n\n";
    assertThrows(
        Fault.class,
        () -> new OneAgentSseDecoder().read(stream(blocked), "s", System.currentTimeMillis() + 2000));
  }

  @Test
  void oneAgentRouterProgressMayUseInternalSessionId() throws Exception {
    String data =
        "event: start\ndata: {\"session_id\":\"s\"}\n\n"
            + "event: router.progress\ndata: {\"type\":\"workflow_called\",\"session_id\":\"web_internal\"}\n\n"
            + "event: message\ndata: {\"ok\":true,\"status\":\"completed\",\"message\":\"完成\"}\n\n"
            + "event: done\ndata: [DONE]\n\n";
    assertEquals(
        "完成",
        new OneAgentSseDecoder()
            .read(stream(data), "s", System.currentTimeMillis() + 2000)
            .path("answer")
            .asText());
  }

  @Test
  void oneAgentDebugTraceFramesAreIgnoredButStreamStillCompletes() throws Exception {
    // debugTrace=true 时平台会夹带 progress/debug 等调试事件；它们不参与业务判定，也不能中断解码。
    String data =
        "event: start\ndata: {\"session_id\":\"s\"}\n\n"
            + "event: progress\ndata: {\"type\":\"skill_loaded\",\"skill\":{\"name\":\"investigation\"},\"session_id\":\"s\"}\n\n"
            + "event: debug.trace\ndata: {\"type\":\"tool_call\",\"detail\":\"token SECRET-TOKEN-1234567890\"}\n\n"
            + "event: message\ndata: {\"ok\":true,\"status\":\"completed\",\"message\":\"完成\"}\n\n"
            + "event: done\ndata: [DONE]\n\n";
    assertEquals(
        "完成",
        new OneAgentSseDecoder(true, "SECRET-TOKEN-1234567890")
            .read(stream(data), "s", System.currentTimeMillis() + 2000)
            .path("answer")
            .asText());
  }

  @Test
  void undocumentedErrorEventsDoNotExposeTheirPayload() {
    String start = "event: start\ndata: {\"session_id\":\"s\"}\n\n";
    Fault business = assertThrows(Fault.class,
        () -> new OneAgentSseDecoder().read(stream(start
            + "event: error\ndata: {\"errorCode\":\"AI_SERVICE_BUSINESS_ERROR\",\"message\":\"企业名称存在歧义\"}\n\n"),
            "s", System.currentTimeMillis() + 2000));
    assertEquals("行内Agent失败", business.getMessage());
    Fault unknown = assertThrows(Fault.class,
        () -> new OneAgentSseDecoder().read(stream(start
            + "event: error\ndata: {\"errorCode\":\"OTHER\",\"message\":\"private detail\"}\n\n"),
            "s", System.currentTimeMillis() + 2000));
    assertEquals("行内Agent失败", unknown.getMessage());
  }

  @Test
  void oneAgentRejectsOpaqueDoneButAcceptsDocumentedJsonString() throws Exception {
    String response = "event: start\ndata: {\"session_id\":\"s\"}\n\n"
        + "event: message\ndata: {\"ok\":true,\"status\":\"completed\",\"message\":\"合成结果\"}\n\n";
    assertEquals("合成结果", new OneAgentSseDecoder().read(stream(response
        + "event: done\ndata: \"[DONE]\"\n\n"), "s", System.currentTimeMillis() + 2000).path("answer").asText());
    for (String ending : new String[] {"event: done\n\n", "event: done\ndata: opaque\n\n"})
      assertEquals("PLATFORM_PROTOCOL_ERROR", assertThrows(Fault.class,
          () -> new OneAgentSseDecoder().read(stream(response + ending), "s", System.currentTimeMillis() + 2000)).code);
  }

  @Test
  void documentedFailureBeforeStartRetainsErrorCode() {
    assertEquals("PLATFORM_BAD_REQUEST", assertThrows(Fault.class,
        () -> new OneAgentSseDecoder().read(stream("event: message\ndata: "
            + "{\"ok\":false,\"status\":\"error\",\"errorCode\":\"ROUTER_BAD_REQUEST\",\"message\":\"参数无效\"}\n\n"
            + "event: done\ndata: \"[DONE]\"\n\n"), "s", System.currentTimeMillis() + 2000)).code);
  }

  @Test
  void oneAgentGuideErrorCodesMapToReadableFailures() {
    String start = "event: start\ndata: {\"session_id\":\"s\"}\n\n";
    assertEquals(
        "PLATFORM_POLICY_BLOCKED",
        assertThrows(Fault.class,
            () -> new OneAgentSseDecoder().read(stream(start
                + "event: message\ndata: {\"ok\":false,\"status\":\"model_output_blocked\",\"message\":\"已拦截\",\"errorCode\":\"POLICY_SENSITIVE_WORD_BLOCKED\"}\n\n"),
                "s", System.currentTimeMillis() + 2000)).code);
    assertEquals(
        "PLATFORM_BAD_REQUEST",
        assertThrows(Fault.class,
            () -> new OneAgentSseDecoder().read(stream(start
                + "event: message\ndata: {\"ok\":false,\"status\":\"error\",\"message\":\"参数\",\"errorCode\":\"ROUTER_BAD_REQUEST\"}\n\n"),
                "s", System.currentTimeMillis() + 2000)).code);
    assertEquals(
        "PLATFORM_INTERNAL_ERROR",
        assertThrows(Fault.class,
            () -> new OneAgentSseDecoder().read(stream(start
                + "event: message\ndata: {\"ok\":false,\"status\":\"error\",\"message\":\"异常\",\"errorCode\":\"ROUTER_INTERNAL_ERROR\"}\n\n"),
                "s", System.currentTimeMillis() + 2000)).code);
    assertEquals(
        "PLATFORM_RATE_LIMITED",
        assertThrows(Fault.class,
            () -> new OneAgentSseDecoder().read(stream(start
                + "event: message\ndata: {\"ok\":false,\"status\":\"error\",\"message\":\"限流\",\"errorCode\":\"MODEL_RATE_LIMITED\"}\n\n"),
                "s", System.currentTimeMillis() + 2000)).code);
    assertEquals(
        "PLATFORM_WORKFLOW_ERROR",
        assertThrows(Fault.class,
            () -> new OneAgentSseDecoder().read(stream(start
                + "event: message\ndata: {\"ok\":false,\"status\":\"error\",\"message\":\"工作流\",\"errorCode\":\"TOOL_WORKFLOW_CALL_ERROR\"}\n\n"),
                "s", System.currentTimeMillis() + 2000)).code);
    assertEquals(
        "PLATFORM_OUTPUT_BLOCKED",
        assertThrows(Fault.class,
            () -> new OneAgentSseDecoder().read(stream(start
                + "event: message\ndata: {\"ok\":false,\"status\":\"model_output_blocked\",\"message\":\"兜底\"}\n\n"),
                "s", System.currentTimeMillis() + 2000)).code);
    assertEquals(
        "PLATFORM_PROTOCOL_ERROR",
        assertThrows(Fault.class,
            () -> new OneAgentSseDecoder().read(stream(start
                + "event: message\ndata: {\"ok\":false,\"status\":\"error\",\"message\":\"未知\",\"errorCode\":\"OTHER\"}\n\n"),
                "s", System.currentTimeMillis() + 2000)).code);
  }

  @Test
  void traceLogsMetadataWithoutMessageOrRawData() throws Exception {
    // 只保留事件元数据，不打印客户回复、工具正文或凭证。
    Logger logger = (Logger) LoggerFactory.getLogger("com.yuerong.diligence.diagnostics");
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      String secret = "SECRET-TOKEN-1234567890";
      String data =
          "event: start\ndata: {\"session_id\":\"s\"}\n\n"
              + "event: progress\ndata: {\"type\":\"skill_loaded\",\"message\":\"已加载 skill：investigation\",\"session_id\":\"s\"}\n\n"
              + "event: debug.trace\ndata: {\"type\":\"tool_call\",\"message\":\"第1行\\n第2行 " + secret + "\"}\n\n"
              + "event: message\ndata: {\"ok\":true,\"status\":\"completed\",\"message\":\"完成\"}\n\n"
              + "event: done\ndata: [DONE]\n\n";
      assertEquals(
          "完成",
          new OneAgentSseDecoder(true, secret)
              .read(stream(data), "s", System.currentTimeMillis() + 2000)
              .path("answer")
              .asText());
      StringBuilder logs = new StringBuilder();
      for (ILoggingEvent event : appender.list)
        logs.append(event.getFormattedMessage()).append('\n');
      assertTrue(logs.toString().contains("\"event_type\":\"progress\""));
      assertFalse(logs.toString().contains("第1行"));
      assertTrue(logs.toString().contains("\"event_type\":\"message\""));
      assertFalse(logs.toString().contains("完成"));
      assertFalse(logs.toString().contains(secret));
      assertFalse(logs.toString().contains("message=null"));
    } finally {
      logger.detachAppender(appender);
    }
  }
}
