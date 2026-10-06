package com.yuerong.diligence.ai.platform;

import com.yuerong.diligence.common.Fault;

import java.io.*;
import java.nio.charset.StandardCharsets;

public final class SseFrames {
  public interface Handler {
    boolean frame(String event, String data);
  }

  public static void read(InputStream input, int maxBytes, long deadline, Handler handler)
      throws IOException {
    InputStreamReader r =
        new InputStreamReader(
            input,
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT));
    StringBuilder line = new StringBuilder(), data = new StringBuilder();
    String event = "message";
    int used = 0, ch;
    while ((ch = r.read()) != -1) {
      if (++used > maxBytes || System.currentTimeMillis() > deadline)
        throw new Fault("PLATFORM_PROTOCOL_ERROR", "事件流超限或超时", 502);
      if (ch != '\n') {
        line.append((char) ch);
        continue;
      }
      String s = line.toString();
      line.setLength(0);
      if (s.endsWith("\r")) s = s.substring(0, s.length() - 1);
      if (s.isEmpty()) {
        boolean control = "done".equals(event) || "failed".equals(event) || "interrupt".equals(event);
        if ((data.length() > 0 || control)
            && handler.frame(event, data.length() == 0 ? "" : data.substring(0, data.length() - 1))) return;
        event = "message";
        data.setLength(0);
      } else if (s.startsWith("event:")) event = s.substring(6).trim();
      else if (s.startsWith("data:")) {
        String v = s.substring(5);
        data.append(v.startsWith(" ") ? v.substring(1) : v).append('\n');
      }
    }
    // EOF with an unfinished frame is not a terminal event.
    throw new Fault("RESULT_UNKNOWN", "连接中断或缺少终止事件", 502);
  }
}
