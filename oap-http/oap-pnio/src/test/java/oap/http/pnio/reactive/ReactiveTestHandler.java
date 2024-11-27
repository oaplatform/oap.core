package oap.http.pnio.reactive;

import oap.http.pnio.TestState;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

public class ReactiveTestHandler extends ReactiveRequestHandler<TestState> {

    private final String name;

    public RuntimeException runtimeException;
    public long sleepTime = -1;

    public ReactiveTestHandler( String name, ReactiveRequestHandler.Type type ) {
        super(type);
        this.name = name;
    }

    @Override
    public Mono<Void> handle(ReactiveExchange<TestState> exchange, TestState testState) {
        return Mono.defer(() -> {
            if (runtimeException != null) {
                return Mono.error(new RuntimeException(runtimeException));
            }

            Mono<Void> resultMono = Mono.empty(); // Start with an empty Mono

            if (sleepTime > 0) {
                System.out.println("Setting Sleep time to " + sleepTime);
                resultMono = Mono.delay( Duration.ofMillis(sleepTime)).then();
            }

            return resultMono.then(Mono.fromRunnable(() -> {
                if (!testState.sb.isEmpty()) {
                    testState.sb.append("\n");
                }

                String data = "name '" + name +
                    " thread '" + Thread.currentThread().getName().substring(0, 2) +
                    "' new thread " + !testState.oldThreadName.equals(Thread.currentThread().getName());

                testState.sb.append(data);
                testState.oldThreadName = Thread.currentThread().getName();
            }));
        });
    }

    @Override
    public String description() {
        return "name '" + name + " thread '" + Thread.currentThread().getName() + "'";
    }

    public ReactiveTestHandler withException( RuntimeException testException ) {
        this.runtimeException = testException;
        return this;
    }

    public ReactiveTestHandler withSleepTime( long duration ) {
        this.sleepTime = duration;
        return this;
    }
}
