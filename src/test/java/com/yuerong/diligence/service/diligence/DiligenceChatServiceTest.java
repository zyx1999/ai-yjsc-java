package com.yuerong.diligence.service.diligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuerong.diligence.ai.model.AgentRequest;
import com.yuerong.diligence.ai.port.AgentPort;
import com.yuerong.diligence.common.*;
import com.yuerong.diligence.common.stream.*;
import com.yuerong.diligence.model.diligence.Contracts;
import com.yuerong.diligence.repository.EnterpriseDataPort;
import com.yuerong.diligence.support.InMemoryStore;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DiligenceChatServiceTest {
  InMemoryStore store;
  DiligenceService biz;
  FilesService files;
  AgentPort agent;
  DiligenceChatService chats;
  String task;
  @BeforeEach void setup() {
    store=new InMemoryStore();
    biz=new DiligenceService(store,mock(EnterpriseDataPort.class),new Contracts(),mock(RuleService.class));
    files=mock(FilesService.class);agent=mock(AgentPort.class);
    when(agent.provider()).thenReturn("fixture");when(agent.initialSession()).thenReturn("platform-session");
    when(agent.chat(any())).thenReturn(Json.obj("session_id","platform-session","answer","完成"));
    chats=new DiligenceChatService(biz,files,agent,"test");task=biz.create("owner").path("task_id").asText();
  }
  List<JsonNode> execute(EventStreamTask run) throws Exception {
    List<JsonNode> events=new ArrayList<>();
    run.execute((id,type,event)->{
      biz.contracts.check("CardEvent",event);
      assertEquals(event.path("event_id").asText(),id);assertEquals(event.path("type").asText(),type);
      events.add(event.deepCopy());
    });return events;
  }
  @Test void completesAndPersistsWithoutServletOrEmitter() throws Exception {
    List<JsonNode> events=execute(chats.prepare(task,"owner",Json.obj("text","你好")));
    assertEquals(Arrays.asList("run.started","run.progress","answer.completed","run.completed"),types(events));
    for(int i=0;i<events.size();i++)assertEquals(i+1,events.get(i).path("sequence").asInt());
    ObjectNode state=biz.session(task,"owner");assertFalse(state.path("running").asBoolean());
    assertEquals(2,state.path("messages").size());
    assertEquals("SUCCEEDED",biz.operation(task,events.get(0).path("run_id").asText()).path("status").asText());
    ArgumentCaptor<AgentRequest> request=ArgumentCaptor.forClass(AgentRequest.class);verify(agent).chat(request.capture());
    assertEquals(task,request.getValue().variables.get(0).path("value").asText());
    assertEquals("platform-session",state.at("/platform_sessions/fixture:test").asText());
  }
  @Test void unknownResultRemainsUnknownAndDoesNotRetry() throws Exception {
    when(agent.chat(any())).thenThrow(new Fault("RESULT_UNKNOWN","连接中断",502));
    List<JsonNode> events=execute(chats.prepare(task,"owner",Json.obj("text","你好")));
    assertEquals("run.unknown",events.get(events.size()-1).path("type").asText());
    assertTrue(biz.session(task,"owner").path("unknown_run").asBoolean());
    assertFalse(biz.session(task,"owner").path("running").asBoolean());
    execute(chats.prepare(task,"owner",Json.obj("text","再次发送")));
    verify(agent,times(1)).chat(any());
  }
  @Test void failedAndRejectedRunsArePersisted() throws Exception {
    when(agent.chat(any())).thenThrow(new Fault("PLATFORM_INTERNAL_ERROR","平台异常",502));
    List<JsonNode> events=execute(chats.prepare(task,"owner",Json.obj("text","你好")));
    assertEquals("run.failed",events.get(events.size()-1).path("type").asText());
    EventStreamTask rejected=chats.prepare(task,"owner",Json.obj("text","排队"));rejected.rejected();
    List<ObjectNode> operations=store.list("operation",task);
    assertEquals("FAILED",operations.get(1).at("/data/status").asText());
    assertEquals("RUN_BUSY",operations.get(1).at("/data/error/code").asText());
  }
  @Test void enforcesOwnershipAndAttachmentOwnership() throws Exception {
    assertEquals("FORBIDDEN",assertThrows(Fault.class,()->chats.prepare(task,"other",Json.obj("text","你好"))).code);
    assertEquals("INVALID_ARGUMENT",assertThrows(Fault.class,()->chats.prepare(task,"owner",Json.obj("text"," "))).code);
    store.create("file","wrong-file","other-task",Json.obj("task_id","other-task","role","FINANCIAL"));
    List<JsonNode> events=execute(chats.prepare(task,"owner",Json.obj("text","处理材料","attachment",Json.obj("file_id","wrong-file"))));
    assertEquals("run.failed",events.get(events.size()-1).path("type").asText());verifyNoInteractions(files);verify(agent,never()).chat(any());
  }
  @Test void preservesRegisteredAttachmentContext() throws Exception {
    store.create("file","file-1",task,Json.obj("task_id",task,"role","FINANCIAL","name","report.pdf"));
    when(files.read(task,"file-1")).thenReturn(new byte[]{1,2});
    execute(chats.prepare(task,"owner",Json.obj("text","处理材料","attachment",Json.obj("file_id","file-1"))));
    ArgumentCaptor<AgentRequest> request=ArgumentCaptor.forClass(AgentRequest.class);verify(agent).chat(request.capture());
    assertEquals(3,request.getValue().variables.size());assertTrue(request.getValue().attachment.path("instruction").asText().contains("financial-report"));
  }
  @Test void concurrentTurnCannotReenterSameSession() throws Exception {
    CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    when(agent.chat(any())).thenAnswer(call->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return Json.obj("session_id","platform-session","answer","完成");});
    ExecutorService worker=Executors.newSingleThreadExecutor();
    try {
      Future<List<JsonNode>> first=worker.submit(()->execute(chats.prepare(task,"owner",Json.obj("text","第一轮"))));
      assertTrue(entered.await(5,TimeUnit.SECONDS));
      List<JsonNode> second=execute(chats.prepare(task,"owner",Json.obj("text","第二轮")));
      assertEquals("RUN_BUSY",second.get(second.size()-1).at("/payload/error/code").asText());
      assertTrue(biz.session(task,"owner").path("running").asBoolean());
      release.countDown();first.get(5,TimeUnit.SECONDS);verify(agent,times(1)).chat(any());
    } finally {release.countDown();worker.shutdownNow();}
  }
  private List<String> types(List<JsonNode> events){List<String> types=new ArrayList<>();for(JsonNode event:events)types.add(event.path("type").asText());return types;}
}
