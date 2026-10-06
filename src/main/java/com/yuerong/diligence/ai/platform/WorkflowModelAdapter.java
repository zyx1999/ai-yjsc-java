package com.yuerong.diligence.ai.platform;

import com.yuerong.diligence.ai.port.ModelInferencePort;
import com.yuerong.diligence.common.Diagnostics;
import com.yuerong.diligence.common.Fault;
import com.yuerong.diligence.common.Json;
import com.yuerong.diligence.common.validation.JsonSchemaValidator;
import com.yuerong.diligence.ai.model.ModelRequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** The independent MODEL-INFERENCE workflow, never the autonomous agent message endpoint. */
public class WorkflowModelAdapter implements ModelInferencePort {
  private final String url, headers, documentBaseUrl;
  private final int timeout;

  public WorkflowModelAdapter(
      String url,
      String documentBaseUrl,
      String headers,
      int timeout) {
    this.documentBaseUrl = documentBaseUrl.replaceAll("/+$", ""); this.url = url; this.headers = headers;
    this.timeout = Math.max(1000, Math.min(timeout, 600000));
  }

  public boolean visionReady() { return !documentBaseUrl.isEmpty(); }

  public JsonNode infer(ModelRequest request) {
    String taskCode = request.taskCode, instruction = request.instruction;
    JsonNode input = request.input, schema = request.schema, image = request.image;
    long started = System.nanoTime();
    String stage = "CONFIG_VALIDATE";
    boolean textWorkflow = request.workflow == ModelRequest.Workflow.TEXT;
    HttpURLConnection connection = null;
    try (Diagnostics.Scope ignored = Diagnostics.scope("call_id", Json.id(), "model_task", taskCode,
        "workflow_kind", textWorkflow ? "TEXT" : "DOCUMENT",
        "mode", image != null && !image.isNull() ? "IMAGE" : "TEXT", "timeout_ms", timeout)) {
      Diagnostics.info("model.started");
      try {
        if (textWorkflow && image != null && !image.isNull())
          throw new Fault("INVALID_ARGUMENT", "文本模型工作流不接受图片", 400);
        String selectedUrl = textWorkflow ? url : documentEndpoint("use_as_tool");
        checked(selectedUrl);
        long deadline = System.currentTimeMillis() + timeout;
        String session = Json.id();
        String endpoint = selectedUrl;
        ObjectNode content = Json.obj("instruction", instruction, "input_data", input, "response_schema", schema);
        ObjectNode body;
        if (image != null && !image.isNull()) {
          stage = "IMAGE_PREPARE";
          session = prepareImage(image, deadline);
          endpoint = documentEndpoint("chat");
          body = Json.obj("data", Json.obj("session_id", session, "txt", content.toString(),
              "files", Json.arr(), "stream", true));
        } else {
          body = Json.obj("session_id", session, "txt", content.toString(), "stream", true,
              "config_variables", Json.arr());
        }
        stage = "HTTP_CONNECT";
        try (Diagnostics.Scope sessionScope = Diagnostics.scope("model_session_id", session)) {
          connection = new PlatformHttp(endpoint, headers, remaining(deadline)).post(body);
          stage = "SSE_READ";
          JsonNode result = consume(connection.getInputStream(), deadline);
          stage = "SCHEMA_VALIDATE";
          new JsonSchemaValidator(schema).validate(schema, result, "model_output");
          Diagnostics.info("model.completed", "elapsed_ms", Diagnostics.elapsed(started));
          return result;
        }
      } catch (IOException e) {
        Diagnostics.failure("model.failed", e, "stage", stage, "elapsed_ms", Diagnostics.elapsed(started));
        throw new Fault("MODEL_UNAVAILABLE", "模型工作流连接中断，未取得完整结果", 502, e);
      } catch (RuntimeException e) {
        Diagnostics.failure("model.failed", e, "stage", stage, "elapsed_ms", Diagnostics.elapsed(started));
        throw e;
      } finally { if (connection != null) connection.disconnect(); }
    }
  }

