/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.http;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.controller.annotation.OnConfigured;
import org.apache.nifi.processor.util.StandardValidators;

public class StandardHttpContextMap extends AbstractControllerService implements HttpContextMap {
    public static final PropertyDescriptor MAX_OUTSTANDING_REQUESTS = new PropertyDescriptor.Builder()
        .name("Maximum Outstanding Requests")
        .description("The maximum number of HTTP requests that can be outstanding at any one time. Any attempt to register an additional HTTP Request will cause an error")
        .required(true)
        .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
        .defaultValue("5000")
        .build();
    
    private final ConcurrentMap<String, Wrapper> wrapperMap = new ConcurrentHashMap<>();
    
    private volatile int maxSize = 5000;
    
    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return Collections.singletonList(MAX_OUTSTANDING_REQUESTS);
    }
    
    @OnConfigured
    public void onConfigured(final ConfigurationContext context) {
        maxSize = context.getProperty(MAX_OUTSTANDING_REQUESTS).asInteger();
    }
    
    @Override
    public void register(final String identifier, final HttpServletRequest request, final HttpServletResponse response, final AsyncContext context) throws TooManyOutstandingRequestsException {
        // TODO: Need to fail if there are too many already. Maybe add a configuration property for how many
        // outstanding, with a default of say 5000
        if ( wrapperMap.size() >= maxSize ) {
            throw new TooManyOutstandingRequestsException();
        }
        final Wrapper wrapper = new Wrapper(request, response, context);
        final Wrapper existing = wrapperMap.putIfAbsent(identifier, wrapper);
        if ( existing != null ) {
            throw new IllegalStateException("HTTP Request already registered with identifier " + identifier);
        }
    }

    @Override
    public HttpServletResponse getResponse(final String identifier) {
        final Wrapper wrapper = wrapperMap.get(identifier);
        if ( wrapper == null ) {
            return null;
        }
        
        return wrapper.getResponse();
    }

    @Override
    public void complete(final String identifier) {
        final Wrapper wrapper = wrapperMap.remove(identifier);
        if ( wrapper == null ) {
            throw new IllegalStateException("No HTTP Request registered with identifier " + identifier);
        }
        
        wrapper.getAsync().complete();
    }

    private static class Wrapper {
        @SuppressWarnings("unused")
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final AsyncContext async;
        
        public Wrapper(final HttpServletRequest request, final HttpServletResponse response, final AsyncContext async) {
            this.request = request;
            this.response = response;
            this.async = async;
        }

        public HttpServletResponse getResponse() {
            return response;
        }

        public AsyncContext getAsync() {
            return async;
        }
        
    }
}
