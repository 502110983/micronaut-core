/*
 * Copyright 2017-2019 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.http.server.context;

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.scheduling.instrument.InvocationInstrumenter;
import io.micronaut.scheduling.instrument.InvocationInstrumenterFactory;
import io.micronaut.scheduling.instrument.ReactiveInvocationInstrumenterFactory;

import javax.inject.Singleton;
import java.util.Optional;

/**
 * Instruments Micronaut such that {@link io.micronaut.http.context.ServerRequestContext} state is propagated.
 *
 * @author graemerocher
 * @since 1.0
 */
@Singleton
@Internal
final class ServerRequestContextInstrumentation implements InvocationInstrumenterFactory, ReactiveInvocationInstrumenterFactory {

    @Override
    public Optional<InvocationInstrumenter> newInvocationInstrumenter() {
        return ServerRequestContext.currentRequest().map(invocationRequest -> new InvocationInstrumenter() {

            private boolean inProgress;
            private HttpRequest<Object> currentRequest;
            private boolean isSet = false;

            @Override
            public void beforeInvocation() {
                if (inProgress) {
                    throw new IllegalStateException("Method 'beforeInvocation' called twice");
                }
                inProgress = true;
                currentRequest = ServerRequestContext.currentRequest().orElse(null);
                if (invocationRequest != currentRequest) {
                    isSet = true;
                    ServerRequestContext.set(invocationRequest);
                }
            }

            @Override
            public void afterInvocation() {
                if (!inProgress) {
                    throw new IllegalStateException("Method 'afterInvocation' called without 'beforeInvocation' call");
                }
                if (isSet) {
                    if (invocationRequest != ServerRequestContext.currentRequest().orElse(null)) {
                        throw new IllegalStateException("Request value doesn't match set value");
                    }
                    ServerRequestContext.set(currentRequest);
                    isSet = false;
                }
                inProgress = false;
            }

        });
    }

    @Override
    public Optional<InvocationInstrumenter> newReactiveInvocationInstrumenter() {
        return newInvocationInstrumenter();
    }

}
