/**
 * Copyright (c) 2015, CodiLime Inc.
 */

package io.deepsense.workflowmanager

import akka.actor.ActorSystem
import com.google.inject.Guice

import io.deepsense.commons.rest.RestServer
import io.deepsense.deeplang.CatalogRecorder
import io.deepsense.deeplang.catalogs.doperable.DOperableCatalog
import io.deepsense.deeplang.catalogs.doperations.DOperationsCatalog

/**
 * This is the entry point of the Experiment Manager application.
 */
object WorkflowManagerApp extends App {
  val insecure: Boolean = args.headOption.map("insecure".equalsIgnoreCase(_)).getOrElse(true)
  val injector = Guice.createInjector(new WorkflowManagerAppModule(insecure))

  CatalogRecorder.registerDOperables(injector.getInstance(classOf[DOperableCatalog]))
  CatalogRecorder.registerDOperations(injector.getInstance(classOf[DOperationsCatalog]))

  injector.getInstance(classOf[RestServer]).start()
  injector.getInstance(classOf[ActorSystem]).awaitTermination()
  System.exit(1)
}