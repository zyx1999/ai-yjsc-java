package com.yuerong.diligence.ai.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuerong.diligence.ai.model.AgentRequest;
import com.yuerong.diligence.common.Fault;

public interface AgentPort {
  String provider();
  default String initialSession() { return ""; }
  ObjectNode chat(AgentRequest request);

  /**
   * 平台会话工作区文件列表（元素为平台原始条目：path/name/type/size）。
   * 适配器不支持平台文件能力时返回 {@code null}，支持但目录为空时返回空数组。
   */
  default JsonNode files(String session) { return null; }

  /** 是否支持把附件上传到平台会话工作区。 */
  default boolean fileCapable() { return false; }

  /** 上传附件到平台会话工作区，返回会话内相对路径；不支持或失败时抛 Fault。 */
  default String uploadFile(String session, String name, byte[] content) {
    throw new Fault("PLATFORM_FILE_UNSUPPORTED", "当前平台适配器不支持文件上传", 503);
  }
}
