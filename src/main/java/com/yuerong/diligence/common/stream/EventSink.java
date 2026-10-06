package com.yuerong.diligence.common.stream;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

/** Transport-independent application event output. */
@FunctionalInterface
public interface EventSink {
  void send(String id, String type, JsonNode payload) throws IOException;
}
