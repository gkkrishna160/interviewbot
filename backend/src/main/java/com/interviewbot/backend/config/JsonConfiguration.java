package com.interviewbot.backend.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.*;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.*;

@Configuration
public class JsonConfiguration {
  @Bean
  Jackson2ObjectMapperBuilderCustomizer strictRequestJson() {
    return builder -> {
      builder.featuresToEnable(
          DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
          JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
      builder.featuresToDisable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
      builder.postConfigurer(
          mapper ->
              mapper
                  .getFactory()
                  .setStreamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(32)
                          .maxStringLength(16000)
                          .build()));
    };
  }
}
