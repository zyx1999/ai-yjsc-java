package com.yuerong.diligence.architecture;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Prevent infrastructure and controllers from accumulating scenario orchestration again. */
class LayerBoundariesTest {
  private final Path root=Paths.get("src/main/java/com/yuerong/diligence");
  @Test void commonAiAndSseAreIndependentOfBusinessAndPersistence() throws Exception {
    for(String layer:Arrays.asList("common","ai","web/sse")) {
      try(Stream<Path> paths=Files.walk(root.resolve(layer))) {
        for(Path path:(Iterable<Path>)paths.filter(p->p.toString().endsWith(".java"))::iterator) {
          String source=new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
          for(String forbidden:Arrays.asList("com.yuerong.diligence.service", "com.yuerong.diligence.model.diligence", "com.yuerong.diligence.repository", "DILIGENCE_V1_DRAFT", "financial-report", "runtime_task_id"))
            assertFalse(source.contains(forbidden),path+" depends on "+forbidden);
        }
      }
    }
  }
  @Test void servicesDoNotDependOnHttpOrPlatformImplementations() throws Exception {
    try(Stream<Path> paths=Files.walk(root.resolve("service"))) {
      for(Path path:(Iterable<Path>)paths.filter(p->p.toString().endsWith(".java"))::iterator) {
        String source=new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
        for(String forbidden:Arrays.asList("org.springframework.web", "javax.servlet", "java.net.", "com.yuerong.diligence.web", "com.yuerong.diligence.ai.platform"))
          assertFalse(source.contains(forbidden),path+" depends on "+forbidden);
      }
    }
  }
  @Test void controllersDoNotDirectlyQueryOrUpdateRepositories() throws Exception {
    try(Stream<Path> paths=Files.walk(root.resolve("web/controller"))) {
      for(Path path:(Iterable<Path>)paths.filter(p->p.toString().endsWith(".java"))::iterator) {
        String source=new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
        for(String forbidden:Arrays.asList(".store.", "JdbcTemplate", "com.yuerong.diligence.repository", "AgentPort", "ModelInferencePort"))
          assertFalse(source.contains(forbidden),path+" bypasses its application service");
      }
    }
  }
}
