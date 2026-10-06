package com.yuerong.diligence;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

@SpringBootApplication
public class YuerongDiligenceApplication extends SpringBootServletInitializer {
  @Override
  protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
    return application.sources(YuerongDiligenceApplication.class);
  }

  public static void main(String[] args) {
    SpringApplication.run(YuerongDiligenceApplication.class, args);
  }
}
