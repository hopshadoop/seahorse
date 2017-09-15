'use strict';


// App
import { specialOperations } from 'APP/enums/special-operations.js';

const CSS_CLASSES_MAP = {
  'status_completed': {
    status: 'completed',
    icon: 'fa-check'
  },
  'status_running': {
    status: 'running',
    icon: 'fa-cog fa-spin'
  },
  'status_queued': {
    status: 'queued',
    icon: 'fa-clock-o'
  },
  'status_aborted': {
    status: 'aborted',
    icon: 'fa-exclamation'
  },
  'status_failed': {
    status: 'failed',
    icon: 'fa-ban'
  },
  'error': {
    status: 'failed',
    icon: 'fa-exclamation'
  },
  unknown: {
    status: 'unknown',
    icon: 'fa-question'
  }
};

const actionIcons = {
  [specialOperations.ACTIONS.EVALUATE]: 'sa-evaluate',
  [specialOperations.ACTIONS.TRANSFORM]: 'sa-transform',
  [specialOperations.ACTIONS.FIT]: 'sa-fit',
  [specialOperations.ACTIONS.FIT_AND_TRANSFORM]: 'sa-fit-transform'
};

class GraphNodeController {
  constructor($rootScope, $scope, $element, WorkflowService, UserService, GraphStyleService) {
    'ngInject';

    _.assign(this, {$rootScope, $scope, $element, WorkflowService, UserService, GraphStyleService});

    this.actionIcon = actionIcons[this.node.operationId];
    this.nodeType = this.getNodeType();
    if (this.nodeType === 'unknown') {
      this.statusClasses = this.getCssClasses();
      this.tooltipMessage = this.node.description;
    }

    $scope.$watch(() => this.node.state, (newValue) => {
      if (newValue) {
        this.statusClasses = this.getCssClasses();
      }
    });

    $scope.$watch(() => this.node.knowledgeErrors, (newValue) => {
      if (this.nodeType === 'unknown') {
        return;
      }

      const errors = this.node.getFancyKnowledgeErrors();
      this.tooltipMessage = errors || '';
    });

    this.borderCssClass = this.getBorderColor();
  }

  $postLink() {
    // TODO we don't want to have communication made with $broadcast and events, think about other way
    this.$element.on('click', ($event) => {
      this.$rootScope.$broadcast('GraphNode.CLICK', {
        originalEvent: $event,
        selectedNode: this.node
      });
    });

    this.$element.on('mousedown', ($event) => {
      this.$rootScope.$broadcast('GraphNode.MOUSEDOWN', {
        originalEvent: $event,
        selectedNode: this.node
      });
    });

    this.$element.on('mouseup', ($event) => {
      this.$rootScope.$broadcast('GraphNode.MOUSEUP', {
        originalEvent: $event,
        selectedNode: this.node
      });
    });
  }

  getNodeType() {
    const operationId = this.node.operationId;
    if (Object.values(specialOperations.ACTIONS).includes(operationId)) {
      return 'action';
    } else if (operationId === specialOperations.CUSTOM_TRANSFORMER.SINK ||
      operationId === specialOperations.CUSTOM_TRANSFORMER.SOURCE) {
      return 'source-or-sink';
    } else if (operationId === specialOperations.UNKNOWN_OPERATION) {
      return 'unknown';
    } else {
      return 'standard';
    }
  }

  isOwner() {
    // TODO do wydzielenia do serwisu - sprawdzanie ownera powtarza sie w paru miejsach w aplikacji
    return this.WorkflowService.getCurrentWorkflow().owner.id === this.UserService.getSeahorseUser().id;
  }

  getCssClasses() {
    if (this.node.operationId === specialOperations.UNKNOWN_OPERATION) {
      return CSS_CLASSES_MAP.unknown;
    }

    if (this.node.state.status && this.node.state.status !== 'status_draft') {
      return CSS_CLASSES_MAP[this.node.state.status];
    } else if (this.node.knowledgeErrors.length > 0) {
      return CSS_CLASSES_MAP.error;
    } else {
      return null;
    }
  }

  getBorderColor() {
    let typeQualifier;

    if (this.node.operationId === specialOperations.UNKNOWN_OPERATION) {
      return 'border-unknown';
    }

    if (this.node.input && this.node.input.length === 1) {
      typeQualifier = this.node.input[0].typeQualifier[0];
    } else if (this.node.originalOutput && this.node.originalOutput.length === 1) {
      typeQualifier = this.node.originalOutput[0].typeQualifier[0];
    }
    const type = this.GraphStyleService.getOutputTypeFromQualifier(typeQualifier);

    return `border-${type}`;
  }

}

export default GraphNodeController;
