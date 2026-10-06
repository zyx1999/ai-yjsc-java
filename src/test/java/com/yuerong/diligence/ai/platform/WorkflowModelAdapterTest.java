package com.yuerong.diligence.ai.platform;

import com.yuerong.diligence.common.Fault;
import com.yuerong.diligence.common.Json;
import com.yuerong.diligence.model.diligence.Contracts;
import com.yuerong.diligence.service.diligence.DiligenceModelService;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;

class WorkflowModelAdapterTest {
  String event(String name, JsonNode value) {return "event: "+name+"\ndata: "+value+"\n\n";}
  String result(String node, JsonNode value) {return event("message",Json.obj("additional_kwargs",Json.obj("node_id",node,"node_title","模型结果","node_output",Json.obj("output",value))));}
  JsonNode consume(String text) throws Exception {return WorkflowModelAdapter.consume(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),System.currentTimeMillis()+2000);}
  @Test void routesByBusinessTaskAndNeverFallsBackForInvestigation() throws Exception {
    List<String> paths = new ArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      paths.add(exchange.getRequestURI().getPath());
      byte[] bytes = (result("end", Json.obj("value", "ok")) + "event: done\ndata: [DONE]\n\n")
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes); exchange.close();
    });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      WorkflowModelAdapter adapter = new WorkflowModelAdapter( base + "/investigation/use_as_tool",
          base + "/document/", "{}", 10000);
      for (String task : Arrays.asList("investigation.ai_summary", "financial_report.analyze", "enterprise_credit.extract"))
        assertEquals("ok", new DiligenceModelService(adapter).infer(task, "合成测试", Json.obj(), Json.obj("type", "object"), null).path("value").asText());
      assertEquals(Arrays.asList("/investigation/use_as_tool", "/document/chatabc/use_as_tool", "/document/chatabc/use_as_tool"), paths);
      WorkflowModelAdapter missing = new WorkflowModelAdapter( "", base + "/document", "{}", 10000);
      assertEquals("MODEL_NOT_CONFIGURED", assertThrows(Fault.class, () -> new DiligenceModelService(missing).infer(
          "investigation.ai_summary", "测试", Json.obj(), Json.obj(), null)).code);
      assertEquals("INVALID_ARGUMENT", assertThrows(Fault.class, () -> new DiligenceModelService(adapter).infer(
          "investigation.ai_summary", "测试", Json.obj(), Json.obj(), Json.obj("name", "page.png"))).code);
      assertEquals(3, paths.size());
    } finally { server.stop(0); }
  }

  @Test void resultRequiresUniqueNodeAndSuccessfulDone() throws Exception {
    String good=result("end",Json.obj("value","合成结果"));
    assertEquals("合成结果",consume(good+event("done",Json.obj())).path("value").asText());
    for(String invalid:Arrays.asList(good, event("done",Json.obj()),good+event("failed",Json.obj()),good+event("interrupt",Json.obj()),good+result("other",Json.obj("value","合成结果"))+event("done",Json.obj())))
      assertThrows(Fault.class,()->consume(invalid));
  }
  @Test void terminalEventsAreHandledBeforeParsingTheirData() throws Exception {
    String good = result("end", Json.M.getNodeFactory().textNode("{\"value\":\"合成结果\"}"));
    for (String ending : Arrays.asList("event: done\n\n", "event: done\ndata:\n\n",
        "event: done\ndata: [DONE]\n\n", "event: done\ndata: \"[DONE]\"\n\n",
        "event: done\ndata: arbitrary non-JSON marker\n\n")) {
      assertEquals("合成结果", consume(good + ending).path("value").asText());
      assertEquals("MODEL_PROTOCOL_ERROR", assertThrows(Fault.class, () -> consume(ending)).code);
    }
    for (String event : Arrays.asList("failed", "interrupt")) {
      for (String data : Arrays.asList("", "data: non-JSON failure\n")) {
        assertEquals("failed".equals(event) ? "MODEL_FAILED" : "MODEL_INTERRUPTED",
            assertThrows(Fault.class, () -> consume(good + "event: " + event + "\n" + data + "\n")).code);
      }
    }
    for (String ending : Arrays.asList("event: done", "event: done\n", "event: done\ndata: [DONE]\n"))
      assertEquals("RESULT_UNKNOWN", assertThrows(Fault.class, () -> consume(good + ending)).code);
    assertThrows(Fault.class, () -> consume("event: message\ndata: {'invalid': True}\n\nevent: done\n\n"));
  }
  @Test void textAndImageCallsUseIndependentSessionAndValidateEvidence() throws Exception {
    HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    List<String> paths=new ArrayList<>(),bodies=new ArrayList<>();
    server.createContext("/workflow",exchange->{
      String path=exchange.getRequestURI().getPath(); paths.add(path);
      ByteArrayOutputStream in=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
      while((count=exchange.getRequestBody().read(buffer))!=-1)in.write(buffer,0,count);
      bodies.add(new String(in.toByteArray(),StandardCharsets.UTF_8));
      String output;
      if(path.endsWith("init_session"))output=Json.obj("resCode","FAIAG0000","data",Json.obj("session_id","image-session")).toString();
      else if(path.endsWith("upload_file"))output=Json.obj("resCode","FAIAG0000","data",Json.obj("file_path","/files/page.png")).toString();
      else output=result("end",Json.obj("text","已提取","evidence_refs",Json.arr("DOC-1")))+event("done",Json.obj());
      byte[] bytes=output.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type",path.endsWith("use_as_tool")||path.endsWith("chat")?"text/event-stream":"application/json");
      exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    });server.start();
    try {
      WorkflowModelAdapter adapter=new WorkflowModelAdapter("","http://127.0.0.1:"+server.getAddress().getPort()+"/workflow","{}",10000);
      JsonNode input=Json.obj("fragments",Json.arr(Json.obj("evidence_id","DOC-1")));
      JsonNode schema=Json.obj("type","object");
      JsonNode first=new DiligenceModelService(adapter).infer("CHECK","提取",input,schema,null);
      assertEquals(first,new DiligenceModelService(adapter).infer("CHECK","提取",input,schema,Json.obj("name","page.png","base64",Base64.getEncoder().encodeToString(new byte[]{(byte)137,80,78,71,13,10,26,10}))));
      assertEquals(Arrays.asList("/workflow/chatabc/use_as_tool","/workflow/chatabc/init_session","/workflow/chatabc/upload_file","/workflow/chatabc/chat"),paths);
      assertNotEquals("image-session",Json.parse(bodies.get(0)).path("session_id").asText());
      assertEquals("image-session",Json.parse(bodies.get(3)).at("/data/session_id").asText());
      assertTrue(bodies.get(2).contains("image-session"));
      JsonNode init = Json.parse(bodies.get(1));
      assertFalse(init.has("agent_id"));
      assertEquals("image", init.at("/data/config_variables/0/name").asText());
      assertEquals("page.png", init.at("/data/config_variables/0/value").asText());
      assertTrue(bodies.get(2).contains("filename=\"page.png\""));
      JsonNode expected = Json.obj("instruction", "提取", "input_data", input, "response_schema", schema);
      assertEquals(expected, Json.parse(Json.parse(bodies.get(0)).path("txt").asText()));
      assertEquals(expected, Json.parse(Json.parse(bodies.get(3)).at("/data/txt").asText()));
      assertEquals("EVIDENCE_INVALID",assertThrows(Fault.class,()->new DiligenceModelService(adapter).infer("CHECK","提取",Json.obj("fragments",Json.arr()),schema,null)).code);
    }finally{server.stop(0);}
  }

  @Test void transportCanServeAnUnrelatedScenarioWithItsOwnEvidencePolicy() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/support", exchange -> {
      byte[] bytes = (result("end", Json.obj("text", "回答", "evidence_refs", Json.arr("support-ticket")))
          + "event: done\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    }); server.start();
    try {
      WorkflowModelAdapter adapter = new WorkflowModelAdapter(
          "http://127.0.0.1:"+server.getAddress().getPort()+"/support", "", "{}", 10000);
      JsonNode answer = adapter.infer(new com.yuerong.diligence.ai.model.ModelRequest(
          com.yuerong.diligence.ai.model.ModelRequest.Workflow.TEXT, "support.answer", "回答工单",
          Json.obj(), Json.obj("type", "object"), null));
      assertEquals("support-ticket", answer.path("evidence_refs").get(0).asText());
    } finally {server.stop(0);}
  }

  @Test void selectedOutputMayBeWrappedExactlyOnce() throws Exception {
    JsonNode expected=Json.obj("value","合成结果");
    JsonNode schema=Json.obj("type","object","required",Json.arr("value"),"additionalProperties",false,
        "properties",Json.obj("value",Json.obj("type","string")));
    Contracts contracts=new Contracts();
    JsonNode wrapped=Json.obj("output",expected.toString());
    for(JsonNode selected:Arrays.asList(wrapped,Json.obj("output",expected),Json.M.getNodeFactory().textNode(wrapped.toString()))) {
      JsonNode parsed=consume(result("end",selected)+"event: done\ndata: [DONE]\n\n");
      assertEquals(expected,parsed);contracts.validate(schema,parsed,"model_output");
    }
    for(JsonNode selected:Arrays.asList(Json.obj("output",wrapped),Json.obj("output",expected,"extra",true))) {
      JsonNode parsed=consume(result("end",selected)+event("done",Json.obj()));
      assertThrows(Fault.class,()->contracts.validate(schema,parsed,"model_output"));
    }
    for(JsonNode invalid:Arrays.asList(Json.obj("output","{'value': 'invalid'}"),Json.obj("output",null),Json.obj("output","null")))
      assertThrows(Fault.class,()->consume(result("end",invalid)+event("done",Json.obj())));
  }
}
