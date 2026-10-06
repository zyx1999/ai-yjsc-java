package com.yuerong.diligence.service.diligence;

import com.yuerong.diligence.common.Fault;

public final class AnswerSafety {
  private AnswerSafety() {}

  public static String visible(String raw) {
    String answer =
        raw.replaceAll("(?is)<think\\b[^>]*>.*?</think\\s*>", "")
            .trim();
    if (answer.matches("(?is).*<\\s*/?think\\b.*") || answer.isEmpty())
      throw new Fault("PLATFORM_PROTOCOL_ERROR", "平台未返回可显示的用户回答", 502);
    return answer;
  }
}
