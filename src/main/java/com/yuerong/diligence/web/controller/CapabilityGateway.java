package com.yuerong.diligence.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuerong.diligence.service.diligence.DiligenceGatewayService;
import org.springframework.web.bind.annotation.*;

@RestController
public class CapabilityGateway {
  private final DiligenceGatewayService service;
  public CapabilityGateway(DiligenceGatewayService service) { this.service = service; }

  @PostMapping("/internal/diligence/gateway")
  public ObjectNode call(@RequestBody JsonNode request) { return service.call(request); }
}
