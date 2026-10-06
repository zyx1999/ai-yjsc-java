package com.yuerong.diligence.config;

import com.yuerong.diligence.ai.platform.WorkflowModelAdapter;
import com.yuerong.diligence.ai.platform.oneagent.*;
import com.yuerong.diligence.ai.port.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

/** Deployment binding is separate from reusable AI client implementations. */
@Configuration
public class AiConfiguration {
  @Bean
  public AgentPort agent(
      @Value("${diligence.platform.adapter:agent-service}") String adapter,
      @Value("${diligence.platform.url:}") String url,
      @Value("${diligence.platform.files-url:}") String filesUrl,
      @Value("${diligence.platform.headers-json:{}}") String headers,
      @Value("${diligence.platform.timeout-ms:600000}") int timeout,
      @Value("${diligence.platform.debug-trace:true}") boolean debugTrace) {
    switch (adapter) {
      case "agent-service":
        return new AgentServiceAdapter(url, headers, timeout, debugTrace);
      case "oneagent":
        return new BankOneAgentAdapter(url, filesUrl, headers, timeout, debugTrace);
      default:
        throw new IllegalStateException("Unknown platform adapter");
    }
  }
  @Bean
  public ModelInferencePort model(
      @Value("${diligence.model.url:}") String url,
      @Value("${diligence.model.document-base-url:}") String documentUrl,
      @Value("${diligence.model.headers-json:{}}") String headers,
      @Value("${diligence.model.timeout-ms:600000}") int timeout) {
    return new WorkflowModelAdapter(url, documentUrl, headers, timeout);
  }
}
