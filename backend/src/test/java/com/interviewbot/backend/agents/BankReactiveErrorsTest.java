package com.interviewbot.backend.agents;

import static org.assertj.core.api.Assertions.*;

import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BankReactiveErrorsTest {
  @Test
  void lateCancelledTimeoutsAndInterruptionsAreHandledButUnexpectedErrorsAreForwarded() {
    var original = RxJavaPlugins.getErrorHandler();
    List<Throwable> unexpected = new ArrayList<>();
    RxJavaPlugins.setErrorHandler(unexpected::add);
    var handler = new BankReactiveErrors();
    handler.install();
    try {
      RxJavaPlugins.onError(new dev.langchain4j.exception.TimeoutException("late network timeout"));
      RxJavaPlugins.onError(new InterruptedException("cancelled"));
      assertThat(unexpected).isEmpty();
      var bug = new NullPointerException("programming error");
      RxJavaPlugins.onError(bug);
      assertThat(unexpected).containsExactly(bug);
      RxJavaPlugins.onError(new java.io.IOException("unclassified network failure"));
      assertThat(unexpected).hasSize(2);
    } finally {
      handler.close();
      RxJavaPlugins.setErrorHandler(original);
    }
  }

  @Test
  void normalDeliveredTimeoutsStillFailTheirSubscriber() {
    var error = new dev.langchain4j.exception.TimeoutException("request timed out");
    io.reactivex.rxjava3.core.Flowable.error(error).test().assertError(error).assertNoValues();
  }

  @Test
  void handlerRestoresTheExistingApplicationHandlerOnClose() {
    var original = RxJavaPlugins.getErrorHandler();
    io.reactivex.rxjava3.functions.Consumer<Throwable> previous = error -> {};
    RxJavaPlugins.setErrorHandler(previous);
    var handler = new BankReactiveErrors();
    handler.install();
    try {
      handler.close();
      assertThat(RxJavaPlugins.getErrorHandler()).isSameAs(previous);
    } finally {
      RxJavaPlugins.setErrorHandler(original);
    }
  }
}
