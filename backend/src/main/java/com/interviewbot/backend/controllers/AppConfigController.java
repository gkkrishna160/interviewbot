package com.interviewbot.backend.controllers;

import com.interviewbot.backend.dtos.MockDtos;
import com.interviewbot.backend.services.MockInterviewService;
import org.springframework.web.bind.annotation.*;

@RestController
public class AppConfigController {
  private final MockInterviewService service;

  public AppConfigController(MockInterviewService service) {
    this.service = service;
  }

  @GetMapping("/api/config")
  public MockDtos.Config config() {
    return service.config();
  }
}
