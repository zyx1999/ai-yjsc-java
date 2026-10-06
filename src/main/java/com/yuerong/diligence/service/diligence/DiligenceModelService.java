package com.yuerong.diligence.service.diligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuerong.diligence.ai.model.ModelRequest;
import com.yuerong.diligence.ai.port.ModelInferencePort;
import com.yuerong.diligence.common.Fault;
import com.yuerong.diligence.common.Diagnostics;
import java.util.*;
import org.springframework.stereotype.Service;

/** Diligence-specific routing and evidence policy stay outside the model transport. */
@Service
public class DiligenceModelService {
  private final ModelInferencePort model;
  public DiligenceModelService(ModelInferencePort model) { this.model = model; }
  public boolean visionReady() { return model.visionReady(); }
  public JsonNode infer(String taskCode, String instruction, JsonNode input, JsonNode schema, JsonNode image) {
    ModelRequest.Workflow workflow = taskCode.startsWith("investigation.")
        ? ModelRequest.Workflow.TEXT : ModelRequest.Workflow.DOCUMENT;
    JsonNode result = model.infer(new ModelRequest(workflow, taskCode, instruction, input, schema, image));
    Set<String> evidence = new HashSet<>();
    for (JsonNode fragment : input.path("fragments")) evidence.add(fragment.path("evidence_id").asText());
    try { checkEvidence(result, evidence); }
    catch (Fault error) {
      Diagnostics.failure("model.evidence_failed", error, "model_task", taskCode, "stage", "EVIDENCE_VALIDATE");
      throw error;
    }
    return result;
  }

  private static void checkEvidence(JsonNode value, Set<String> allowed) {
    if (value.isObject()) {
      value.fields().forEachRemaining(e -> {
        if ("evidence_refs".equals(e.getKey())) {
          if (!e.getValue().isArray()) throw new Fault("EVIDENCE_INVALID", "模型证据格式无效");
          for (JsonNode ref : e.getValue())
            if (!ref.isTextual() || !allowed.contains(ref.asText()))
              throw new Fault("EVIDENCE_INVALID", "模型引用本次输入以外的证据");
        } else checkEvidence(e.getValue(), allowed);
      });
    } else if (value.isArray()) for (JsonNode item : value) checkEvidence(item, allowed);
  }

}
