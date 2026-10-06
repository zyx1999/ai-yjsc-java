package com.yuerong.diligence.ai.platform.oneagent;

import com.yuerong.diligence.ai.platform.SseFrames;
import com.yuerong.diligence.common.Diagnostics;
import com.yuerong.diligence.common.Fault;
import com.yuerong.diligence.common.Json;
import com.yuerong.diligence.common.TraceText;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OneAgentSseDecoder {
  private static final Logger LOG = LoggerFactory.getLogger(OneAgentSseDecoder.class);

  private final boolean trace;
  private final String[] secrets;

  public OneAgentSseDecoder() {
    this(false);
  }

  /** trace=true 时逐帧记录 [SSE-TRACE] 日志（打码、单帧截断）；secrets 为需打码的敏感值。 */
  public OneAgentSseDecoder(boolean trace, String... secrets) {
    this.trace = trace;
    this.secrets = secrets == null ? new String[0] : secrets;
  }

  public ObjectNode read(InputStream input, String session, long deadline) throws IOException {
    ObjectNode state = Json.obj("started", false, "answer", null);
    final int[] events = {0};
    final String[] lastEvent = {"NONE"};
    long started = System.nanoTime();
    try (Diagnostics.Scope ignored = Diagnostics.scope("platform_session_id", session)) {
      Diagnostics.info("agent.sse_started");
      try {
      SseFrames.read(
          input,
          8388608,
          deadline,
          (event, raw) -> {
            events[0]++; lastEvent[0] = event;
            traceFrame(session, event, raw);
            if (events[0] <= 50 || "message".equals(event) || "done".equals(event)) logFrameMetadata(event, raw);
            if ("done".equals(event)) {
              if (!raw.equals("[DONE]") && !raw.equals("\"[DONE]\"")
                  || !state.path("started").asBoolean()
                  || !state.hasNonNull("answer"))
                throw new Fault("PLATFORM_PROTOCOL_ERROR", "无效的行内终止事件", 502);
              return true;
            }
            if (!java.util.Arrays.asList(
                    "start", "message", "progress", "router.progress", "error", "failed")
                .contains(event)) return false;
            JsonNode p = Json.parse(raw);
            if (("start".equals(event) || "message".equals(event))
                && p.has("session_id")
                && !session.equals(p.path("session_id").asText()))
              throw new Fault("PLATFORM_PROTOCOL_ERROR", "行内会话不匹配", 502);
            if ("start".equals(event)) {
              if (!session.equals(p.path("session_id").asText()) || state.path("started").asBoolean())
                throw new Fault("PLATFORM_PROTOCOL_ERROR", "无效的开始事件", 502);
              state.put("started", true);
            }
            if ("message".equals(event)) {
              // Validation can fail before a session starts; retain the documented error code.
              if (!p.path("ok").asBoolean() || !"completed".equals(p.path("status").asText()))
                throw failure(p);
              if (!state.path("started").asBoolean() || state.hasNonNull("answer"))
                throw new Fault("PLATFORM_PROTOCOL_ERROR", "行内Agent未成功完成", 502);
              if (p.path("ok").asBoolean()
                  && "completed".equals(p.path("status").asText())
                  && !p.path("message").asText().trim().isEmpty()) {
                state.set("answer", p.get("message"));
              } else {
                throw failure(p);
              }
            }
            if ("failed".equals(event) || "error".equals(event)) {
              throw new Fault("PLATFORM_PROTOCOL_ERROR", "行内Agent失败", 502);
            }
            return false;
          });
      Diagnostics.info("agent.sse_completed", "event_index", events[0], "elapsed_ms", Diagnostics.elapsed(started));
      return Json.obj("answer", state.get("answer"), "session_id", session);
      } catch (IOException | RuntimeException error) {
        Diagnostics.failure("agent.sse_failed", error, "event_index", events[0], "event_type", lastEvent[0],
            "result_seen", state.hasNonNull("answer"), "elapsed_ms", Diagnostics.elapsed(started));
        throw error;
      }
    }
  }

  /**
   * 逐帧调试日志：与平台实测报文同款两行格式（event / data），秘密打码并单帧截断。
   *
   * <p>仅写 {@code OneAgentSseDecoder} 日志通道，不影响 {@link Diagnostics} 的“有界元数据”诊断日志。
   */
  private void traceFrame(String session, String event, String raw) {
    if (!trace && !LOG.isDebugEnabled()) return;
    if ("trace".equals(event)) {
      traceSummary(session, raw);
      return;
    }
    logFrameLine(session, "event: " + event);
    logFrameLine(session, "data: " + TraceText.truncate(TraceText.mask(raw, secrets), TraceText.DEFAULT_LIMIT));
  }

  /** 调试追踪帧（trace）：info 仅输出摘要（体积大且含模型内部上下文），完整帧降为 debug 级别。 */
  private void traceSummary(String session, String raw) {
    String summary = "（调试追踪帧已省略）";
    try {
      JsonNode payload = Json.M.readTree(raw);
      if (payload != null && payload.isObject()) {
        summary = "（调试追踪帧已省略：turn_id=" + payload.path("turn_id").asText("")
            + "，event_count=" + payload.path("execution_trace").path("event_count").asInt(0)
            + "，request_id=" + payload.path("request_id").asText("") + "）";
      }
    } catch (IOException ignored) {
      // 非 JSON 帧：仅输出省略提示。
    }
    logFrameLine(session, "event: trace " + summary);
    if (LOG.isDebugEnabled())
      LOG.debug("[SSE-TRACE] session={} data: {}", session,
          TraceText.truncate(TraceText.mask(raw, secrets), TraceText.DEFAULT_LIMIT));
  }

  private void logFrameLine(String session, String line) {
    if (trace) LOG.info("[SSE-TRACE] session={} {}", session, line);
    else LOG.debug("[SSE-TRACE] session={} {}", session, line);
  }

  /** Diagnostics 通道的有界元数据日志（不含正文，供业务诊断与既有测试使用）。 */
  private void logFrameMetadata(String event, String raw) {
    String status = "", code = "";
    try {
      JsonNode payload = Json.M.readTree(raw);
      if (payload != null && payload.isObject()) {
        status = payload.path("status").asText(); code = payload.path("errorCode").asText();
      }
    } catch (IOException ignored) { /* Terminal data need not be JSON. */ }
    Diagnostics.info("agent.sse_frame", "event_type", event, "status", status, "error_code", code);
  }

  /** 行内错误码 → 可读失败；未知码不透出平台原文，见 10 号自主编排指南错误码表。 */
  private static Fault failure(JsonNode p) {
    String code = p.path("errorCode").asText("");
    if ("POLICY_SENSITIVE_WORD_BLOCKED".equals(code))
      return new Fault("PLATFORM_POLICY_BLOCKED", "输入被平台策略拦截，请调整后重试", 502);
    if ("ROUTER_BAD_REQUEST".equals(code))
      return new Fault("PLATFORM_BAD_REQUEST", "平台拒绝请求参数，请联系管理员", 502);
    if ("ROUTER_INTERNAL_ERROR".equals(code))
      return new Fault("PLATFORM_INTERNAL_ERROR", "平台内部异常，请稍后重试", 502);
    if ("MODEL_RATE_LIMITED".equals(code))
      return new Fault("PLATFORM_RATE_LIMITED", "模型服务限流，请稍后重试", 503);
    if ("TOOL_WORKFLOW_CALL_ERROR".equals(code))
      return new Fault("PLATFORM_WORKFLOW_ERROR", "工作流调用失败，请稍后重试", 502);
    if ("model_output_blocked".equals(p.path("status").asText()))
      return new Fault("PLATFORM_OUTPUT_BLOCKED", "平台未返回可显示的用户回答", 502);
    return new Fault("PLATFORM_PROTOCOL_ERROR", "行内Agent未成功完成", 502);
  }
}
