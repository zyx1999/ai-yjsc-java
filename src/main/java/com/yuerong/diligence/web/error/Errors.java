package com.yuerong.diligence.web.error;

import com.yuerong.diligence.common.Diagnostics;
import com.yuerong.diligence.common.Fault;
import com.yuerong.diligence.common.Json;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class Errors {
  @ExceptionHandler(Fault.class)
  public ResponseEntity<?> fault(Fault e) {
    String requestId = Diagnostics.current("http_request_id");
    if (requestId.isEmpty()) requestId = Json.id();
    Diagnostics.failure("api.failed", e, "request_id", requestId, "http_status", e.status);
    return ResponseEntity.status(e.status)
        .body(
            Json.obj(
                "contract_version",
                "DILIGENCE_V1_DRAFT",
                "request_id",
                requestId,
                "status",
                "FAILED",
                "data",
                null,
                "error",
                Json.obj("code", e.code, "message", e.getMessage(), "retryable", false)));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> unexpected(Exception e) {
    return fault(new Fault("INTERNAL_ERROR", "操作未完成，请查询状态或联系维护人员", 500, e));
  }
}
