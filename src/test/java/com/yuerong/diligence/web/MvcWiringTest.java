package com.yuerong.diligence.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuerong.diligence.ai.model.AgentRequest;
import com.yuerong.diligence.ai.port.*;
import com.yuerong.diligence.common.Json;
import com.yuerong.diligence.repository.ApplicationStorePort;
import com.yuerong.diligence.support.InMemoryStore;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Boots the real MVC/service configuration using isolated fixtures, never the configured business DB. */
@SpringBootTest(properties={
  "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
  "spring.datasource.initialization-mode=never", "diligence.source.adapter=demo",
  "diligence.jobs.enabled=false", "diligence.demo.seed-session=false",
  "diligence.security.dev-user=mvc-test-user", "diligence.files.root=target/mvc-context-files"})
@Import(MvcWiringTest.Fixtures.class)
@AutoConfigureMockMvc
class MvcWiringTest {
  @TestConfiguration static class Fixtures {
    @Bean @Primary ApplicationStorePort testStore(){return new InMemoryStore();}
    @Bean JdbcTemplate jdbc(){return mock(JdbcTemplate.class);}
    @Bean NamedParameterJdbcTemplate namedJdbc(){return mock(NamedParameterJdbcTemplate.class);}
    @Bean PlatformTransactionManager transactionManager(){return mock(PlatformTransactionManager.class);}
  }
  @Autowired MockMvc mvc;
  @MockBean AgentPort agent;
  @MockBean ModelInferencePort model;
  @BeforeEach void configureAgent(){
    when(agent.provider()).thenReturn("fixture");when(agent.initialSession()).thenReturn("fixture-session");
    when(agent.chat(any())).thenAnswer(call->{AgentRequest req=call.getArgument(0);return Json.obj("session_id",req.session,"answer","测试回答");});
  }
  String create() throws Exception {
    return Json.parse(mvc.perform(post("/api/v1/diligence/sessions"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).at("/data/task_id").asText();
  }
  @Test void routesSessionsGatewayAndCatalogThroughServices() throws Exception {
    String task=create();assertFalse(task.isEmpty());
    mvc.perform(get("/api/v1/diligence/catalog")).andExpect(status().isOk());
    mvc.perform(post("/api/v1/diligence/enterprise/resolve").contentType("application/json")
        .content(Json.obj("task_id",task,"arguments",Json.obj("credit_code","91310000MA00000001")).toString()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.resolution").value("MATCHED"));
    mvc.perform(post("/internal/diligence/gateway").contentType("application/json")
        .content(Json.obj("request_id","mvc-context","action","session.context","context",Json.obj("task_id",task),"arguments",Json.obj()).toString()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.data.bound_subject.credit_code").value("91310000MA00000001"));
    mvc.perform(delete("/api/v1/diligence/sessions/"+task)).andExpect(status().isOk());
    mvc.perform(get("/api/v1/diligence/sessions/"+task)).andExpect(status().isNotFound());
  }
  @Test void realControllerPreservesSseAndSavedMessages() throws Exception {
    String task=create();
    MvcResult pending=mvc.perform(post("/api/v1/diligence/sessions/"+task+"/chat/events")
        .contentType("application/json").content("{\"text\":\"你好\"}"))
        .andExpect(request().asyncStarted()).andReturn();
    pending.getAsyncResult(4000);
    mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
        .andExpect(content().string(containsString("event:answer.completed")))
        .andExpect(content().string(containsString("event:run.completed")));
    mvc.perform(get("/api/v1/diligence/sessions/"+task)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.messages[1].text").value("测试回答"));
    mvc.perform(post("/api/v1/diligence/sessions/"+task+"/chat/events")
        .contentType("application/json").content("{\"text\":\" \"}")).andExpect(status().isBadRequest());
  }
}
