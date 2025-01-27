/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.processing.deployment.model.transformer;

import static io.camunda.zeebe.util.buffer.BufferUtil.wrapString;

import io.camunda.zeebe.el.impl.StaticExpression;
import io.camunda.zeebe.engine.Loggers;
import io.camunda.zeebe.engine.processing.deployment.model.element.ExecutableJobWorkerTask;
import io.camunda.zeebe.engine.processing.deployment.model.element.ExecutableProcess;
import io.camunda.zeebe.engine.processing.deployment.model.element.JobWorkerProperties;
import io.camunda.zeebe.engine.processing.deployment.model.transformation.ModelElementTransformer;
import io.camunda.zeebe.engine.processing.deployment.model.transformation.TransformContext;
import io.camunda.zeebe.model.bpmn.instance.UserTask;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeFormDefinition;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeHeader;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskHeaders;
import io.camunda.zeebe.msgpack.spec.MsgPackWriter;
import io.camunda.zeebe.protocol.Protocol;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;

public final class UserTaskTransformer implements ModelElementTransformer<UserTask> {

  private static final Logger LOG = Loggers.STREAM_PROCESSING;

  private static final int INITIAL_SIZE_KEY_VALUE_PAIR = 128;

  private final MsgPackWriter msgPackWriter = new MsgPackWriter();

  @Override
  public Class<UserTask> getType() {
    return UserTask.class;
  }

  @Override
  public void transform(final UserTask element, final TransformContext context) {

    final ExecutableProcess process = context.getCurrentProcess();
    final ExecutableJobWorkerTask userTask =
        process.getElementById(element.getId(), ExecutableJobWorkerTask.class);

    final var jobWorkerProperties = new JobWorkerProperties();
    userTask.setJobWorkerProperties(jobWorkerProperties);

    transformTaskDefinition(jobWorkerProperties);

    transformTaskHeaders(element, jobWorkerProperties);
  }

  private void transformTaskDefinition(final JobWorkerProperties jobWorkerProperties) {
    jobWorkerProperties.setType(new StaticExpression(Protocol.USER_TASK_JOB_TYPE));
    jobWorkerProperties.setRetries(new StaticExpression("1"));
  }

  private void transformTaskHeaders(
      final UserTask element, final JobWorkerProperties jobWorkerProperties) {
    final Map<String, String> taskHeaders = new HashMap<>();

    collectModelTaskHeaders(element, taskHeaders);

    addZeebeUserTaskFormKeyHeader(element, taskHeaders);

    if (!taskHeaders.isEmpty()) {
      final DirectBuffer encodedHeaders = encode(taskHeaders);
      jobWorkerProperties.setEncodedHeaders(encodedHeaders);
    }
  }

  private DirectBuffer encode(final Map<String, String> taskHeaders) {
    final MutableDirectBuffer buffer = new UnsafeBuffer(0, 0);

    final ExpandableArrayBuffer expandableBuffer =
        new ExpandableArrayBuffer(INITIAL_SIZE_KEY_VALUE_PAIR * taskHeaders.size());

    msgPackWriter.wrap(expandableBuffer, 0);
    msgPackWriter.writeMapHeader(taskHeaders.size());

    taskHeaders.forEach(
        (k, v) -> {
          if (isValidHeader(k, v)) {
            final DirectBuffer key = wrapString(k);
            msgPackWriter.writeString(key);

            final DirectBuffer value = wrapString(v);
            msgPackWriter.writeString(value);
          }
        });

    buffer.wrap(expandableBuffer.byteArray(), 0, msgPackWriter.getOffset());

    return buffer;
  }

  private void addZeebeUserTaskFormKeyHeader(
      final UserTask element, final Map<String, String> taskHeaders) {
    final ZeebeFormDefinition formDefinition =
        element.getSingleExtensionElement(ZeebeFormDefinition.class);

    if (formDefinition != null) {
      taskHeaders.put(Protocol.USER_TASK_FORM_KEY_HEADER_NAME, formDefinition.getFormKey());
    }
  }

  private void collectModelTaskHeaders(
      final UserTask element, final Map<String, String> taskHeaders) {
    final ZeebeTaskHeaders modelTaskHeaders =
        element.getSingleExtensionElement(ZeebeTaskHeaders.class);

    if (modelTaskHeaders != null) {
      final List<ZeebeHeader> validHeaders =
          modelTaskHeaders.getHeaders().stream()
              .filter(this::isValidHeader)
              .collect(Collectors.toList());

      if (validHeaders.size() < modelTaskHeaders.getHeaders().size()) {
        LOG.warn(
            "Ignoring invalid headers for task '{}'. Must have non-empty key and value.",
            element.getName());
      }

      validHeaders.forEach(h -> taskHeaders.put(h.getKey(), h.getValue()));
    }
  }

  private boolean isValidHeader(final ZeebeHeader header) {
    return header != null && isValidHeader(header.getKey(), header.getValue());
  }

  private boolean isValidHeader(final String key, final String value) {
    return key != null && !key.isEmpty() && value != null && !value.isEmpty();
  }
}
