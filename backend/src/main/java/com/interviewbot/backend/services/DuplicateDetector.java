package com.interviewbot.backend.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class DuplicateDetector {
  public String normalize(String text) {
    return Normalizer.normalize(text, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^\\p{L}\\p{N}]+", " ")
        .trim()
        .replaceAll("\\s+", " ");
  }

  public String fingerprint(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(normalize(text).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  public double similarity(String a, String b) {
    Set<String> left = new HashSet<>(Arrays.asList(normalize(a).split(" ")));
    Set<String> right = new HashSet<>(Arrays.asList(normalize(b).split(" ")));
    Set<String> union = new HashSet<>(left);
    union.addAll(right);
    left.retainAll(right);
    return union.isEmpty() ? 0 : (double) left.size() / union.size();
  }
}
