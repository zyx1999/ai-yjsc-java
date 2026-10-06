package com.yuerong.diligence.model.diligence;

import com.yuerong.diligence.common.Json;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Executes the JSON Schema vocabulary used by the checked-in contract; no remote refs. */
@Component
public class Contracts {
  public final JsonNode schema;

  public Contracts() {
    try {
      schema =
          Json.M.readTree(
              getClass().getResourceAsStream("/contracts/business-contracts.schema.json"));
    } catch (Exception e) {
      throw new IllegalStateException("Business schema missing", e);
    }
  }

  public void check(String name, JsonNode value) {
    validate(schema.path("$defs").path(name), value, "arguments");
  }

  public JsonNode definition(String name) {
    return schema.path("$defs").path(name);
  }

  public ObjectNode response(JsonNode data) {
    return Json.obj(
        "contract_version",
        "DILIGENCE_V1_DRAFT",
        "request_id",
        Json.id(),
        "status",
        "SUCCEEDED",
        "data",
        data,
        "error",
        null);
  }

  public void validate(JsonNode definition, JsonNode value, String path) {
    new com.yuerong.diligence.common.validation.JsonSchemaValidator(schema).validate(definition, value, path);
  }
}
