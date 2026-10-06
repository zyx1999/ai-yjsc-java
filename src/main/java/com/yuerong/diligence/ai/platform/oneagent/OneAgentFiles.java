package com.yuerong.diligence.ai.platform.oneagent;

import com.yuerong.diligence.ai.platform.PlatformHttp;
import com.yuerong.diligence.common.Diagnostics;
import com.yuerong.diligence.common.Fault;
import com.yuerong.diligence.common.Json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 行内平台文件接口（与 Message 同 namespace）。后端只使用上传：附件先落到会话 Workspace，
 * 再在消息中引用相对路径；同一 sessionId 内同名文件会被覆盖。
 */
public class OneAgentFiles {
  private static final Logger LOG = LoggerFactory.getLogger(OneAgentFiles.class);
  private static final int MAX_RESPONSE_BYTES = 65536;
  private final String base, headerJson;
  private final int timeout;
  private final boolean debugTrace;

  public OneAgentFiles(String filesBase, String headerJson, int timeout) {
    this(filesBase, headerJson, timeout, false);
  }

  public OneAgentFiles(String filesBase, String headerJson, int timeout, boolean debugTrace) {
    this.base = filesBase == null ? "" : filesBase;
    this.headerJson = headerJson;
    this.timeout = timeout;
    this.debugTrace = debugTrace;
  }

  /** 由主接口地址推导同 namespace 的文件接口根（/api/v1/message → /api/v1/files）；不可推导返回空串。 */
  public static String filesBase(String messageUrl) {
    String suffix = "/message";
    if (messageUrl == null || !messageUrl.endsWith(suffix)) return "";
    return messageUrl.substring(0, messageUrl.length() - suffix.length()) + "/files";
  }

  /** 是否已配置可用的文件接口地址。 */
  public boolean configured() {
    return !base.isEmpty();
  }

  /** 上传附件，返回平台返回的会话内相对路径。 */
  public String upload(String sessionId, String name, byte[] content) {
    long started = System.nanoTime();
    try (Diagnostics.Scope ignored = Diagnostics.scope("platform_session_id", sessionId, "stage", "AGENT_FILE_UPLOAD")) {
      Diagnostics.info("agent.file_upload_started", "bytes", content == null ? 0 : content.length);
      try {
        String path = uploadFile(sessionId, name, content);
        if (debugTrace)
          LOG.info("[SSE-TRACE] 上传附件完成 session={} 文件名={} 大小={}B 平台相对路径={}",
              sessionId, name, content == null ? 0 : content.length, path);
        Diagnostics.info("agent.file_upload_completed", "elapsed_ms", Diagnostics.elapsed(started));
        return path;
      } catch (RuntimeException error) {
        Diagnostics.failure("agent.file_upload_failed", error, "elapsed_ms", Diagnostics.elapsed(started)); throw error;
      }
    }
  }

  /** 列举会话 Workspace 根目录文件（平台 files/list）。 */
  public ObjectNode list(String sessionId) {
    if (base.isEmpty())
      throw new Fault("PLATFORM_NOT_CONFIGURED", "尚未配置平台文件接口地址", 503);
    if (sessionId == null || sessionId.isEmpty())
      throw new Fault("INVALID_ARGUMENT", "缺少平台会话标识");
    HttpURLConnection c = null;
    try {
      String encoded = java.net.URLEncoder.encode(sessionId, "UTF-8");
      URL target = new URL(base + "/list?sessionId=" + encoded);
      if (!java.util.Arrays.asList("http", "https").contains(target.getProtocol())
          || target.getUserInfo() != null)
        throw new Fault("CONFIG_INVALID", "平台文件接口地址无效", 503);
      c = (HttpURLConnection) target.openConnection();
      c.setInstanceFollowRedirects(false);
      c.setConnectTimeout(5000);
      c.setReadTimeout(timeout);
      c.setRequestMethod("GET");
      PlatformHttp.applyHeaders(c, headerJson);
      Diagnostics.info("agent.file_list_response", "http_status", c.getResponseCode());
      if (c.getResponseCode() != 200)
        throw new Fault("PLATFORM_FILE_ERROR", "平台文件列表查询失败，请稍后重试", 502);
      JsonNode response =
          Json.parse(new String(readLimited(c.getInputStream()), StandardCharsets.UTF_8));
      JsonNode files = response.path("files");
      if (!files.isArray())
        throw new Fault("PLATFORM_PROTOCOL_ERROR", "平台文件列表响应无效", 502);
      return Json.obj("files", files.deepCopy());
    } catch (IOException e) {
      throw new Fault("PLATFORM_FILE_ERROR", "平台文件列表查询未完成", 502, e);
    } finally {
      if (c != null) c.disconnect();
    }
  }

