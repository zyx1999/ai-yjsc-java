package com.yuerong.diligence.common;

/**
 * SSE 调试日志文本工具：秘密打码、折叠换行、超长截断（供 [SSE-TRACE] 入站帧与出站事件共用）。
 *
 * <p>仅供 <b>显式开启 debugTrace</b> 的联调日志使用；业务诊断日志仍走 {@link Diagnostics}，
 * 只记录有界元数据，不写入正文。
 */
public final class TraceText {

  /** 单帧日志上限（超出截断，避免日志被长报文淹没）。 */
  public static final int DEFAULT_LIMIT = 4000;

  private TraceText() {}

  /** 将明文中长度 >= 8 的秘密值替换为 ***（短值不足以构成敏感信息，保持可读）。 */
  public static String mask(String raw, String... secrets) {
    String masked = raw == null ? "" : raw;
    if (secrets != null) {
      for (String secret : secrets) {
        if (secret != null && secret.length() >= 8) masked = masked.replace(secret, "***");
      }
    }
    return masked;
  }

  /** 折叠空白为单空格并去首尾（便于单行日志阅读）；空文本返回 null。 */
  public static String singleLine(String text) {
    if (text == null) return null;
    String folded = text.replaceAll("\\s+", " ").trim();
    return folded.isEmpty() ? null : folded;
  }

  /** 超长截断（带省略标记）。 */
  public static String truncate(String value, int limit) {
    if (value == null) return "";
    return value.length() > limit ? value.substring(0, limit) + "…（截断）" : value;
  }
}
