/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.broker.system.partitions.impl;

import static java.lang.String.format;
import static java.util.List.of;
import static java.util.concurrent.CompletableFuture.runAsync;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.framework;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.atomix.raft.RaftServer;
import io.atomix.raft.RaftServer.Role;
import io.camunda.zeebe.broker.system.partitions.PartitionTransitionContext;
import io.camunda.zeebe.broker.system.partitions.PartitionTransitionStep;
import io.camunda.zeebe.broker.system.partitions.impl.steps.StreamProcessorTransitionStep;
import io.camunda.zeebe.engine.processing.streamprocessor.StreamProcessor;
import io.camunda.zeebe.engine.processing.streamprocessor.StreamProcessorBuilder;
import io.camunda.zeebe.util.health.HealthMonitor;
import io.camunda.zeebe.util.sched.Actor;
import io.camunda.zeebe.util.sched.ActorScheduler;
import io.camunda.zeebe.util.sched.future.ActorFuture;
import io.camunda.zeebe.util.sched.future.CompletableActorFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RandomizedPartitionTransitionTest {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(RandomizedPartitionTransitionTest.class);

  @Property(generation = GenerationMode.EXHAUSTIVE)
  void atMostOneStreamProcessorIsRunningAtAnyTime(
      @ForAll("testOperations") final List<TestOperation> operations) {
    LOGGER.debug(
        format("Testing property 'atMostOneStreamProcessorIsRunningAtAnyTime'  %s", operations));

    final var actorScheduler = ActorScheduler.newActorScheduler().build();
    actorScheduler.start();
    try {
      final var actor = new Actor() {};
      actorScheduler.submitActor(actor);

      final var instanceTracker =
          new PropertyAssertingInstanceTracker<StreamProcessor>() {
            @Override
            void assertProperties() {
              assertThat(opened).describedAs("Active stream processors").hasSizeLessThan(2);
            }
          };

      final var mockStreamProcessorBuilder = produceMockStreamProcessorBuilder(instanceTracker);

      final var firstStep = new PausableStep(operations);
      final var streamProcessorStep =
          new StreamProcessorTransitionStep(() -> mockStreamProcessorBuilder);

      final var mockContext = mock(PartitionTransitionContext.class);
      when(mockContext.getComponentHealthMonitor()).thenReturn(mock(HealthMonitor.class));

      // a shorthand for "let the getter return the last value that was passed to the setter"
      doAnswer(
              answer ->
                  when(mockContext.getStreamProcessor())
                      .thenReturn((StreamProcessor) answer.getArguments()[0]))
          .when(mockContext)
          .setStreamProcessor(any());

      final var sut =
          new NewPartitionTransitionImpl(of(firstStep, streamProcessorStep), mockContext);
      sut.setConcurrencyControl(actor);

      final var pausedSteps = new ArrayList<CountDownLatch>();
      ActorFuture<Void> latestTransitionFuture = null;

      for (int index = 0; index < operations.size(); index++) {
        final var operation = operations.get(index);

        if (operation instanceof RequestTransition) {
          final var requestTransition = (RequestTransition) operation;

          latestTransitionFuture = sut.transitionTo(index, requestTransition.role);
          if (requestTransition.isPause()) {
            pausedSteps.add(requestTransition.getCountDownLatch());
          } else {
            requestTransition.getCountDownLatch().countDown();
          }
        } else { // catch up operation
          catchUp(latestTransitionFuture, pausedSteps);
        }
      }

      assertThat(instanceTracker.getOpenedInstances())
          .describedAs("Active stream processors at end of transition sequence")
          .hasSizeLessThan(2);
    } finally {
      actorScheduler.stop();
      framework()
          .clearInlineMocks(); // prevent memory leaks from statically held mocks and stubbings
    }
  }

  private void catchUp(
      final ActorFuture<Void> latestTransitionFuture, final ArrayList<CountDownLatch> pausedSteps) {
    if (latestTransitionFuture == null) {
      return;
    }
    while (!latestTransitionFuture.isDone()) {
      final var stepsToResume = new ArrayList<>(pausedSteps);
      pausedSteps.clear();
      stepsToResume.forEach(CountDownLatch::countDown);
    }
  }

  @Provide
  Arbitrary<List<TestOperation>> testOperations() {
    final var kind = Arbitraries.of(TestOperationKind.class);
    final var role = Arbitraries.of(RaftServer.Role.class);

    final var operation = Combinators.combine(kind, role).as(this::createTestOperation);

    return operation
        .list()
        .ofMaxSize(4)
        .filter(list -> list.stream().anyMatch(RequestTransition.class::isInstance))
        .map(
            list -> {
              list.add(new CatchUpOperation());
              return list;
            });
  }

  private TestOperation createTestOperation(
      final TestOperationKind kind, final RaftServer.Role role) {
    switch (kind) {
      case TRANSITION_TO_ROLE_NO_PAUSE:
        return new RequestTransition(role, false);
      case TRANSITION_TO_RULE_PAUSED:
        return new RequestTransition(role, true);
      case CATCH_UP:
      default:
        return new CatchUpOperation();
    }
  }

  private StreamProcessorBuilder produceMockStreamProcessorBuilder(
      final PropertyAssertingInstanceTracker<StreamProcessor> instanceTracker) {
    final var mockStreamProcessorBuilder = mock(StreamProcessorBuilder.class);
    when(mockStreamProcessorBuilder.logStream(any())).thenReturn(mockStreamProcessorBuilder);
    when(mockStreamProcessorBuilder.actorSchedulingService(any()))
        .thenReturn(mockStreamProcessorBuilder);
    when(mockStreamProcessorBuilder.zeebeDb(any())).thenReturn(mockStreamProcessorBuilder);
    when(mockStreamProcessorBuilder.eventApplierFactory(any()))
        .thenReturn(mockStreamProcessorBuilder);
    when(mockStreamProcessorBuilder.nodeId(anyInt())).thenReturn(mockStreamProcessorBuilder);
    when(mockStreamProcessorBuilder.commandResponseWriter(any()))
        .thenReturn(mockStreamProcessorBuilder);
    when(mockStreamProcessorBuilder.listener(any())).thenReturn(mockStreamProcessorBuilder);
    when(mockStreamProcessorBuilder.streamProcessorFactory(any()))
        .thenReturn(mockStreamProcessorBuilder);
    when(mockStreamProcessorBuilder.streamProcessorMode(any()))
        .thenReturn(mockStreamProcessorBuilder);

    when(mockStreamProcessorBuilder.build())
        .thenAnswer((invocation) -> produceMockStreamProcessor(instanceTracker));
    return mockStreamProcessorBuilder;
  }

  private StreamProcessor produceMockStreamProcessor(
      final PropertyAssertingInstanceTracker<StreamProcessor> instanceTracker) {
    final var mockStreamProcessor = mock(StreamProcessor.class);

    instanceTracker.registerCreation(mockStreamProcessor);

    when(mockStreamProcessor.openAsync(anyBoolean()))
        .thenAnswer(
            invocation -> {
              instanceTracker.registerOpen(mockStreamProcessor);
              return CompletableActorFuture.completed(null);
            });

    when(mockStreamProcessor.closeAsync())
        .thenAnswer(
            invocation -> {
              instanceTracker.registerClose(mockStreamProcessor);
              return CompletableActorFuture.completed(null);
            });

    return mockStreamProcessor;
  }

  private static final class RequestTransition implements TestOperation {
    final RaftServer.Role role;
    final boolean pause;
    final CountDownLatch countDownLatch = new CountDownLatch(1);

    private RequestTransition(final RaftServer.Role role, final boolean pause) {
      this.role = role;
      this.pause = pause;
    }

    Role getRole() {
      return role;
    }

    boolean isPause() {
      return pause;
    }

    CountDownLatch getCountDownLatch() {
      return countDownLatch;
    }

    @Override
    public int hashCode() {
      int result = role.hashCode();
      result = 31 * result + (pause ? 1 : 0);
      return result;
    }

    @Override
    public boolean equals(final Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      final RequestTransition that = (RequestTransition) o;

      if (pause != that.pause) {
        return false;
      }
      return role == that.role;
    }

    @Override
    public String toString() {
      return "RequestTransition{" + "role=" + role + ", pause=" + pause + '}';
    }
  }

  private static final class CatchUpOperation implements TestOperation {
    @Override
    public int hashCode() {
      return 1;
    }

    @Override
    public boolean equals(final Object o) {
      if (this == o) {
        return true;
      }
      return o != null && getClass() == o.getClass();
    }

    @Override
    public String toString() {
      return "Catch Up";
    }
  }

  private abstract static class PropertyAssertingInstanceTracker<T> {
    final List<T> created = new ArrayList<>();
    final List<T> opened = new ArrayList<>();
    final List<T> closed = new ArrayList<>();

    abstract void assertProperties();

    void registerCreation(final T instance) {
      created.add(instance);
      assertProperties();
    }

    void registerOpen(final T instance) {
      created.remove(instance);
      opened.add(instance);
      assertProperties();
    }

    void registerClose(final T instance) {
      opened.remove(instance);
      closed.add(instance);
      assertProperties();
    }

    List<T> getOpenedInstances() {
      return opened;
    }
  }

  private static final class PausableStep implements PartitionTransitionStep {

    final List<TestOperation> operations;

    public PausableStep(final List<TestOperation> operations) {
      this.operations = operations;
    }

    @Override
    public ActorFuture<Void> prepareTransition(
        final PartitionTransitionContext context, final long term, final Role targetRole) {
      return CompletableActorFuture.completed(null);
    }

    @Override
    public ActorFuture<Void> transitionTo(
        final PartitionTransitionContext context, final long term, final Role targetRole) {

      final var testOperation = operations.get(Long.valueOf(term).intValue());

      final var requestedTransition = (RequestTransition) testOperation;

      final var countdownLatch = requestedTransition.getCountDownLatch();

      final var transitionFuture = new CompletableActorFuture<Void>();
      runAsync(
              () -> {
                try {
                  countdownLatch.await();
                } catch (final InterruptedException e) {
                  LOGGER.error(e.getMessage(), e);
                }
              })
          .whenComplete(
              (ok, error) -> {
                if (error != null) {
                  transitionFuture.completeExceptionally(error);
                } else {
                  transitionFuture.complete(null);
                }
              });

      return transitionFuture;
    }

    @Override
    public String getName() {
      return getClass().getSimpleName();
    }
  }

  private interface TestOperation {}

  private enum TestOperationKind {
    /**
     * Request a transition to a certain role. Do not pause on the first step of that transition.
     */
    TRANSITION_TO_ROLE_NO_PAUSE,
    /**
     * Request a transition to a certain role. Do pause on the first step of that transition. (this
     * is to allow the transition to be cancelled by successor steps)
     */
    TRANSITION_TO_RULE_PAUSED,
    /** Resume all paused steps and run all scheduled transitions to their respective end */
    CATCH_UP
  }
}
