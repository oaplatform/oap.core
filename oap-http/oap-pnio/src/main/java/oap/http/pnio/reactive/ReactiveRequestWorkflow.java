package oap.http.pnio.reactive;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import lombok.AllArgsConstructor;
import lombok.Getter;
import reactor.core.publisher.Mono;

@Getter
public class ReactiveRequestWorkflow<WorkflowState> {
    private Node<WorkflowState> root;

    private ReactiveRequestWorkflow( Node<WorkflowState> root ) {
        this.root = root;
    }

    public ReactiveRequestWorkflow<WorkflowState> skipBefore(Predicate<ReactiveRequestHandler<WorkflowState>> predicate ) {
        var current = root;
        while( current != null ) {
            if( predicate.test( current.handler ) ) {
                return new ReactiveRequestWorkflow<>( current );
            }
            current = current.next;
        }
        return new ReactiveRequestWorkflow<>( null );
    }

    public <T> List<T> map(Function<ReactiveRequestHandler<WorkflowState>, T> mapFunc ) {
        var ret = new ArrayList<T>();
        var current = root;
        while( current != null ) {
            ret.add( mapFunc.apply( current.handler ) );
            current = current.next;
        }
        return ret;
    }

    public static <WorkflowState> ReactiveRequestWorkflowBuilder<WorkflowState> init(ReactiveRequestHandler<WorkflowState> task ) {
        ReactiveRequestWorkflow<WorkflowState> workflow = new ReactiveRequestWorkflow<>( new Node<>(task, null ) );
        return new ReactiveRequestWorkflowBuilder<>(workflow);
    }

    public void update( ReactiveRequestWorkflow<WorkflowState> newWorkflow ) {
        root = newWorkflow.root;
    }

    @AllArgsConstructor
    static class Node<WorkflowState> {
        final ReactiveRequestHandler<WorkflowState> handler;
        Node<WorkflowState> next;
    }

    public static class ReactiveRequestWorkflowBuilder<WorkflowState> {
        private final ReactiveRequestWorkflow<WorkflowState> workflow;
        private Node<WorkflowState> lastNode;

        public ReactiveRequestWorkflowBuilder(ReactiveRequestWorkflow<WorkflowState> workflow ) {
            this.workflow = workflow;
            lastNode = workflow.root;
        }

        public ReactiveRequestWorkflowBuilder<WorkflowState> next(ReactiveRequestHandler<WorkflowState> handlers ) {
            var node = new Node<>(handlers, null );
            lastNode.next = node;
            lastNode = node;

            return this;
        }

        public <T> ReactiveRequestWorkflowBuilder<WorkflowState> next(List<T> list, Function<T, ReactiveRequestHandler<WorkflowState>> next ) {
            return next( list, next, null );
        }

        public <T> ReactiveRequestWorkflowBuilder<WorkflowState> next(List<T> list, Function<T, ReactiveRequestHandler<WorkflowState>> next, BiFunction<ReactiveExchange<WorkflowState>, WorkflowState, Mono<Void>> postProcess ) {
            for( var item : list ) {
                next( next.apply( item ) );
            }

            if( postProcess != null ) {
                next( new ReactiveRequestHandler<>( ReactiveRequestHandler.Type.CPU) {
                    @Override
                    public Mono<Void> handle(ReactiveExchange<WorkflowState> exchange, WorkflowState workflowState ) {
                        return postProcess.apply( exchange, workflowState );
                    }
                } );
            }

            return this;
        }

        public ReactiveRequestWorkflow<WorkflowState> build() {
            return workflow;
        }
    }

    @Override
    public String toString() {
        StringBuilder workflowSteps = new StringBuilder();
        Node<WorkflowState> currentNode = root;

        while (currentNode != null) {
            workflowSteps.append(currentNode.handler.getClass().getSimpleName()).append("->");
            currentNode = currentNode.next;
        }

        if (!workflowSteps.isEmpty()) {
            workflowSteps.setLength(workflowSteps.length() - 2);
        }

        return workflowSteps.toString();
    }


}
