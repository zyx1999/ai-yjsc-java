package com.yuerong.diligence.web;

import com.yuerong.diligence.common.Diagnostics;
import com.yuerong.diligence.common.Json;

import java.io.IOException;
import javax.servlet.*;
import javax.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Logs arrival before MVC binding, including malformed JSON and failed multipart requests. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class DiagnosticsFilter extends OncePerRequestFilter {
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (!path.startsWith("/api/v1/diligence/") && !path.equals("/internal/diligence/gateway")) {
      chain.doFilter(request, response); return;
    }
    String id = Json.id();
    String route = path.equals("/internal/diligence/gateway") ? "business_gateway"
        : path.endsWith("/chat/events") ? "chat" : path.endsWith("/files") ? "file_upload" : "business_api";
    long started = System.nanoTime();
    response.setHeader("X-Request-ID", id);
    try (Diagnostics.Scope ignored = Diagnostics.scope("http_request_id", id, "route", route, "method", request.getMethod())) {
      Diagnostics.info("http.received");
      try { chain.doFilter(request, response); }
      catch (IOException | ServletException | RuntimeException error) {
        Diagnostics.failure("http.failed", error, "elapsed_ms", Diagnostics.elapsed(started)); throw error;
      } finally {
        Diagnostics.info(request.isAsyncStarted() ? "http.async_started" : "http.completed",
            "http_status", response.getStatus(), "elapsed_ms", Diagnostics.elapsed(started));
      }
    }
  }
}
