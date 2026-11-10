package com.interviewbot.backend.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounded local-model work and request sizes; generation administration can require an API key. */
@Component
@Order(10)
public class ApiProtectionFilter extends OncePerRequestFilter {
  private record Window(long minute, java.util.concurrent.atomic.AtomicInteger count) {}

  private final ConcurrentHashMap<String, Window> clients = new ConcurrentHashMap<>();
  private final Semaphore permits;
  private final int requestsPerMinute;
  private final int bodyLimit;
  private final String adminKey;
  private final boolean loopback;
  private final Set<String> origins;
  private static final Logger log = LoggerFactory.getLogger(ApiProtectionFilter.class);

  public ApiProtectionFilter(
      @Value("${app.security.max-concurrent-ai-requests:2}") int concurrent,
      @Value("${app.security.ai-requests-per-minute:90}") int rate,
      @Value("${app.security.max-request-bytes:262144}") int limit,
      @Value("${app.security.generation-admin-key:}") String key,
      @Value("${server.address:127.0.0.1}") String address,
      @Value("${app.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
          String allowed) {
    if (concurrent < 1 || concurrent > 32 || rate < 1 || limit < 16384 || limit > 1048576)
      throw new IllegalArgumentException("Invalid API resource limits");
    if (!key.isBlank() && key.length() < 32)
      throw new IllegalArgumentException(
          "Generation administrator keys must contain at least 32 characters");
    permits = new Semaphore(concurrent);
    requestsPerMinute = rate;
    bodyLimit = limit;
    adminKey = key;
    loopback = Set.of("127.0.0.1", "::1", "localhost").contains(address);
    origins = Set.of(allowed.split(","));
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return false;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = UUID.randomUUID().toString();
    MDC.put("requestId", id);
    long start = System.nanoTime();
    boolean acquired = false;
    try {
      response.setHeader("X-Request-ID", id);
      response.setHeader("Cache-Control", "no-store");
      response.setHeader("X-Content-Type-Options", "nosniff");
      response.setHeader("X-Frame-Options", "DENY");
      response.setHeader("Referrer-Policy", "no-referrer");
      String origin = request.getHeader("Origin");
      if (origin != null && origins.contains(origin) && !"OPTIONS".equals(request.getMethod())) {
        response.setHeader("Access-Control-Allow-Origin", origin);
        response.setHeader("Vary", "Origin");
      }
      if (request.getContentLengthLong() > bodyLimit) {
        reject(response, 413, "Request body is too large");
        return;
      }
      boolean write = "POST".equals(request.getMethod());
      String path = request.getRequestURI();
      if (path.contains("%") || path.contains(";") || path.contains("//")) {
        reject(response, 400, "Use a canonical API path");
        return;
      }
      if (write && path.startsWith("/api/question-bank/") && (!adminKey.isBlank() || !loopback)) {
        String key = request.getHeader("X-Admin-Key");
        if (adminKey.isBlank()
            || key == null
            || !MessageDigest.isEqual(
                adminKey.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8))) {
          reject(response, 403, "A configured generation administrator key is required");
          return;
        }
      }
      boolean expensive =
          write
              && Set.of(
                      "/api/interview/assess",
                      "/api/interview/next-question",
                      "/api/question-bank/agents/run")
                  .contains(path);
      if (expensive) {
        long minute = System.currentTimeMillis() / 60000;
        if (clients.size() >= 4096 && !clients.containsKey(request.getRemoteAddr())) {
          clients.entrySet().removeIf(e -> e.getValue().minute() != minute);
          if (clients.size() >= 4096) {
            reject(response, 429, "Too many requests. Please try again shortly.");
            return;
          }
        }
        Window window =
            clients.compute(
                request.getRemoteAddr(),
                (ip, old) ->
                    old == null || old.minute() != minute
                        ? new Window(minute, new java.util.concurrent.atomic.AtomicInteger())
                        : old);
        if (window.count().incrementAndGet() > requestsPerMinute) {
          response.setHeader("Retry-After", "60");
          reject(response, 429, "Too many AI requests. Please wait a minute.");
          return;
        }
        if (!permits.tryAcquire()) {
          response.setHeader("Retry-After", "2");
          reject(response, 503, "The AI is busy. Please try again shortly.");
          return;
        }
        acquired = true;
      }
      chain.doFilter(new LimitedRequest(request, bodyLimit), response);
    } finally {
      if (acquired) permits.release();
      log.info(
          "HTTP {} {} status={} durationMs={}",
          request.getMethod(),
          request.getRequestURI(),
          response.getStatus(),
          (System.nanoTime() - start) / 1_000_000);
      MDC.remove("requestId");
    }
  }

  private void reject(HttpServletResponse response, int status, String message) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write("{\"message\":\"" + message + "\"}");
  }

  public static class BodyLimitException extends IOException {
    public BodyLimitException() {
      super("Request body is too large");
    }
  }

  private static class LimitedRequest extends HttpServletRequestWrapper {
    private final int limit;
    private ServletInputStream stream;

    LimitedRequest(HttpServletRequest request, int limit) {
      super(request);
      this.limit = limit;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      if (stream == null) {
        var source = super.getInputStream();
        stream =
            new ServletInputStream() {
              int bytes;

              public int read() throws IOException {
                int b = source.read();
                if (b != -1 && ++bytes > limit) throw new BodyLimitException();
                return b;
              }

              public int read(byte[] b, int offset, int length) throws IOException {
                int n = source.read(b, offset, length);
                if (n > 0 && (bytes += n) > limit) throw new BodyLimitException();
                return n;
              }

              public boolean isFinished() {
                return source.isFinished();
              }

              public boolean isReady() {
                return source.isReady();
              }

              public void setReadListener(ReadListener listener) {
                source.setReadListener(listener);
              }
            };
      }
      return stream;
    }

    @Override
    public BufferedReader getReader() throws IOException {
      return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
  }
}
