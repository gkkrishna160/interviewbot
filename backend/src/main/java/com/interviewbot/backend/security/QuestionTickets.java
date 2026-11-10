package com.interviewbot.backend.security;

import static com.interviewbot.backend.dtos.MockDtos.*;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** A signed question snapshot prevents client tampering without storing interview sessions. */
@Component
public class QuestionTickets {
  private final byte[] key;
  private final ObjectMapper json;

  private record Ticket(Data question, long expires) {}

  public QuestionTickets(
      ObjectMapper mapper,
      @org.springframework.beans.factory.annotation.Value("${interview.signing-key:}")
          String configured) {
    json =
        mapper
            .copy()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    if (configured.isBlank()) {
      key = new byte[32];
      new SecureRandom().nextBytes(key);
    } else {
      try {
        key = Base64.getDecoder().decode(configured);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("interview.signing-key must be base64 encoded");
      }
      if (key.length < 32)
        throw new IllegalArgumentException(
            "interview.signing-key requires at least 32 random bytes");
    }
  }

  public Question sign(Data data) {
    try {
      String payload =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  json.writeValueAsBytes(
                      new Ticket(data, Instant.now().plusSeconds(28800).getEpochSecond())));
      return new Question(data, payload + "." + signature(payload));
    } catch (Exception e) {
      throw new IllegalStateException("Could not sign question", e);
    }
  }

  public Data verify(String token) {
    try {
      if (token == null || token.length() > 10000) throw new IllegalArgumentException();
      String[] parts = token.split("\\.", -1);
      if (parts.length != 2
          || !MessageDigest.isEqual(
              signature(parts[0]).getBytes(StandardCharsets.US_ASCII),
              parts[1].getBytes(StandardCharsets.US_ASCII))) throw new IllegalArgumentException();
      Ticket ticket = json.readValue(Base64.getUrlDecoder().decode(parts[0]), Ticket.class);
      if (ticket.expires() < Instant.now().getEpochSecond() || ticket.question() == null)
        throw new IllegalArgumentException();
      return ticket.question();
    } catch (Exception e) {
      throw new ResponseStatusException(
          BAD_REQUEST, "Question is invalid or expired. Start a new practice interview.");
    }
  }

  private String signature(String payload) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
  }
}
