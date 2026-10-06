package com.yuerong.diligence.config;

import com.yuerong.diligence.repository.ApplicationStorePort;
import com.yuerong.diligence.repository.EnterpriseDataPort;
import com.yuerong.diligence.repository.source.demo.DemoSourceAdapter;
import com.yuerong.diligence.repository.source.mysql.MysqlSourceAdapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration
public class SourceConfiguration {
  @Bean
  public EnterpriseDataPort source(
      ApplicationStorePort store,
      NamedParameterJdbcTemplate jdbc,
      @Value("${diligence.source.adapter:mysql}") String adapter,
      @Value("${diligence.source.mapping:}") String mapping) {
    switch (adapter) {
      case "demo":
        return new DemoSourceAdapter(store);
      case "mysql":
        return new MysqlSourceAdapter(jdbc, mapping);
      default:
        throw new IllegalStateException("Unknown source adapter");
    }
  }

}
