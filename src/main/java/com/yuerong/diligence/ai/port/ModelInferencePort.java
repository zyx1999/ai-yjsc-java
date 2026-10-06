package com.yuerong.diligence.ai.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuerong.diligence.ai.model.ModelRequest;

/** One bounded inference, with transport completion and caller-supplied schema validation. */
public interface ModelInferencePort {
  JsonNode infer(ModelRequest request);
  boolean visionReady();
}
