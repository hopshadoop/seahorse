/**
 * Copyright 2015, deepsense.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.deepsense.workflowexecutor.partialexecution

import io.deepsense.commons.exception.FailureDescription
import io.deepsense.commons.models.Entity
import io.deepsense.deeplang.inference.InferContext
import io.deepsense.graph.Node.Id
import io.deepsense.graph.nodestate.NodeStatus
import io.deepsense.graph.{DirectedGraph, Node, ReadyNode, StatefulGraph, nodestate}

object Execution {
  def empty: IdleExecution = IdleExecution(StatefulGraph(), Set.empty[Node.Id])

  def apply(directedGraph: DirectedGraph, nodes: Seq[Node.Id] = Seq.empty): IdleExecution = {
    val selected: Set[Node.Id] = selectedNodes(directedGraph, nodes)
    empty.updateStructure(directedGraph, selected)
  }

  def selectedNodes(directedGraph: DirectedGraph, nodes: Seq[Id]): Set[Id] = {
    val graphNodeIds = directedGraph.nodes.map(_.id)
    val filteredNodes = nodes.filter(graphNodeIds.contains).toSet
    val selected = if (filteredNodes.isEmpty) graphNodeIds else filteredNodes
    selected
  }
}

sealed trait ExecutionLike {
  type NodeStatuses = Map[Node.Id, NodeStatus]

  def node(id: Node.Id): Node
  def nodeStarted(id: Node.Id): Execution
  def nodeFailed(id: Node.Id, cause: Exception): Execution
  def nodeFinished(id: Node.Id, results: Seq[Entity.Id]): Execution
  def enqueue: Execution
  def readyNodes: Seq[ReadyNode]
  def error: Option[FailureDescription]
  def statuses: NodeStatuses
  def isRunning: Boolean
  def inferAndApplyKnowledge(inferContext: InferContext): Execution
  def updateStructure(directedGraph: DirectedGraph, nodes: Set[Node.Id]): Execution
  def abort: Execution
}

sealed abstract class Execution(graph: StatefulGraph, running: Boolean) extends ExecutionLike {
  override def node(id: Node.Id): Node = graph.node(id)

  override def isRunning: Boolean = running
}

case class IdleExecution(
    graph: StatefulGraph,
    selectedNodes: Set[Node.Id])
  extends Execution(graph, running = false) {

  override def statuses: NodeStatuses = graph.statuses

  override def nodeFinished(id: Node.Id, results: Seq[Entity.Id]): Execution = {
    throw new IllegalStateException("A node cannot finish in IdleExecution")
  }

  override def nodeFailed(id: Id, cause: Exception): Execution = {
    throw new IllegalStateException("A node cannot fail in IdleExecution")
  }


  override def nodeStarted(id: Id): Execution = {
    throw new IllegalStateException("A node cannot start in IdleExecution")
  }

  override def updateStructure(newStructure: DirectedGraph, nodes: Set[Id]): IdleExecution = {
    val selected = Execution.selectedNodes(newStructure, nodes.toSeq)
    val substructure = newStructure.subgraph(selected)
    val newStatus = findStatuses(newStructure, substructure, selected)
    val graph = StatefulGraph(newStructure, newStatus, None)
    IdleExecution(graph, selected)
  }

  override def readyNodes: Seq[ReadyNode] = {
    throw new IllegalStateException("IdleExecution has no read nodes!")
  }

  override def enqueue: Execution = {
    val (selected: Set[Id], subgraph: StatefulGraph) = selectedSubgraph
    val enqueuedSubgraph: StatefulGraph = subgraph.enqueueDraft
    if (enqueuedSubgraph.isRunning) {
      RunningExecution(graph, enqueuedSubgraph, selected)
    } else {
      this
    }
  }

  override def inferAndApplyKnowledge(inferContext: InferContext): IdleExecution = {
    val (_, subgraph: StatefulGraph) = selectedSubgraph
    val inferred = subgraph.inferAndApplyKnowledge(inferContext)
    copy(graph = graph.updateStatuses(inferred))
  }

  override def abort: Execution = {
    throw new IllegalStateException("IdleExecution cannot be aborted!")
  }

  private def selectedSubgraph: (Set[Id], StatefulGraph) = {
    val selected = Execution.selectedNodes(graph.directedGraph, selectedNodes.toSeq)
    val subgraph = graph.subgraph(selected)
    (selected, subgraph)
  }

  private def findStatuses(
      newStructure: DirectedGraph,
      substructure: DirectedGraph,
      nodes: Set[Node.Id]): NodeStatuses = {
    if (newStructure.containsCycle) {
      newStructure.nodes.map(n => n.id -> nodestate.Draft).toMap
    } else {
      val noMissingStatuses = newStructure.nodes.map {
        case Node(id, _) => id -> statuses.getOrElse(id, nodestate.Draft)
      }.toMap

      val wholeGraph = StatefulGraph(newStructure, noMissingStatuses, None)
      val newNodes = newStructure.nodes.diff(graph.directedGraph.nodes).map(_.id)

      val nodesToExecute = substructure.nodes.filter { case Node(id, _) =>
        nodes.contains(id) || !wholeGraph.statuses(id).isCompleted
      }.map(_.id)

      val nodesNeedingDrafting = newNodes ++ nodesToExecute

      nodesNeedingDrafting.foldLeft(wholeGraph) {
        case (g, id) => g.draft(id)
      }.statuses
    }
  }

  override def error: Option[FailureDescription] = graph.executionFailure
  override def isRunning: Boolean = false
}

abstract class StartedExecution(
  graph: StatefulGraph,
  runningPart: StatefulGraph,
  selectedNodes: Set[Node.Id])
  extends Execution(graph, running = true) {

  override def statuses: NodeStatuses = graph.statuses ++ runningPart.statuses

  override def readyNodes: Seq[ReadyNode] = runningPart.readyNodes

  override def nodeFinished(id: Id, results: Seq[Entity.Id]): Execution =
    withRunningPartUpdated(_.nodeFinished(id, results))

  override def nodeFailed(id: Id, cause: Exception): Execution =
    withRunningPartUpdated(_.nodeFailed(id, cause))

  override def error: Option[FailureDescription] = runningPart.executionFailure

  override def enqueue: Execution = {
    throw new IllegalStateException("An Execution that is not idle cannot be enqueued!")
  }

  override def inferAndApplyKnowledge(inferContext: InferContext): RunningExecution = {
    throw new IllegalStateException("An Execution that is not idle cannot infer knowledge!")
  }

  override def updateStructure(directedGraph: DirectedGraph, nodes: Set[Id]): Execution =
    throw new IllegalStateException("Structure of an Execution that is not idle cannot be altered!")

  private def withRunningPartUpdated(update: (StatefulGraph) => StatefulGraph): Execution = {
    val updatedRunningPart = update(runningPart)
    val updatedGraph = graph.updateStatuses(updatedRunningPart)

    if (updatedRunningPart.isRunning) {
      updateState(updatedRunningPart, updatedGraph)
    } else {
      IdleExecution(updatedGraph, selectedNodes)
    }
  }

  protected def updateState(
    updatedRunningPart: StatefulGraph,
    updatedGraph: StatefulGraph): Execution
}

case class RunningExecution(
    graph: StatefulGraph,
    runningPart: StatefulGraph,
    selectedNodes: Set[Node.Id])
  extends StartedExecution(graph, runningPart, selectedNodes) {

  override def nodeStarted(id: Id): RunningExecution = {
    val updatedRunningPart = runningPart.nodeStarted(id)
    val updatedGraph = graph.updateStatuses(updatedRunningPart)
    copy(graph = updatedGraph, runningPart = updatedRunningPart)
  }

  override def abort: AbortedExecution = {
    AbortedExecution(graph, runningPart.abort, selectedNodes)
  }

  override protected def updateState(
    updatedRunningPart: StatefulGraph,
    updatedGraph: StatefulGraph): Execution = {
    RunningExecution(updatedGraph, updatedRunningPart, selectedNodes)
  }
}

case class AbortedExecution(
  graph: StatefulGraph,
  runningPart: StatefulGraph,
  selectedNodes: Set[Node.Id])
  extends StartedExecution(graph, runningPart, selectedNodes) {

  override def nodeStarted(id: Id): AbortedExecution = {
    throw new IllegalStateException("A node cannot be started when execution is Aborted!")
  }

  override def abort: Execution = {
    throw new IllegalStateException("Once aborted execution cannot be aborted again!")
  }

  override protected def updateState(
    updatedRunningPart: StatefulGraph,
    updatedGraph: StatefulGraph): Execution = {
    AbortedExecution(updatedGraph, updatedRunningPart, selectedNodes)
  }
}
