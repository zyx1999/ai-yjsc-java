package com.yuerong.diligence.ai.platform.oneagent;

/**
 * 独立的 Python AI 服务 speaks the bank autonomous-Agent SSE contract。
 *
 * <p>当它与行内 oneagent 平台处于同一命名空间时（files-url 留空时由 message 地址自动推导），
 * 同样具备平台文件上传/列举能力，供文答窗口与材料类对话使用。
 */
public class AgentServiceAdapter extends BankOneAgentAdapter {
  public AgentServiceAdapter(String url, String filesUrl, String headers, int timeout) {
    this(url, filesUrl, headers, timeout, true);
  }

  public AgentServiceAdapter(
      String url, String filesUrl, String headers, int timeout, boolean debugTrace) {
    super(url, filesUrl, headers, timeout, debugTrace);
  }

  @Override
  public String provider() {
    return "agent-service";
  }
}
