package oap.http.pnio.reactive;

import org.testng.annotations.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ReactiveRequestWorkflowTest {
    @Test
    public void testSkip() {
        var workflow = ReactiveRequestWorkflow
            .init( new TestRequestHandler( "1" ) )
            .next( new TestRequestHandler( "2" ) )
            .next( new TestRequestHandler( "3" ) )
            .next( new TestRequestHandler( "4" ) )
            .build();

        assertThat( workflow.map( ReactiveRequestHandler::toString ) ).isEqualTo( List.of( "1", "2", "3", "4" ) );
        assertThat( workflow.skipBefore( h -> ( ( TestRequestHandler ) h ).id.equals( "2" ) ).map( ReactiveRequestHandler::toString ) )
            .isEqualTo( List.of( "2", "3", "4" ) );
    }

    private static class TestRequestHandler extends ReactiveRequestHandler<Object> {
        public final String id;

        public TestRequestHandler( String id ) {
            super( Type.CPU );
            this.id = id;
        }

        @Override
        public String toString() {
            return id;
        }

        @Override
        public Mono<Void> handle( ReactiveExchange<Object> exchange, Object o ) {
            return Mono.empty();
        }
    }
}

