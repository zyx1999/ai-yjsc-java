package com.yuerong.diligence.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;

public interface EnterpriseDataPort {
  ObjectNode resolve(JsonNode identifier);

  ObjectNode query(String creditCode, String dimension);

  boolean writable(String dimension, String field);

  String baseline(String creditCode);

  void apply(String creditCode, String baseline, String operationId, ArrayNode changes);
}
