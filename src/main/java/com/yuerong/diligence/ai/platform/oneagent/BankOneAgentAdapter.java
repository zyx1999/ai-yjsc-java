package com.yuerong.diligence.ai.platform.oneagent;

import com.yuerong.diligence.common.Fault;
import com.yuerong.diligence.common.Json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import com.yuerong.diligence.ai.model.AgentRequest;

/**
 * 行内原生自主编排 Agent（provider=oneagent，协议见 docs/context/10_行内自主编排模式api指南.wps）。
 * 附件先上传到平台文件接口（同一 sessionId），再在用户消息中引用会话相对路径；
 * 单附件为对象、多附件为数组（文答窗口一次可提交多个材料）。
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
  public boolean fileCapable() {
    return files.configured();
  }

  @Override
  public String uploadFile(String session, String name, byte[] content) {
    return files.upload(session, name, content);
  }

  @Override
  public ObjectNode chat(AgentRequest request) {
    String session = request.session, user = request.user, text = request.text;
    JsonNode attachment = request.attachment;
    if (attachment == null || attachment.isNull())
      return exchange(session, user, text, request.variables);
    ArrayNode items = attachment.isArray() ? (ArrayNode) attachment : Json.arr(attachment);
    String sid = session == null || session.isEmpty() ? Json.id() : session;
    StringBuilder message = new StringBuilder(text == null ? "" : text);
    for (JsonNode item : items) {
      String name = item.path("name").asText("材料.pdf");
      String path = item.path("platform_path").asText("");
      if (path.isEmpty()) {
        JsonNode content = item.path("content");
        if (!content.isBinary()) throw new Fault("INVALID_ARGUMENT", "附件内容不可用");
        byte[] bytes;
        try {
          bytes = content.binaryValue();
        } catch (IOException e) {
          throw new Fault("INVALID_ARGUMENT", "附件内容不可用");
        }
        path = files.upload(sid, name, bytes);
      }
      message.append(
          attachmentReference(name, path, item.path("instruction").asText(""), item.path("reference_style").asText("")));
    }
    return exchange(sid, user, message.toString(), request.variables);
  }

  @Override
  public JsonNode files(String session) {
    return files.list(session).path("files");
  }

  /** 默认引用格式（尽调）：业务后端登记语义，配套 instruction 使用。 */
  static String attachmentReference(String name, String path, String instruction) {
    return attachmentReference(name, path, instruction, "");
  }

  /**
   * 附件引用文本。reference_style=user_upload 时使用平台实测可用的用户附件格式
   * （【用户上传附件】…工作区相对路径…，供文答窗口的文件分析场景）；否则保持尽调默认格式。
   */
  static String attachmentReference(String name, String path, String instruction, String style) {
    if ("user_upload".equals(style)) {
      String head = "【用户上传附件】文件名：" + name + "，工作区相对路径：" + path + "。请在需要时读取该文件。";
      return "\n\n" + head + (instruction.isEmpty() ? "" : instruction);
    }
    return "\n\n[附件已上传] 文件名：" + name + "，会话相对路径：" + path + "。"
        + (instruction.isEmpty() ? "请先读取该文件，再继续处理。" : instruction);
  }
}