  static JsonNode consume(InputStream input, long deadline) throws IOException {
    final JsonNode[] output = {null};
    final String[] nodeId = {null};
    final int[] events = {0};
    final String[] stage = {"SSE_READ"}, lastEvent = {"NONE"};
    try {
      SseFrames.read(input, 8 * 1024 * 1024, deadline, (event, raw) -> {
        events[0]++; lastEvent[0] = event; stage[0] = "SSE_EVENT";
        // Workflow terminal events do not define a JSON data contract.
        if ("done".equals(event)) {
          Diagnostics.info("model.sse_done", "event_index", events[0], "result_seen", output[0] != null);
          if (output[0] == null) throw new Fault("MODEL_PROTOCOL_ERROR", "模型工作流未返回指定结果", 502);
          return true;
        }
        if ("failed".equals(event))
          throw new Fault("MODEL_FAILED", "模型工作流执行失败", 502);
        if ("interrupt".equals(event))
          throw new Fault("MODEL_INTERRUPTED", "单次模型工作流不能等待人工输入", 502);
        stage[0] = "SSE_EVENT_JSON";
        JsonNode p = Json.parse(raw);
        stage[0] = "SSE_ENVELOPE";
        if (!p.isObject() || (p.has("resCode") && !"FAIAG0000".equals(p.path("resCode").asText())))
          throw new Fault("MODEL_PROTOCOL_ERROR", "模型工作流返回失败报文", 502);
        JsonNode data = p.has("data") ? p.get("data") : p;
        if (!data.isObject()) throw new Fault("MODEL_PROTOCOL_ERROR", "模型事件格式无效", 502);
        if ("failed".equals(data.path("status").asText()))
          throw new Fault("MODEL_FAILED", "模型工作流执行失败", 502);
        if ("waiting_for_input".equals(data.path("status").asText()))
          throw new Fault("MODEL_INTERRUPTED", "单次模型工作流不能等待人工输入", 502);
        JsonNode meta = data.path("additional_kwargs");
        if (events[0] <= 50 && "message".equals(event))
          Diagnostics.info("model.sse_message", "event_index", events[0], "node_id", meta.path("node_id").asText(),
              "result_node", "模型结果".equals(meta.path("node_title").asText()),
              "result_shape", Diagnostics.shape(meta.path("node_output").get("output")));
        if ("message".equals(event) && "模型结果".equals(meta.path("node_title").asText())) {
          stage[0] = "RESULT_NODE_VALIDATE";
          String id = meta.path("node_id").asText();
          if (id.isEmpty() || (nodeId[0] != null && !nodeId[0].equals(id)))
            throw new Fault("MODEL_PROTOCOL_ERROR", "模型结果节点缺失或不唯一", 502);
          JsonNode value = meta.path("node_output").get("output");
          stage[0] = "RESULT_JSON";
          int wrappers = 0;
          if (value != null && value.isTextual()) value = Json.parse(value.asText());
          // Some published workflows wrap the selected end output once.
          if (value != null && value.isObject() && value.size() == 1 && value.has("output")) {
            wrappers++; stage[0] = "RESULT_UNWRAP_JSON";
            value = value.get("output");
            if (value != null && value.isTextual()) value = Json.parse(value.asText());
          }
          stage[0] = "RESULT_VALIDATE";
          if (value == null || value.isNull() || (!value.isObject() && !value.isArray())
              || (output[0] != null && !output[0].equals(value)))
            throw new Fault("MODEL_PROTOCOL_ERROR", "模型结束结果无效或冲突", 502);
          nodeId[0] = id; output[0] = value;
          Diagnostics.info("model.result_received", "node_id", id, "event_index", events[0],
              "wrapper_depth", wrappers, "result_shape", Diagnostics.shape(value));
        }
        stage[0] = "SSE_READ";
        return false;
      });
      return output[0];
    } catch (IOException | RuntimeException error) {
      Diagnostics.failure("model.sse_failed", error, "stage", stage[0], "event_index", events[0],
          "event_type", lastEvent[0], "result_seen", output[0] != null);
      throw error;
    }
  }

  private String documentEndpoint(String action) {
    URL base = checked(documentBaseUrl);
    if (base.getQuery() != null || base.getPath().endsWith("/chatabc")
        || base.getPath().contains("/chatabc/"))
      throw new Fault("MODEL_NOT_CONFIGURED", "文档模型请填写工作流基础地址，不含/chatabc接口后缀", 503);
    return documentBaseUrl + "/chatabc/" + action;
  }

