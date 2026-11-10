package com.interviewbot.backend.security;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class ApiProtectionFilterTest {
  private ApiProtectionFilter filter(int permits, int rate, String key, String address) {
    return new ApiProtectionFilter(permits, rate, 16384, key, address, "http://localhost:5173");
  }

  @Test
  void rejectsOversizedBodiesBeforeInvokingControllers() throws Exception {
    var f = filter(2, 20, "", "127.0.0.1");
    var r = new MockHttpServletRequest("POST", "/api/interview/assess");
    r.setContent(new byte[16385]);
    var response = new MockHttpServletResponse();
    f.doFilter(
        r,
        response,
        (a, b) -> {
          throw new AssertionError("Controller called for oversized body");
        });
    assertThat(response.getStatus()).isEqualTo(413);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getHeader("X-Request-ID")).isNotBlank();
  }

  @Test
  void protectsPublicGenerationWritesAndAcceptsAnExplicitKey() throws Exception {
    var f = filter(2, 20, "a-private-administration-key-for-tests-only", "0.0.0.0");
    var request = new MockHttpServletRequest("POST", "/api/question-bank/agents/run");
    var denied = new MockHttpServletResponse();
    f.doFilter(
        request,
        denied,
        (a, b) -> {
          throw new AssertionError("Unauthorized write reached controller");
        });
    assertThat(denied.getStatus()).isEqualTo(403);
    request.addHeader("X-Admin-Key", "a-private-administration-key-for-tests-only");
    var response = new MockHttpServletResponse();
    boolean[] reached = {false};
    f.doFilter(request, response, (a, b) -> reached[0] = true);
    assertThat(reached[0]).isTrue();
  }

  @Test
  void deniesUnkeyedGenerationControlsWhenListeningPublicly() throws Exception {
    var f = filter(2, 20, "", "0.0.0.0");
    var r = new MockHttpServletRequest("POST", "/api/question-bank/agents/run");
    var response = new MockHttpServletResponse();
    f.doFilter(
        r,
        response,
        (a, b) -> {
          throw new AssertionError("Unprotected administration");
        });
    assertThat(response.getStatus()).isEqualTo(403);
  }

  @Test
  void rateLimitsExpensiveCalls() throws Exception {
    var f = filter(2, 1, "", "127.0.0.1");
    var first = new MockHttpServletResponse();
    f.doFilter(new MockHttpServletRequest("POST", "/api/interview/assess"), first, (a, b) -> {});
    var next = new MockHttpServletResponse();
    f.doFilter(
        new MockHttpServletRequest("POST", "/api/interview/assess"),
        next,
        (a, b) -> {
          throw new AssertionError("Rate limit bypassed");
        });
    assertThat(next.getStatus()).isEqualTo(429);
    assertThat(next.getHeader("Retry-After")).isEqualTo("60");
  }

  @Test
  void limitsConcurrentRequestsAndReleasesCapacityAfterFailure() throws Exception {
    var f = filter(1, 20, "", "127.0.0.1");
    try (var executor = Executors.newSingleThreadExecutor()) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var task =
          executor.submit(
              () -> {
                try {
                  f.doFilter(
                      new MockHttpServletRequest("POST", "/api/interview/assess"),
                      new MockHttpServletResponse(),
                      (a, b) -> {
                        entered.countDown();
                        try {
                          release.await(3, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                      });
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      var busy = new MockHttpServletResponse();
      f.doFilter(
          new MockHttpServletRequest("POST", "/api/interview/assess"),
          busy,
          (a, b) -> {
            throw new AssertionError("Concurrency limit bypassed");
          });
      assertThat(busy.getStatus()).isEqualTo(503);
      release.countDown();
      task.get(2, TimeUnit.SECONDS);
      var available = new MockHttpServletResponse();
      f.doFilter(
          new MockHttpServletRequest("POST", "/api/interview/assess"), available, (a, b) -> {});
      assertThat(available.getStatus()).isEqualTo(200);
    }
  }

  @Test
  void rejectsEncodedOrMatrixPathsBeforeResourceLimitsCanBeBypassed() throws Exception {
    for (String path :
        java.util.List.of(
            "/%61pi/interview/assess", "/api/interview/assess;x=1", "/api//interview/assess")) {
      var response = new MockHttpServletResponse();
      filter(1, 10, "", "127.0.0.1")
          .doFilter(
              new MockHttpServletRequest("POST", path),
              response,
              (a, b) -> {
                throw new AssertionError("Noncanonical path reached a controller");
              });
      assertThat(response.getStatus()).isEqualTo(400);
    }
  }
}
