package com.yuerong.diligence.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuerong.diligence.service.diligence.AnalysisService;
import com.yuerong.diligence.web.security.Access;
import com.yuerong.diligence.web.sse.SseStreams;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 征信 / 流水分析窗口（文答场景）HTTP 入口；会话与对话编排在 AnalysisService。 */
@RestController
public class AnalysisController {
  private final AnalysisService analysis;
  private final Access access;
  private final SseStreams streams;

  public AnalysisController(AnalysisService analysis, Access access, SseStreams streams) {
    this.analysis = analysis;
    this.access = access;
    this.streams = streams;
  }

  @PostMapping("/api/v1/analysis/sessions")
  public JsonNode create(@RequestBody(required = false) JsonNode body) {
    return analysis.create(access.user(), body);
  }

  /** 历史会话列表（kind=credit/bankflow 区分窗口；同用户隔离）。 */
  @GetMapping("/api/v1/analysis/sessions")
  public JsonNode sessions(
      @RequestParam(value = "kind", required = false, defaultValue = "") String kind) {
    return analysis.list(access.user(), kind);
  }

  @GetMapping("/api/v1/analysis/sessions/{task}")
  public JsonNode session(@PathVariable String task) {
    return analysis.session(access.user(), task);
  }

  @DeleteMapping("/api/v1/analysis/sessions/{task}")
  public JsonNode delete(@PathVariable String task) {
    return analysis.delete(access.user(), task);
  }

  @GetMapping("/api/v1/analysis/sessions/{task}/files")
  public JsonNode files(@PathVariable String task) {
    return analysis.files(access.user(), task);
  }

  @PostMapping("/api/v1/analysis/sessions/{task}/files")
  public JsonNode upload(@PathVariable String task, @RequestParam("file") MultipartFile file)
      throws Exception {
    return analysis.upload(access.user(), task, file.getOriginalFilename(), file.getBytes());
  }

  @PostMapping(value = "/api/v1/analysis/sessions/{task}/chat/events", produces = "text/event-stream")
  public SseEmitter events(@PathVariable String task, @RequestBody JsonNode body) {
    return streams.open(analysis.prepare(task, access.user(), body));
  }
}
