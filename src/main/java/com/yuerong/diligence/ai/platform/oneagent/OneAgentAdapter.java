package com.yuerong.diligence.ai.platform.oneagent;

import com.yuerong.diligence.ai.platform.PlatformHttp;
import com.yuerong.diligence.ai.port.AgentPort;
import com.yuerong.diligence.common.Fault;
import com.yuerong.diligence.common.Json;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.yuerong.diligence.ai.model.AgentRequest;
import java.io.*;
import java.net.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OneAgentAdapter implements AgentPort {
  private static final Logger LOG = LoggerFactory.getLogger(OneAgentAdapter.class);
  private final PlatformHttp http;
  private final int timeout;
  private final boolean debugTrace;

  public OneAgentAdapter(String url, String headers, int timeout) {
    this(url, headers, timeout, true);
  }

  /** debugTrace 开启后平台回传调试事件，后端仅记录事件元数据。 */
  public OneAgentAdapter(String url, String headers, int timeout, boolean debugTrace) {
    http = new PlatformHttp(url, headers, timeout);
    this.timeout = timeout;
    this.debugTrace = debugTrace;
  }

  public String initialSession() {
    return Json.id();
  }

  public String provider() {
    return "oneagent";
  }

  public ObjectNode chat(AgentRequest request) {
    return exchange(request.session, request.user, request.text, request.variables);
  }

  /** 子类可替换文本或运行变量后发起同构请求；不要经 4 参重载回到覆写方。 */
  protected ObjectNode exchange(String session, String user, String text, ArrayNode variables) {
    String sid = session == null || session.isEmpty() ? Json.id() : session;
    if (debugTrace)
      LOG.info("[SSE-TRACE] 发送消息 session={} 文本长度={} 运行变量={}", sid,
          text == null ? 0 : text.length(), variableNames(variables));
    HttpURLConnection c = http.post(messageBody(sid, user, text, variables, debugTrace));
    try (InputStream input = c.getInputStream()) {
      return new OneAgentSseDecoder(debugTrace)
          .read(input, sid, System.currentTimeMillis() + timeout);
    } catch (IOException e) {
      throw new Fault("RESULT_UNKNOWN", "Agent流未完整结束", 502, e);
    } finally {
      c.disconnect();
    }
  }

  static ObjectNode messageBody(
      String sid, String user, String text, ArrayNode variables, boolean debugTrace) {
    return Json.obj(
        "sessionId", sid,
        "custID", user,
        "txt", text,
        "executionMode", "execute",
        "stream", true,
        "debugTrace", debugTrace,
        "config_variables", variables);
  }

  private static String variableNames(ArrayNode variables) {
    StringBuilder names = new StringBuilder();
    for (JsonNode variable : variables) {
      if (names.length() > 0) names.append(',');
      names.append(variable.path("name").asText());
    }
    return names.toString();
  }

}
