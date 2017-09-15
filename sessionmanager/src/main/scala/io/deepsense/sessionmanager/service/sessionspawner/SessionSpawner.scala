/**
 * Copyright (c) 2016, CodiLime Inc.
 */

package io.deepsense.sessionmanager.service.sessionspawner

import io.deepsense.commons.models.ClusterDetails

trait SessionSpawner {
  def createSession(
    sessionConfig: SessionConfig,
    clusterConfig: ClusterDetails): ExecutorSession
}
