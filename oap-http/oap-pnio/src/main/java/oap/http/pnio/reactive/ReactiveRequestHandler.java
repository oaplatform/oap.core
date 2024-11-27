/*
 *
 *  * Copyright (c) Xenoss
 *  * Unauthorized copying of this file, via any medium is strictly prohibited
 *  * Proprietary and confidential
 *
 *
 */

package oap.http.pnio.reactive;

import lombok.Getter;
import reactor.core.publisher.Mono;

@SuppressWarnings( "checkstyle:AbstractClassName" )
public abstract class ReactiveRequestHandler<State> {

    @Getter
    private final Type type;

    public ReactiveRequestHandler( Type type ) {
        this.type = type;
    }

    public String description() {
        return getClass().getName();
    }

    @Override
    public String toString() {
        return description();
    }

    public abstract Mono<Void> handle(ReactiveExchange<State> exchange, State state );

    public enum Type {
        CPU, IO
    }
}
