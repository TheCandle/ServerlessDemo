/*
 * Copyright 2010 Proofpoint, Inc.
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
package com.facebook.presto.tests;

import com.facebook.airlift.discovery.client.AnnouncementHttpServerInfo;
import com.facebook.airlift.http.server.AuthenticationFilter;
import com.facebook.airlift.http.server.Authenticator;
import com.facebook.airlift.http.server.Authorizer;
import com.facebook.airlift.http.server.HttpServer;
import com.facebook.airlift.http.server.HttpServerBinder.HttpResourceBinding;
import com.facebook.airlift.http.server.HttpServerConfig;
import com.facebook.airlift.http.server.HttpServerInfo;
import com.facebook.airlift.http.server.LocalAnnouncementHttpServerInfo;
import com.facebook.airlift.http.server.TheServlet;
import com.facebook.airlift.http.server.testing.TestingHttpServer;
import com.google.common.collect.ImmutableList;
import com.google.inject.Binder;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;

import java.util.List;
import java.util.Set;

import static com.facebook.airlift.configuration.ConfigBinder.configBinder;
import static com.facebook.airlift.http.server.HttpServerBinder.HttpResourceBinding;
import static com.google.inject.multibindings.MapBinder.newMapBinder;
import static com.google.inject.multibindings.Multibinder.newSetBinder;
import static com.google.inject.multibindings.OptionalBinder.newOptionalBinder;

public class CustomTestingHttpServerModule
        implements Module
{
    private final int httpPort;

    public CustomTestingHttpServerModule()
    {
        this(8060);
    }

    public CustomTestingHttpServerModule(int httpPort)
    {
        this.httpPort = httpPort;
    }

    @Override
    public void configure(Binder binder)
    {
        binder.disableCircularProxies();

        configBinder(binder).bindConfig(HttpServerConfig.class);
        configBinder(binder).bindConfigDefaults(HttpServerConfig.class, config -> {
            config.setHttpPort(httpPort);
            if (httpPort == 0) {
                config.setHttpsPort(0);
            }
        });

        binder.bind(HttpServerInfo.class).in(Scopes.SINGLETON);
        binder.bind(TestingHttpServer.class).in(Scopes.SINGLETON);
        binder.bind(HttpServer.class).to(Key.get(TestingHttpServer.class));
        newMapBinder(binder, String.class, Servlet.class, TheServlet.class);
        newSetBinder(binder, Filter.class, TheServlet.class);
        newSetBinder(binder, HttpResourceBinding.class, TheServlet.class);
        binder.bind(AnnouncementHttpServerInfo.class).to(LocalAnnouncementHttpServerInfo.class);
        newSetBinder(binder, Filter.class, TheServlet.class).addBinding()
                .to(AuthenticationFilter.class).in(Scopes.SINGLETON);
        newSetBinder(binder, Authenticator.class);
        newOptionalBinder(binder, Authorizer.class);
    }

    @Provides
    List<Authenticator> getAuthenticatorList(Set<Authenticator> authenticators)
    {
        return ImmutableList.copyOf(authenticators);
    }
}
