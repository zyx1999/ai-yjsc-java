package com.yuerong.diligence.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

/** Trusted caller context and optional attachment, independent of any business scenario. */
public final class AgentRequest {
  public final String session, user, text;
  public final ArrayNode variables;
  public final JsonNode attachment;

  public AgentRequest(String session, String user, String text, ArrayNode variables, JsonNode attachment) {
    this.session = session;
    this.user = user;
    this.text = text;
    this.variables = variables.deepCopy();
    this.attachment = attachment == null ? null : attachment.deepCopy();
  }
}
