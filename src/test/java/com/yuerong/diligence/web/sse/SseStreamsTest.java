package com.yuerong.diligence.web.sse;

import com.yuerong.diligence.common.*;
import com.yuerong.diligence.common.stream.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SseStreamsTest {
  @org.springframework.context.annotation.Profile("standalone-test-only")
  @RestController static class AnotherScenario {
    final SseStreams streams;
    AnotherScenario(SseStreams streams) { this.streams=streams; }
    @GetMapping(value="/news/events",produces="text/event-stream")
    SseEmitter events() {
      return streams.open(new EventStreamTask() {
        public void execute(EventSink sink) throws Exception {sink.send("news-1","news.item",Json.obj("headline","示例"));}
        public void rejected() {fail("should be scheduled");}
      });
    }
  }
  @org.springframework.context.annotation.Profile("standalone-test-only")
  @RestController static class SlowScenario {
    final SseStreams streams;
    SlowScenario(SseStreams streams) { this.streams=streams; }
    @GetMapping(value="/slow/events",produces="text/event-stream")
    SseEmitter events() {
      return streams.open(new EventStreamTask() {
        public void execute(EventSink sink) throws Exception {
          sink.send("slow-1","run.progress",Json.obj("step",1));
          Thread.sleep(200);
        }
        public void rejected() {fail("should be scheduled");}
      });
    }
  }
  @Test void transportsAnotherScenarioWithoutDiligenceContract() throws Exception {
    SseStreams streams=new SseStreams(5000);
    try {
      MockMvc mvc=MockMvcBuilders.standaloneSetup(new AnotherScenario(streams)).build();
      MvcResult first=mvc.perform(get("/news/events")).andExpect(request().asyncStarted()).andReturn();
      first.getAsyncResult(3000);
      mvc.perform(asyncDispatch(first)).andExpect(status().isOk())
          .andExpect(content().string(containsString("id:news-1")))
          .andExpect(content().string(containsString("event:news.item")))
          .andExpect(content().string(containsString("headline")));
    } finally {streams.shutdown();}
  }
  @Test void keepsLongRunsAliveWithHeartbeatComments() throws Exception {
    // 长耗时模型等待期间连接空闲：心跳注释帧保持连接，且不干扰业务事件。
    SseStreams streams=new SseStreams(Executors.newSingleThreadExecutor(),5000,40);
    try {
      MockMvc mvc=MockMvcBuilders.standaloneSetup(new SlowScenario(streams)).build();
      MvcResult result=mvc.perform(get("/slow/events")).andExpect(request().asyncStarted()).andReturn();
      result.getAsyncResult(3000);
      mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
          .andExpect(content().string(containsString(":keep-alive")))
          .andExpect(content().string(containsString("run.progress")));
    } finally {streams.shutdown();}
  }
  @Test void saturationInvokesScenarioRejectionAndNeverExecutes() {
    ExecutorService stopped=Executors.newSingleThreadExecutor();stopped.shutdown();
    SseStreams streams=new SseStreams(stopped,5000);AtomicBoolean rejected=new AtomicBoolean();
    Fault error=assertThrows(Fault.class,()->streams.open(new EventStreamTask(){
      public void execute(EventSink sink){fail("must not execute rejected work");}
      public void rejected(){rejected.set(true);}
    }));
    assertEquals("RUN_BUSY",error.code);assertTrue(rejected.get());
  }
}
