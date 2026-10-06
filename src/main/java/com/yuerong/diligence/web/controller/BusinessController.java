package com.yuerong.diligence.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuerong.diligence.model.FileDownload;
import com.yuerong.diligence.service.diligence.DiligenceFacade;
import com.yuerong.diligence.web.security.Access;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** HTTP binding and response rendering only; business policy lives in application services. */
@RestController
public class BusinessController {
  private final DiligenceFacade service;
  private final Access access;
  public BusinessController(DiligenceFacade service, Access access) {
    this.service = service; this.access = access;
  }

  @PostMapping("/api/v1/diligence/sessions")
  public JsonNode create() { return service.create(access.user()); }

  @GetMapping("/api/v1/diligence/sessions")
  public JsonNode list() { return service.list(access.user()); }

  @GetMapping("/api/v1/diligence/sessions/{task}")
  public JsonNode session(@PathVariable String task) { return service.session(access.user(), task); }

  @DeleteMapping("/api/v1/diligence/sessions/{task}")
  public JsonNode delete(@PathVariable String task) { return service.delete(access.user(), task); }

  @GetMapping("/api/v1/diligence/sessions/{task}/proposals/{id}/files")
  public JsonNode proposalFiles(@PathVariable String task, @PathVariable String id) {
    return service.proposalFiles(access.user(), task, id);
  }

  @PostMapping("/api/v1/diligence/{family}/{action}")
  public JsonNode call(@PathVariable String family, @PathVariable String action, @RequestBody JsonNode body)
      throws Exception { return service.call(access.user(), family, action, body); }

  @PostMapping("/api/v1/diligence/financial/proposals/read")
  public JsonNode proposal(@RequestBody JsonNode body) { return service.proposal(access.user(), body); }

  @PostMapping("/api/v1/diligence/sessions/{task}/files")
  public JsonNode upload(@PathVariable String task, @RequestParam String credit_code,
      @RequestParam String role, @RequestParam("file") MultipartFile file) throws Exception {
    return service.upload(access.user(), task, credit_code, role, file.getOriginalFilename(), file.getBytes());
  }

  @GetMapping("/api/v1/diligence/sessions/{task}/files/{id}")
  public ResponseEntity<byte[]> download(@PathVariable String task, @PathVariable String id) throws Exception {
    FileDownload file = service.download(access.user(), task, id);
    return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=" + file.name)
        .contentType(MediaType.parseMediaType(file.contentType)).body(file.content);
  }

  @GetMapping("/api/v1/diligence/catalog")
  public JsonNode catalog() throws Exception { return service.catalog(); }
}
