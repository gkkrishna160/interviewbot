package com.interviewbot.backend.security;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.response.*;
import java.util.*;

/** Runs on EVERY provider call, including subsequent ADK tool-loop turns. */
public final class GuardedChatModel implements ChatModel {
  private final ChatModel delegate;
  private final boolean tools;
  private final String modelName;
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(GuardedChatModel.class);
  private final PromptGuard guard = new PromptGuard();

  public GuardedChatModel(ChatModel delegate, boolean tools) {
    this(delegate, tools, "configured-model");
  }

  public GuardedChatModel(ChatModel delegate, boolean tools, String modelName) {
    this.delegate = delegate;
    this.tools = tools;
    this.modelName = modelName;
  }

  @Override
  public ChatResponse doChat(ChatRequest request) {
    for (var message : request.messages()) {
      if (message instanceof UserMessage user)
        for (var c : user.contents()) {
          if (c instanceof TextContent t) guard.checkPayload(t.text());
          else throw new IllegalArgumentException("Only text input is permitted");
        }
      if (message instanceof ToolExecutionResultMessage result) {
        guard.tool(result.toolName(), Map.of());
        guard.checkPayload(result.text());
      }
      if (message instanceof AiMessage assistant && assistant.hasToolExecutionRequests())
        for (var call : assistant.toolExecutionRequests()) {
          if (!tools) throw new IllegalArgumentException("This agent has no tools");
          guard.tool(call.name(), Map.of());
          guard.checkPayload(call.arguments());
        }
    }
    long started = System.nanoTime();
    boolean succeeded = false;
    log.info("Model request started: model={}", modelName);
    ChatResponse response;
    try {
      response = delegate.chat(request);
      succeeded = true;
    } finally {
      log.info(
          "Model request finished: model={} status={} durationMs={}",
          modelName,
          succeeded ? "SUCCEEDED" : "FAILED",
          (System.nanoTime() - started) / 1_000_000);
    }
    if (response.aiMessage() != null && response.aiMessage().hasToolExecutionRequests())
      for (var call : response.aiMessage().toolExecutionRequests()) {
        if (!tools) throw new IllegalArgumentException("This agent has no tools");
        guard.tool(call.name(), Map.of());
        guard.checkPayload(call.arguments());
      }
    return response;
  }

  @Override
  public ChatRequestParameters defaultRequestParameters() {
    return delegate.defaultRequestParameters();
  }

  @Override
  public Set<Capability> supportedCapabilities() {
    return delegate.supportedCapabilities();
  }

  @Override
  public dev.langchain4j.model.ModelProvider provider() {
    return delegate.provider();
  }
}