  private String prepareImage(JsonNode image, long deadline) {
    if (!visionReady()) throw new Fault("VISION_NOT_CONFIGURED", "未配置文档模型工作流基础地址", 503);
    String name = image.path("name").asText();
    if (!name.matches("[A-Za-z0-9_.-]+\\.png")) throw new Fault("INVALID_ARGUMENT", "视觉文件名无效");
    byte[] bytes;
    try { bytes = Base64.getDecoder().decode(image.path("base64").asText()); }
    catch (IllegalArgumentException e) { throw new Fault("INVALID_ARGUMENT", "视觉文件编码无效"); }
    if (bytes.length < 8 || bytes.length > 4194304 || bytes[0] != (byte) 0x89
        || bytes[1] != 'P' || bytes[2] != 'N' || bytes[3] != 'G')
      throw new Fault("INVALID_ARGUMENT", "视觉文件格式或大小无效");
    String base = documentEndpoint("init_session");
    JsonNode init = fileData(postBytes(base, "application/json",
        Json.obj("data", Json.obj("config_variables",
            Json.arr(Json.obj("name", "image", "value", name)))).toString().getBytes(StandardCharsets.UTF_8), deadline));
    String sid = init.path("session_id").asText();
    if (sid.isEmpty() || sid.length() > 200 || sid.matches("(?s).*[\\r\\n\\x00].*"))
      throw new Fault("MODEL_PROTOCOL_ERROR", "文件初始化未返回有效会话", 502);
    String boundary = "model-" + Json.id();
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"session_id\"\r\n\r\n"
          + sid + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
          + name + "\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
      out.write(bytes);
      out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
      JsonNode uploaded = fileData(postBytes(documentEndpoint("upload_file"), "multipart/form-data; boundary=" + boundary,
          out.toByteArray(), deadline));
      String path = uploaded.path("file_path").asText();
      if (!path.startsWith("/") || path.length() < 2)
        throw new Fault("MODEL_PROTOCOL_ERROR", "图片上传未返回有效文件引用", 502);
      return sid;
    } catch (IOException e) { throw new Fault("MODEL_UNAVAILABLE", "图片上传未完成", 502, e); }
  }

  private JsonNode postBytes(String endpoint, String type, byte[] body, long deadline) {
    HttpURLConnection c = null;
    long started = System.nanoTime();
    try (Diagnostics.Scope ignored = Diagnostics.scope("stage", PlatformHttp.route(endpoint))) {
      Diagnostics.info("model.file_http_started", "bytes", body.length);
      try {
        c = (HttpURLConnection) checked(endpoint).openConnection();
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(Math.min(5000, remaining(deadline)));
        c.setReadTimeout(remaining(deadline)); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", type); c.setRequestProperty("Accept", "application/json");
        PlatformHttp.applyHeaders(c, headers);
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream out = c.getOutputStream()) { out.write(body); }
        Diagnostics.info("model.file_http_response", "http_status", c.getResponseCode(),
            "content_type", PlatformHttp.mediaType(c.getContentType()), "elapsed_ms", Diagnostics.elapsed(started));
        if (c.getResponseCode() != 200) throw new Fault("MODEL_PROTOCOL_ERROR", "模型文件接口请求失败", 502);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = c.getInputStream()) {
          byte[] buffer = new byte[8192]; int n;
          while ((n = in.read(buffer)) != -1) {
            remaining(deadline);
            if (out.size() + n > 1048576) throw new Fault("MODEL_PROTOCOL_ERROR", "文件接口回包超限", 502);
            out.write(buffer, 0, n);
          }
        }
        JsonNode result = Json.parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
        Diagnostics.info("model.file_http_completed", "elapsed_ms", Diagnostics.elapsed(started));
        return result;
      } catch (IOException e) {
        Diagnostics.failure("model.file_http_failed", e, "elapsed_ms", Diagnostics.elapsed(started));
        throw new Fault("MODEL_UNAVAILABLE", "模型文件接口连接未完成", 502, e);
      } catch (RuntimeException e) {
        Diagnostics.failure("model.file_http_failed", e, "elapsed_ms", Diagnostics.elapsed(started)); throw e;
      }
      finally { if (c != null) c.disconnect(); }
    }
  }

  private JsonNode fileData(JsonNode value) {
    if (!"FAIAG0000".equals(value.path("resCode").asText()) || !value.path("data").isObject())
      throw new Fault("MODEL_PROTOCOL_ERROR", "模型文件接口未返回成功回执", 502);
    return value.get("data");
  }

  private static URL checked(String address) {
    try {
      URL parsed = new URL(address);
      if (!Arrays.asList("http", "https").contains(parsed.getProtocol())
          || parsed.getHost().isEmpty() || parsed.getUserInfo() != null || parsed.getRef() != null)
        throw new MalformedURLException();
      return parsed;
    } catch (MalformedURLException e) { throw new Fault("MODEL_NOT_CONFIGURED", "独立模型工作流地址无效或未配置", 503); }
  }

  private static int remaining(long deadline) {
    long value = deadline - System.currentTimeMillis();
    if (value <= 0) throw new Fault("MODEL_TIMEOUT", "模型工作流超时", 504);
    return (int) Math.min(value, Integer.MAX_VALUE);
  }
}
