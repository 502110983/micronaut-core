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

package io.micronaut.http.client.filter

import io.micronaut.context.ApplicationContext
import io.micronaut.http.HttpResponse
import io.micronaut.http.MutableHttpRequest
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.filter.ClientFilterChain
import io.micronaut.http.filter.HttpClientFilter
import io.micronaut.runtime.server.EmbeddedServer
import io.reactivex.Single
import org.reactivestreams.Publisher
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import javax.inject.Singleton

class ClientFilterStereotypeSpec extends Specification {

    @Shared
    @AutoCleanup
    EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer)

    @Shared
    MarkedClient markedClient = embeddedServer.applicationContext.getBean(MarkedClient)

    @Shared
    UnmarkedClient unmarkedClient = embeddedServer.applicationContext.getBean(UnmarkedClient)

    @Shared
    MatchedBean matchedBean = embeddedServer.applicationContext.getBean(MatchedBean)

    @Shared
    UnmatchedBean unmatchedBean = embeddedServer.applicationContext.getBean(UnmatchedBean);

    void "filter should be applied only to annotated declarative clients"() {
        expect:
        markedClient.echo() == "Intercepted"
    }

    void "filter should not be applied because declarative client has no annotation"() {
        expect:
        unmarkedClient.echo() == "echo"
    }

    void "low-level annotated client and injected in constructor intercepted by http filter"() {
        expect:
        matchedBean.httpClient.toBlocking().retrieve('/') == "Intercepted"
    }

    void "low-level not annotated client not intercepted by http filter"() {
        expect:
        unmatchedBean.httpClient.toBlocking().retrieve('/') == "echo"
    }

    @Singleton
    static class MatchedBean {
        HttpClient httpClient

        MatchedBean(@MarkerStereotypeAnnotation @Client('/filters/marked') HttpClient httpClient) {
            this.httpClient = httpClient
        }
    }

    @Singleton
    static class UnmatchedBean {
        HttpClient httpClient

        UnmatchedBean(@Client('/filters/marked') HttpClient httpClient) {
            this.httpClient = httpClient
        }
    }

    @Client("/filters/marked")
    @MarkerStereotypeAnnotation
    static interface MarkedClient {
        @Get("/")
        String echo()
    }

    @Client("/filters/marked")
    static interface UnmarkedClient {
        @Get("/")
        String echo()
    }

    @Controller('/filters/')
    static class UriController {
        @Get('/marked')
        String marked() {
            return "echo"
        }
    }

    @MarkerStereotypeAnnotation
    @Singleton
    static class MarkerFilter implements HttpClientFilter {

        @Override
        Publisher<? extends HttpResponse<?>> doFilter(MutableHttpRequest<?> request, ClientFilterChain chain) {
            return Single.fromPublisher(chain.proceed(request))
                    .map({ response ->
                        HttpResponse.ok("Intercepted")
                    }).toFlowable()
        }
    }

}
