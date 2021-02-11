/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.engine.state.appliers;

import io.zeebe.engine.state.TypedEventApplier;
import io.zeebe.engine.state.mutable.MutableVariableState;
import io.zeebe.protocol.impl.record.value.variable.VariableRecord;
import io.zeebe.protocol.record.intent.VariableIntent;

final class VariableApplier implements TypedEventApplier<VariableIntent, VariableRecord> {

  private final MutableVariableState variableState;

  public VariableApplier(final MutableVariableState variableState) {
    this.variableState = variableState;
  }

  @Override
  public void applyState(final long key, final VariableRecord value) {
    variableState.setVariableLocal(
        key,
        value.getScopeKey(),
        value.getWorkflowKey(),
        value.getNameBuffer(),
        value.getValueBuffer());
  }
}