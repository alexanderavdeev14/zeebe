/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.engine.processing.streamprocessor;

import io.zeebe.protocol.impl.record.value.workflowinstance.WorkflowInstanceRecord;
import io.zeebe.protocol.record.ValueType;
import io.zeebe.protocol.record.intent.Intent;
import io.zeebe.protocol.record.intent.JobIntent;
import io.zeebe.protocol.record.intent.MessageStartEventSubscriptionIntent;
import io.zeebe.protocol.record.intent.MessageSubscriptionIntent;
import io.zeebe.protocol.record.value.BpmnElementType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class MigratedStreamProcessors {

  private static final List<BpmnElementType> MIGRATED_BPMN_PROCESSORS = new ArrayList<>();

  private static final Function<TypedRecord<?>, Boolean> NOT_MIGRATED = record -> false;
  private static final Function<TypedRecord<?>, Boolean> MIGRATED = record -> true;

  private static final Map<ValueType, Function<TypedRecord<?>, Boolean>> MIGRATED_VALUE_TYPES =
      new EnumMap<>(ValueType.class);

  private static final Function<List<Intent>, Function<TypedRecord<?>, Boolean>>
      MIGRATED_INTENT_FILTER_FACTORY =
          (intents) -> (record) -> intents.contains(record.getIntent());

  static {
    MIGRATED_VALUE_TYPES.put(
        ValueType.WORKFLOW_INSTANCE,
        record -> {
          final var recordValue = (WorkflowInstanceRecord) record.getValue();
          final var bpmnElementType = recordValue.getBpmnElementType();
          return MIGRATED_BPMN_PROCESSORS.contains(bpmnElementType);
        });
    MIGRATED_VALUE_TYPES.put(
        ValueType.JOB,
        MIGRATED_INTENT_FILTER_FACTORY.apply(
            List.of(
                JobIntent.CREATE,
                JobIntent.CREATED,
                JobIntent.COMPLETE,
                JobIntent.COMPLETED,
                JobIntent.FAIL,
                JobIntent.FAILED,
                JobIntent.THROW_ERROR,
                JobIntent.ERROR_THROWN,
                JobIntent.TIME_OUT,
                JobIntent.TIMED_OUT,
                JobIntent.UPDATE_RETRIES,
                JobIntent.RETRIES_UPDATED,
                JobIntent.CANCEL,
                JobIntent.CANCELED)));
    MIGRATED_BPMN_PROCESSORS.add(BpmnElementType.TESTING_ONLY);
    MIGRATED_BPMN_PROCESSORS.add(BpmnElementType.EXCLUSIVE_GATEWAY);
    MIGRATED_BPMN_PROCESSORS.add(BpmnElementType.PARALLEL_GATEWAY);

    MIGRATED_VALUE_TYPES.put(ValueType.ERROR, MIGRATED);
    MIGRATED_VALUE_TYPES.put(ValueType.WORKFLOW, MIGRATED);
    MIGRATED_VALUE_TYPES.put(ValueType.DEPLOYMENT_DISTRIBUTION, MIGRATED);
    MIGRATED_VALUE_TYPES.put(ValueType.DEPLOYMENT, MIGRATED);
    MIGRATED_VALUE_TYPES.put(ValueType.MESSAGE, MIGRATED);

    MIGRATED_VALUE_TYPES.put(
        ValueType.MESSAGE_SUBSCRIPTION,
        MIGRATED_INTENT_FILTER_FACTORY.apply(
            List.of(
                MessageSubscriptionIntent.CREATE,
                MessageSubscriptionIntent.CREATED,
                MessageSubscriptionIntent.CORRELATING,
                MessageSubscriptionIntent.CORRELATE,
                MessageSubscriptionIntent.CORRELATED)));
    MIGRATED_VALUE_TYPES.put(
        ValueType.MESSAGE_START_EVENT_SUBSCRIPTION,
        record -> record.getIntent() == MessageStartEventSubscriptionIntent.CORRELATED);
  }

  private MigratedStreamProcessors() {}

  public static boolean isMigrated(final TypedRecord<?> record) {
    final var valueType = record.getValueType();
    return MIGRATED_VALUE_TYPES.getOrDefault(valueType, NOT_MIGRATED).apply(record);
  }

  public static boolean isMigrated(final ValueType valueType) {
    return MIGRATED_VALUE_TYPES.get(valueType) == MIGRATED;
  }

  public static boolean isMigrated(final BpmnElementType bpmnElementType) {
    return MIGRATED_BPMN_PROCESSORS.contains(bpmnElementType);
  }
}
