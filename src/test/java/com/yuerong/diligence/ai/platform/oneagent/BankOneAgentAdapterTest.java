package com.yuerong.diligence.ai.platform.oneagent;

import com.yuerong.diligence.common.Fault;
import com.yuerong.diligence.common.Json;
import com.yuerong.diligence.service.diligence.DiligenceAgentContext;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

class BankOneAgentAdapterTest {
  private BankOneAgentAdapter adapter() {
    return new BankOneAgentAdapter("", "", "{}", 1000);
  }

  @Test
  void localVariablesCarryRuntimeContext() {
    // 两种入口共用受控运行变量。
    ArrayNode variables = DiligenceAgentContext.variables("task-1", null);
    assertEquals("task-1", value(variables, "runtime_task_id"));
    assertNull(value(variables, "runtime_run_token"));
    assertEquals(1, variables.size());
  }

  @Test
  void runtimeVariablesKeepAttachmentNames() {
    ArrayNode variables =
        DiligenceAgentContext.variables(
            "task-1", Json.obj("file_id", "f1", "role", "FINANCIAL"));
    assertEquals("f1", value(variables, "input_file_id"));
    assertEquals("FINANCIAL", value(variables, "input_file_role"));
  }

  private static String value(JsonNode variables, String name) {
    for (JsonNode variable : variables)
      if (name.equals(variable.path("name").asText())) return variable.path("value").asText();
    return null;
  }

  @Test
  void chatWithoutAttachmentDoesNotRecurse() {
    // 回归：曾因 4 参/5 参重载相互虚分派导致 StackOverflowError（内网实测）。
    Fault fault =
        assertThrows(
            Fault.class, () -> adapter().chat(DiligenceAgentContext.request("s", "u", "你好", "task-1", null)));
    assertEquals("PLATFORM_NOT_CONFIGURED", fault.code);
  }

  @Test
  void chatWithFileIdButNoContentFailsFast() {
    Fault fault =
        assertThrows(
            Fault.class,
            () ->
                adapter()
                    .chat(DiligenceAgentContext.request("s", "u", "你好", "task-1", Json.obj("file_id", "f1", "role", "FINANCIAL"))));
    assertEquals("INVALID_ARGUMENT", fault.code);
  }
}
