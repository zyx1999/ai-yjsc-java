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

  @Test
  void platformPathAttachmentSkipsContentAndProceedsToExchange() {
    // 已有平台工作区路径时不再要求本地字节，直接进入平台交互（此处因未配置地址而失败）。
    Fault fault =
        assertThrows(
            Fault.class,
            () ->
                adapter()
                    .chat(
                        DiligenceAgentContext.request(
                            "s", "u", "你好", "task-1",
                            Json.obj("file_id", "f1", "name", "征信.pdf", "platform_path", "征信.pdf"))));
    assertEquals("PLATFORM_NOT_CONFIGURED", fault.code);
  }

  @Test
  void fileCapabilityFollowsFilesAddress() {
    assertTrue(new BankOneAgentAdapter("https://host/api/v1/message", "", "{}", 1000).fileCapable());
    assertFalse(adapter().fileCapable());
    Fault fault =
        assertThrows(Fault.class, () -> adapter().uploadFile("s", "a.pdf", "x".getBytes()));
    assertEquals("PLATFORM_NOT_CONFIGURED", fault.code);
  }

  @Test
  void attachmentReferenceKeepsBothFormats() {
    assertEquals(
        "\n\n[附件已上传] 文件名：a.pdf，会话相对路径：a.pdf。请先读取该文件，再继续处理。",
        BankOneAgentAdapter.attachmentReference("a.pdf", "a.pdf", ""));
    assertEquals(
        "\n\n[附件已上传] 文件名：a.pdf，会话相对路径：a.pdf。请调用 skill。",
        BankOneAgentAdapter.attachmentReference("a.pdf", "a.pdf", "请调用 skill。"));
    assertEquals(
        "\n\n【用户上传附件】文件名：a.pdf，工作区相对路径：a.pdf。请在需要时读取该文件。",
        BankOneAgentAdapter.attachmentReference("a.pdf", "a.pdf", "", "user_upload"));
  }
}
