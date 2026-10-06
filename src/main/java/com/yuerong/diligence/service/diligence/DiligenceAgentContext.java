package com.yuerong.diligence.service.diligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.yuerong.diligence.ai.model.AgentRequest;
import com.yuerong.diligence.common.Json;

/** Builds trusted Skill context and registered-document instructions for this scenario. */
public final class DiligenceAgentContext {
  public static ArrayNode variables(String task, JsonNode attachment) {
    ArrayNode variables = Json.arr(Json.obj("name", "runtime_task_id", "value", task));
    if (attachment != null && attachment.hasNonNull("file_id")) {
      variables.add(Json.obj("name", "input_file_id", "value", attachment.path("file_id").asText()));
      variables.add(Json.obj("name", "input_file_role", "value", attachment.path("role").asText()));
    }
    return variables;
  }

  public static AgentRequest request(String session, String user, String text, String task, JsonNode attachment) {
    JsonNode file = attachment == null ? null : attachment.deepCopy();
    if (file != null && file.isObject()) {
      String role = file.path("role").asText();
      if ("FINANCIAL".equals(role) || "CREDIT".equals(role)) {
        String skill = "FINANCIAL".equals(role) ? "financial-report" : "enterprise-credit";
        ((ObjectNode) file).put("instruction", "附件已由业务后端登记。请调用 " + skill
            + " Skill，先执行 context 核对企业及 current_attachment，使用其返回的已登记 file_id 按该 Skill 说明处理。"
            + "原件读取、主体核验和提取由业务后端完成；不要先在沙箱读取或转换此 PDF，不要重复调用 upload，"
            + "也不要把相对路径作为 file_id。若 context 未返回对应用途的 current_attachment，请报告附件上下文缺失，不猜测文件ID或路径。");
      }
    }
    return new AgentRequest(session, user, text, variables(task, attachment), file);
  }

  private DiligenceAgentContext() {}
}
