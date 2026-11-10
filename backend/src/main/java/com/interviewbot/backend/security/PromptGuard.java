package com.interviewbot.backend.security;

import com.fasterxml.jackson.databind.*;
import java.text.Normalizer;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Defense in depth: bounded structured data, injection checks, isolated prompts and restricted
 * tools.
 */
@Component
public class PromptGuard {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern ATTACK =
      Pattern.compile(
          "(?im)(?:^|[.!?]\\s+|\\n"
              + ")\\s*(?:(?:ignore|disregard|forget|override)\\s+(?:all\\s+)?(?:the\\s+)?(?:previous|prior|above|system|developer|your|these)\\s+(?:instructions?|rules?|prompts?|messages?)|(?:give|award|assign)\\s+(?:me\\s+)?(?:full"
              + " marks|(?:a\\s+)?(?:score\\s+of\\s+)?10\\s*/\\s*10)|(?:reveal|print|expose|leak)\\s+(?:the\\s+|your\\s+)?(?:system"
              + " prompt|api key|secret|password)|(?:system|developer|assistant)\\s*:)");
  private static final Pattern ROLES =
      Pattern.compile(
          "(?i)<\\|(?:im_start|system|developer|assistant|start_header_id)\\|>|\\[/?INST\\]");

  public void checkText(String text) {
    if (text == null) return;
    if (text.length() > 50000) throw new IllegalArgumentException("AI input is too long");
    String normalized =
        Normalizer.normalize(text, Normalizer.Form.NFKC).replaceAll("[\\p{Cf}\\x00]", "");
    // Quoted examples remain data; direct role delimiters cannot change message roles.
    String instructions =
        normalized.replaceAll(
            "(?s)```.*?```|`[^`]*`|\"[^\"]*\"|‘[^’]*’|“[^”]*”", " [quoted example] ");
    if (instructions.replace(" [quoted example] ", "").trim().length() < 8)
      instructions = normalized;
    if (ATTACK.matcher(instructions).find() || ROLES.matcher(instructions).find())
      throw new IllegalArgumentException(
          "Potential prompt injection detected. Submit only the question or your own answer,"
              + " without instructions to the AI.");
  }

  public void checkPayload(String payload) {
    if (payload == null || payload.length() > 180000)
      throw new IllegalArgumentException("AI payload exceeds its limit");
    try {
      scan(JSON.readTree(payload), 0);
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      checkText(payload);
    }
  }

  private void scan(JsonNode node, int depth) {
    if (node == null) return;
    if (depth > 24) throw new IllegalArgumentException("AI input nesting exceeds its limit");
    if (node.isTextual()) checkText(node.asText());
    else if (node.isContainerNode()) node.forEach(n -> scan(n, depth + 1));
  }

  public void tool(String name, Object arguments) {
    if (!java.util.Set.of("generate_for_topic", "review_pending").contains(name))
      throw new IllegalArgumentException("Tool is not permitted");
    try {
      checkPayload(JSON.writeValueAsString(arguments));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid tool arguments");
    }
  }
}
