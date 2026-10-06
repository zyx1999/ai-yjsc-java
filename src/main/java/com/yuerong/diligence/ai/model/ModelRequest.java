package com.yuerong.diligence.ai.model;

import com.fasterxml.jackson.databind.JsonNode;

/** The caller chooses an endpoint capability; taskCode is opaque diagnostic metadata. */
public final class ModelRequest {
  public enum Workflow { TEXT, DOCUMENT }
  public final Workflow workflow;
  public final String taskCode, instruction;
  public final JsonNode input, schema, image;

  public ModelRequest(Workflow workflow, String taskCode, String instruction,
      JsonNode input, JsonNode schema, JsonNode image) {
    this.workflow = java.util.Objects.requireNonNull(workflow);
    this.taskCode = taskCode;
    this.instruction = instruction;
    this.input = input;
    this.schema = schema;
    this.image = image;
  }
}
