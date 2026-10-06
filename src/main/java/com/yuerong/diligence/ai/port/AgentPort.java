package com.yuerong.diligence.ai.port;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuerong.diligence.ai.model.AgentRequest;

public interface AgentPort {
  String provider();
  default String initialSession() { return ""; }
  ObjectNode chat(AgentRequest request);
}
