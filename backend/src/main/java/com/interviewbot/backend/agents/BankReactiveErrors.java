package com.interviewbot.backend.agents;

import io.reactivex.rxjava3.exceptions.UndeliverableException;
import io.reactivex.rxjava3.functions.Consumer;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Route known late cancellation failures to logs; preserve handlers for unexpected failures. */
@Component
public class BankReactiveErrors {
  private static final Logger log = LoggerFactory.getLogger(BankReactiveErrors.class);
  private Consumer<? super Throwable> previous;
  private Consumer<Throwable> handler;

  @PostConstruct
  public void install() {
    previous = RxJavaPlugins.getErrorHandler();
    handler = this::handle;
    RxJavaPlugins.setErrorHandler(handler);
  }

  void handle(Throwable error) throws Throwable {
    if (error instanceof UndeliverableException && (isTimeout(error) || isInterrupted(error))) {
      log.warn(
          "Model request reported a late timeout/interruption after cancellation; the agent"
              + " execution has already terminated");
      return;
    }
    if (previous != null) previous.accept(error);
    else
      Thread.currentThread()
          .getUncaughtExceptionHandler()
          .uncaughtException(Thread.currentThread(), error);
  }

  static boolean isTimeout(Throwable error) {
    for (int depth = 0; error != null && depth < 12; depth++, error = error.getCause())
      if (error instanceof java.util.concurrent.TimeoutException
          || error instanceof java.net.http.HttpTimeoutException
          || error instanceof java.net.SocketTimeoutException
          || error instanceof dev.langchain4j.exception.TimeoutException) return true;
    return false;
  }

  private static boolean isInterrupted(Throwable error) {
    for (int depth = 0; error != null && depth < 12; depth++, error = error.getCause())
      if (error instanceof InterruptedException || error instanceof java.io.InterruptedIOException)
        return true;
    return false;
  }

  @PreDestroy
  public void close() {
    if (RxJavaPlugins.getErrorHandler() == handler) RxJavaPlugins.setErrorHandler(previous);
  }
}
