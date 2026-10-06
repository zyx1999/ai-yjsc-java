package com.yuerong.diligence.service.diligence;

import com.yuerong.diligence.common.Diagnostics;
import com.yuerong.diligence.common.Fault;
import com.yuerong.diligence.common.Json;
import com.yuerong.diligence.web.DiagnosticsFilter;
import com.yuerong.diligence.web.error.Errors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

class DiagnosticsTest {
  Logger logger;
  Level previous;
  ListAppender<ILoggingEvent> logs;
  @TempDir Path dir;

  @BeforeEach void capture() {
    logger = (Logger) LoggerFactory.getLogger("com.yuerong.diligence.diagnostics");
    previous = logger.getLevel(); logger.setLevel(Level.INFO);
    logs = new ListAppender<>(); logs.setContext(logger.getLoggerContext()); logs.start(); logger.addAppender(logs);
  }
  @AfterEach void cleanup() { logger.detachAppender(logs); logs.stop(); logger.setLevel(previous); }
  String output() { StringBuilder out = new StringBuilder(); logs.list.forEach(e -> out.append(e.getFormattedMessage()).append('\n')); return out.toString(); }

  @Test void scopeRestoresContextAndFailuresDoNotLeakPayloadOrCauseMessages() throws Exception {
    String secret = "PRIVATE_PDF_TEXT_AND_TOKEN_ABC123";
    try (Diagnostics.Scope outer = Diagnostics.scope("request_id", "request-1")) {
      try (Diagnostics.Scope inner = Diagnostics.scope("file_id", "file-1")) {
        Diagnostics.failure("test.failure", new Fault("TEST_ERROR", secret, 500, new java.io.IOException(secret)),
            "body", secret, "url", "https://example.test/?token=" + secret, "status", "bad\n" + secret);
      }
      assertEquals("", Diagnostics.current("file_id"));
      assertEquals("request-1", Diagnostics.current("request_id"));
      ExecutorService pool = Executors.newSingleThreadExecutor();
      try { assertEquals("", pool.submit(() -> Diagnostics.current("request_id")).get()); }
      finally { pool.shutdownNow(); }
    }
    assertEquals("", Diagnostics.current("request_id"));
    assertTrue(output().contains("java.io.IOException"));
    assertTrue(output().contains("DiagnosticsTest.java:"));
    assertFalse(output().contains(secret));
    assertTrue(output().contains("request-1"));
  }

  @Test void missingArchivedFileIsDistinguishedFromMissingMetadata() throws Exception {
    DiligenceService biz = mock(DiligenceService.class);
    when(biz.owned("file", "disk-missing", "task-1")).thenReturn(Json.obj());
    when(biz.owned("file", "metadata-missing", "task-1")).thenThrow(new Fault("NOT_FOUND", "材料不存在", 404));
    FilesService files = new FilesService(biz, dir.toString());
    assertThrows(NoSuchFileException.class, () -> files.read("task-1", "disk-missing"));
    assertTrue(output().contains("file.reading"));
    assertTrue(output().contains("NoSuchFileException"));
    assertTrue(output().contains("disk-missing"));
    assertFalse(output().contains(dir.toString()));
    logs.list.clear();
    assertThrows(Fault.class, () -> files.read("task-1", "metadata-missing"));
    assertTrue(output().contains("NOT_FOUND"));
    assertFalse(output().contains("file.reading"));
  }

  @org.springframework.context.annotation.Profile("standalone-test-only")
  @RestController static class BrokenController {
    @PostMapping("/internal/diligence/gateway") Object fail(@RequestBody JsonNode body) {
      throw new IllegalStateException("PRIVATE_DATABASE_AND_CUSTOMER_TEXT");
    }
  }
  @Test void mvcErrorsAndInvalidJsonHaveVisibleCorrelatedLogs() throws Exception {
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new BrokenController()).setControllerAdvice(new Errors())
        .addFilters(new DiagnosticsFilter()).build();
    for (String body : new String[]{"{}", "{PRIVATE_INVALID_JSON"}) {
      logs.list.clear();
      MvcResult result = mvc.perform(post("/internal/diligence/gateway").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
      String id = result.getResponse().getHeader("X-Request-ID");
      assertNotNull(id);
      assertEquals(id, Json.parse(result.getResponse().getContentAsString()).path("request_id").asText());
      assertTrue(output().contains("http.received"));
      assertTrue(output().contains("api.failed"));
      assertTrue(output().contains(id));
      assertFalse(output().contains("PRIVATE_"));
      assertEquals("", Diagnostics.current("http_request_id"));
    }
    assertTrue(output().contains("JsonParseException"));
    assertTrue(output().contains("\"line\""));
  }
}
