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
package io.micronaut.http.filter;

import io.micronaut.core.util.ArrayUtils;
import io.micronaut.http.HttpMethod;

import javax.annotation.Nonnull;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.StringJoiner;

/**
 * Encapsulates all the possible configurations that might be defined in {@link io.micronaut.http.annotation.Filter} annotation.
 *
 * @author svishnyakoff
 * @since 1.2.4
 */
public final class FilterProperties {

    /**
     * Filter properties without any filter criteria. Filters build with the usage of this instance will be applied to all http clients.
     */
    public static final FilterProperties EMPTY_FILTER_PROPERTIES = new FilterProperties(
            new String[0],
            new HttpMethod[0],
            new String[0],
            new Class[0]
    );

    private final String[] patterns;
    private final HttpMethod[] methods;
    private final String[] serviceId;
    private final Class<? extends Annotation>[] stereotypes;

    /**
     * @param patterns          the patterns this filter should match
     * @param methods           the methods to match
     * @param serviceId         the serviceId to match
     * @param stereotypes the annotation markers to match
     */
    public FilterProperties(@Nonnull String[] patterns,
                            @Nonnull HttpMethod[] methods,
                            @Nonnull String[] serviceId,
                            @Nonnull Class<? extends Annotation>[] stereotypes) {
        this.patterns = patterns;
        this.methods = methods;
        this.serviceId = serviceId;
        this.stereotypes = stereotypes;
    }

    /**
     * @param stereotypes the annotation markers to match
     */
    public FilterProperties(@Nonnull Class<? extends Annotation>[] stereotypes) {
        this.patterns = new String[0];
        this.methods = new HttpMethod[0];
        this.serviceId = new String[0];
        this.stereotypes = stereotypes;
    }

    /**
     * @param properties additional properties you want to include
     * @return new {@link FilterProperties} that contains properties from current and given object.
     */
    @Nonnull
    public FilterProperties merge(FilterProperties properties) {
        return new FilterProperties(
                ArrayUtils.concat(patterns, properties.patterns),
                ArrayUtils.concat(methods, properties.methods),
                ArrayUtils.concat(serviceId, properties.serviceId),
                ArrayUtils.concat(stereotypes, properties.stereotypes)
        );
    }

    /**
     * @return The patterns this filter should match
     */
    @Nonnull
    public String[] getPatterns() {
        return this.patterns;
    }

    /**
     * @return The methods to match. Defaults to all
     */
    @Nonnull
    public HttpMethod[] getMethods() {
        return this.methods;
    }

    /**
     * The service identifiers this filter applies to. Equivalent to the {@code id()} of {@code io.micronaut.http.client.Client}.
     *
     * @return The service identifiers
     */
    @Nonnull
    public String[] getServiceId() {
        return this.serviceId;
    }

    /**
     * If provided, filter will be applied only to {@code io.micronaut.http.client.Client} that are marked
     * with one of provided annotations.
     *
     * All the provided stereotype annotations must be marked with {@link io.micronaut.http.annotation.HttpFilterQualifier}.
     *
     * @return Stereotype annotations
     */
    @Nonnull
    public Class<? extends Annotation>[] getStereotypes() {
        return this.stereotypes;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", FilterProperties.class.getSimpleName() + "[", "]")
                .add("patterns=" + Arrays.toString(patterns))
                .add("methods=" + Arrays.toString(methods))
                .add("serviceId=" + Arrays.toString(serviceId))
                .add("stereotypes=" + Arrays.toString(stereotypes))
                .toString();
    }
}
