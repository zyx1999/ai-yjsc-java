package com.yuerong.diligence.config;

import com.yuerong.diligence.common.Json;
import com.yuerong.diligence.service.diligence.DiligenceService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Opt-in local demonstration: ordinary sessions still start empty. */
@Component
public class DemoBootstrap implements ApplicationRunner {
  private final DiligenceService biz;
  private final boolean enabled;
  private final String user, adapter;

  public DemoBootstrap(
      DiligenceService biz,
      @Value("${diligence.demo.seed-session:false}") boolean enabled,
      @Value("${diligence.security.dev-user:}") String user,
      @Value("${diligence.source.adapter:}") String adapter) {
    this.biz = biz;
    this.enabled = enabled;
    this.user = user;
    this.adapter = adapter;
  }

  public void run(ApplicationArguments args) {
    if (!enabled || !"demo".equals(adapter) || user.isEmpty()) return;
    String marker = user + "-single-company-v2";
    if (biz.store.get("demo-bootstrap", marker) != null) return;
    String task = biz.create(user).path("task_id").asText();
    biz.store.update(
        "session",
        task,
        s -> {
          s.put("title", "制造企业尽调 · 模拟演示");
          return s;
        });
    String code = "91310000MA00000001";
    JsonNode subject = biz.subject(code);
    biz.bindSubject(task, subject);
    JsonNode last = null;
    for (JsonNode d : biz.contracts.schema.path("x-dimensions"))
      last = biz.query(task, Json.obj("credit_code", code, "dimension", d.get("code")));
    ObjectNode operation = biz.newOperation(task, "investigation");
    operation.put("status", "SUCCEEDED");
    operation.put("stage", "INVESTIGATION_SAVED");
    operation.set("result_id", last.get("result_id"));
    operation.set("result_version", last.get("version"));
    biz.finish(task, operation);
    biz.store.create("demo-bootstrap", marker, user, Json.obj("task_id", task));
  }
}
