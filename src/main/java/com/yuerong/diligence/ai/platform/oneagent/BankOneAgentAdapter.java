package com.yuerong.diligence.ai.platform.oneagent;

import com.yuerong.diligence.common.Fault;
import com.yuerong.diligence.common.Json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import com.yuerong.diligence.ai.model.AgentRequest;

/**
 * 行内原生自主编排 Agent（provider=oneagent，协议见 docs/context/10_行内自主编排模式api指南.wps）。
 * 附件先上传到平台文件接口（同一 sessionId），再在用户消息中引用会话相对路径。
 */
public class BankOneAgentAdapter extends OneAgentAdapter {
  private final OneAgentFiles files;

  public BankOneAgentAdapter(String url, String filesUrl, String headers, int timeout) {
    this(url, filesUrl, headers, timeout, true);
  }

  public BankOneAgentAdapter(
      String url, String filesUrl, String headers, int timeout, boolean debugTrace) {
    super(url, headers, timeout, debugTrace);
    this.files =
        new OneAgentFiles(
            filesUrl == null || filesUrl.isEmpty() ? OneAgentFiles.filesBase(url) : filesUrl,
            headers,
            timeout,
            debugTrace);
  }

  @Override
  public String provider() {
    return "oneagent";
  }

  @Override
  public ObjectNode chat(AgentRequest request) {
    String session = request.session, user = request.user, text = request.text;
    JsonNode attachment = request.attachment;
    if (attachment == null || attachment.isNull())
      return exchange(session, user, text, request.variables);
    JsonNode content = attachment.path("content");
    if (!content.isBinary()) throw new Fault("INVALID_ARGUMENT", "附件内容不可用");
    byte[] bytes;
    try {
      bytes = content.binaryValue();
    } catch (IOException e) {
      throw new Fault("INVALID_ARGUMENT", "附件内容不可用");
    }
    String sid = session == null || session.isEmpty() ? Json.id() : session;
    String name = attachment.path("name").asText("材料.pdf");
    String path = files.upload(sid, name, bytes);
    return exchange(sid, user, text + attachmentReference(name, path, attachment.path("instruction").asText()), request.variables);
  }

  /** The caller supplies business instructions; the adapter only binds the uploaded file path. */
  static String attachmentReference(String name, String path, String instruction) {
    return "\n\n[附件已上传] 文件名：" + name + "，会话相对路径：" + path + "。"
        + (instruction.isEmpty() ? "请先读取该文件，再继续处理。" : instruction);
  }
}
