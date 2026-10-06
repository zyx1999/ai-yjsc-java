package com.yuerong.diligence.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuerong.diligence.service.diligence.DiligenceChatService;
import com.yuerong.diligence.web.security.Access;
import com.yuerong.diligence.web.sse.SseStreams;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ChatController {
  private final DiligenceChatService chat;
  private final Access access;
  private final SseStreams streams;
  public ChatController(DiligenceChatService chat, Access access, SseStreams streams) {
    this.chat = chat; this.access = access; this.streams = streams;
  }

  @PostMapping(value = "/api/v1/diligence/sessions/{task}/chat/events", produces = "text/event-stream")
  public SseEmitter events(@PathVariable String task, @RequestBody JsonNode body) {
    return streams.open(chat.prepare(task, access.user(), body));
  }
}
