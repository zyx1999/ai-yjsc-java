package com.yuerong.diligence.ai.platform.oneagent;

/** The independent Python AI service speaks the bank autonomous-Agent SSE contract. */
public class AgentServiceAdapter extends OneAgentAdapter {
  public AgentServiceAdapter(String url, String headers, int timeout) {
    this(url, headers, timeout, true);
  }

  public AgentServiceAdapter(String url, String headers, int timeout, boolean debugTrace) {
    super(url, headers, timeout, debugTrace);
  }

  @Override
  public String provider() {
    return "agent-service";
  }
}