  private String uploadFile(String sessionId, String name, byte[] content) {
    if (base.isEmpty())
      throw new Fault("PLATFORM_NOT_CONFIGURED", "尚未配置平台文件接口地址", 503);
    if (sessionId == null || sessionId.isEmpty() || content == null || content.length == 0)
      throw new Fault("INVALID_ARGUMENT", "缺少平台会话标识或文件内容");
    String boundary = "----diligence" + Long.toHexString(System.nanoTime());
    byte[] body = multipart(boundary, sessionId, safeName(name), content);
    HttpURLConnection c = null;
    try {
      URL target = new URL(base + "/upload");
      if (!java.util.Arrays.asList("http", "https").contains(target.getProtocol())
          || target.getUserInfo() != null)
        throw new Fault("CONFIG_INVALID", "平台文件接口地址无效", 503);
      c = (HttpURLConnection) target.openConnection();
      c.setInstanceFollowRedirects(false);
      c.setConnectTimeout(5000);
      c.setReadTimeout(timeout);
      c.setRequestMethod("POST");
      c.setDoOutput(true);
      c.setFixedLengthStreamingMode(body.length);
      c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
      PlatformHttp.applyHeaders(c, headerJson);
      try (OutputStream out = c.getOutputStream()) {
        out.write(body);
      }
      Diagnostics.info("agent.file_upload_response", "http_status", c.getResponseCode());
      if (c.getResponseCode() != 200)
        throw new Fault("PLATFORM_FILE_ERROR", "平台文件上传失败，请稍后重试", 502);
      JsonNode file = Json.parse(new String(readLimited(c.getInputStream()), StandardCharsets.UTF_8)).path("file");
      String path = file.path("path").asText("");
      if (path.isEmpty() || path.startsWith("/") || path.contains(".."))
        throw new Fault("PLATFORM_PROTOCOL_ERROR", "平台未返回有效文件路径", 502);
      return path;
    } catch (IOException e) {
      throw new Fault("PLATFORM_FILE_ERROR", "平台文件上传未完成，请核实运行状态", 502, e);
    } finally {
      if (c != null) c.disconnect();
    }
  }

  private static byte[] multipart(String boundary, String sessionId, String name, byte[] content) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      out.write(
          ("--" + boundary + "\r\n"
              + "Content-Disposition: form-data; name=\"sessionId\"\r\n\r\n"
              + sessionId + "\r\n").getBytes(StandardCharsets.UTF_8));
      out.write(
          ("--" + boundary + "\r\n"
              + "Content-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\n"
              + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
      out.write(content);
      out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] readLimited(InputStream input) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] chunk = new byte[4096];
    int total = 0, n;
    while ((n = input.read(chunk)) != -1) {
      total += n;
      if (total > MAX_RESPONSE_BYTES)
        throw new Fault("PLATFORM_PROTOCOL_ERROR", "平台文件响应超限", 502);
      out.write(chunk, 0, n);
    }
    return out.toByteArray();
  }

  private static String safeName(String name) {
    String value = name == null || name.trim().isEmpty() ? "材料.pdf" : name.trim();
    value = value.replaceAll("[\\r\\n\"\\\\]", "_");
    return value.length() > 120 ? value.substring(value.length() - 120) : value;
  }
}
