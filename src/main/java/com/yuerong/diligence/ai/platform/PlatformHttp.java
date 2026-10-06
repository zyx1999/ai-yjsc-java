package com.yuerong.diligence.ai.platform;

import com.yuerong.diligence.common.Diagnostics;
import com.yuerong.diligence.common.Fault;
import com.yuerong.diligence.common.Json;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class PlatformHttp {
  private final String url, headerJson;
  private final int timeout;

  public PlatformHttp(String url, String headers, int timeout) {
    this.url = url;
    this.headerJson = headers;
    this.timeout = timeout;
  }

  /** 校验并写入平台认证头；协议头由调用方控制，不接受配置覆盖。 */
  public static void applyHeaders(HttpURLConnection c, String headerJson) {
    JsonNode headers = Json.parse(headerJson);
    headers
        .fields()
        .forEachRemaining(
            e -> {
              String k = e.getKey(), v = e.getValue().asText();
              if (k.matches("(?i)host|content-length|transfer-encoding|connection")
                  || k.contains("\n")
                  || v.contains("\n")
                  || v.contains("\r")) throw new Fault("CONFIG_INVALID", "认证Header无效");
              c.setRequestProperty(k, v);
            });
  }

  public HttpURLConnection post(JsonNode body) {
    long started = System.nanoTime();
    HttpURLConnection c = null;
    try (Diagnostics.Scope ignored = Diagnostics.scope("route", route(url), "timeout_ms", timeout)) {
      Diagnostics.info("platform.http_started");
      try {
        if (url.isEmpty()) throw new Fault("PLATFORM_NOT_CONFIGURED", "尚未配置平台发布地址", 503);
        URL target = new URL(url);
        if (!java.util.Arrays.asList("http", "https").contains(target.getProtocol())
            || target.getUserInfo() != null) throw new Fault("CONFIG_INVALID", "平台地址无效", 503);
        c = (HttpURLConnection) target.openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(5000);
        c.setReadTimeout(timeout);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "text/event-stream");
        applyHeaders(c, headerJson);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = c.getOutputStream()) {
          out.write(bytes);
        }
        Diagnostics.info("platform.http_response", "http_status", c.getResponseCode(),
            "content_type", mediaType(c.getContentType()), "elapsed_ms", Diagnostics.elapsed(started));
        if (c.getResponseCode() != 200
            || c.getContentType() == null
            || !c.getContentType().startsWith("text/event-stream")) {
          c.disconnect();
          throw new Fault("PLATFORM_PROTOCOL_ERROR", "平台未返回成功的SSE响应", 502);
        }
        return c;
      } catch (IOException e) {
        if (c != null) c.disconnect();
        Diagnostics.failure("platform.http_failed", e, "elapsed_ms", Diagnostics.elapsed(started));
        throw new Fault("RESULT_UNKNOWN", "平台连接未完成，请核实运行状态", 502, e);
      } catch (RuntimeException e) {
        if (c != null) c.disconnect();
        Diagnostics.failure("platform.http_failed", e, "elapsed_ms", Diagnostics.elapsed(started));
        throw e;
      }
    }
  }

  static String route(String address) {
    try {
      String path = new URL(address).getPath();
      String last = path.substring(path.lastIndexOf('/') + 1);
      return java.util.Arrays.asList("use_as_tool", "chat", "message", "init_session", "upload_file").contains(last)
          ? last : "platform";
    } catch (MalformedURLException e) { return "unconfigured"; }
  }

  static String mediaType(String value) {
    return value == null ? "MISSING" : value.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
  }
}
