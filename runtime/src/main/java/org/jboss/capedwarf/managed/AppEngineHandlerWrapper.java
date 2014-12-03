/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.capedwarf.managed;

import java.io.IOException;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.appengine.api.memcache.MemcacheSerialization;
import com.google.appengine.spi.ServiceFactoryFactory;
import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.runtime.timer.Timer;
import com.google.apphosting.utils.config.AppEngineWebXml;
import com.google.apphosting.vmruntime.CommitDelayingResponseServlet3;
import com.google.apphosting.vmruntime.VmApiProxyDelegate;
import com.google.apphosting.vmruntime.VmApiProxyEnvironment;
import com.google.apphosting.vmruntime.VmMetadataCache;
import com.google.apphosting.vmruntime.VmRuntimeFileLogHandler;
import com.google.apphosting.vmruntime.VmRuntimeLogHandler;
import com.google.apphosting.vmruntime.VmRuntimeUtils;
import com.google.apphosting.vmruntime.VmTimer;
import com.google.apphosting.vmruntime.jetty9.VmRuntimeWebAppContext;
import io.undertow.server.HandlerWrapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 */
public class AppEngineHandlerWrapper implements HandlerWrapper {
    private static final Logger logger = Logger.getLogger(AppEngineHandlerWrapper.class.getName());

    static {
        System.setProperty(ServiceFactoryFactory.USE_THREAD_CONTEXT_CLASSLOADER_PROPERTY, Boolean.TRUE.toString());
        System.setProperty(MemcacheSerialization.USE_THREAD_CONTEXT_CLASSLOADER_PROPERTY, Boolean.TRUE.toString());
    }

    private final AppEngineWebXml appEngineWebXml;

    private final VmMetadataCache metadataCache;
    private final Timer wallclockTimer;
    private VmApiProxyEnvironment defaultEnvironment;

    public AppEngineHandlerWrapper(AppEngineWebXml appEngineWebXml) {
        this.appEngineWebXml = appEngineWebXml;
        // GAE env
        metadataCache = new VmMetadataCache();
        wallclockTimer = new VmTimer();
        ApiProxy.setDelegate(new VmApiProxyDelegate());

        init();
    }

    private void init() {
        try {
            defaultEnvironment = VmApiProxyEnvironment.createDefaultContext(System.getenv(), metadataCache, VmRuntimeUtils.getApiServerAddress(), wallclockTimer, VmRuntimeUtils.ONE_DAY_IN_MILLIS, "/tmp"); // FAKE path
            ApiProxy.setEnvironmentForCurrentThread(defaultEnvironment);
            if (ApiProxy.getEnvironmentFactory() == null) {
                ApiProxy.setEnvironmentFactory(new VmRuntimeWebAppContext.VmEnvironmentFactory(defaultEnvironment));
            }

            VmRuntimeUtils.installSystemProperties(defaultEnvironment, appEngineWebXml);
            VmRuntimeLogHandler.init();
            VmRuntimeFileLogHandler.init();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public HttpHandler wrap(HttpHandler handler) {
        return new AppEngineHttpHandler(handler);
    }

    private class AppEngineHttpHandler implements HttpHandler {
        private final HttpHandler next;

        public AppEngineHttpHandler(HttpHandler next) {
            this.next = next;
        }

        public void handleRequest(HttpServerExchange exchange) throws Exception {
            String target = exchange.getRequestPath();
            ServletRequestContext servletRequestContext = exchange.getAttachment(ServletRequestContext.ATTACHMENT_KEY);
            HttpServletRequest request = (HttpServletRequest) servletRequestContext.getServletRequest();
            HttpServletResponse response = (HttpServletResponse) servletRequestContext.getServletResponse();

            VmApiProxyEnvironment requestSpecificEnvironment = VmApiProxyEnvironment.createFromHeaders(System.getenv(), metadataCache, request, VmRuntimeUtils.getApiServerAddress(), wallclockTimer, VmRuntimeUtils.ONE_DAY_IN_MILLIS, defaultEnvironment);

            CommitDelayingResponseServlet3 wrappedResponse = new CommitDelayingResponseServlet3(response);
            servletRequestContext.setServletResponse(wrappedResponse);
            try {
                ApiProxy.setEnvironmentForCurrentThread(requestSpecificEnvironment);
                VmRuntimeUtils.handleSkipAdminCheck(request);
                //setSchemeAndPort(baseRequest);
                next.handleRequest(exchange);
            } finally {
                try {
                    VmRuntimeUtils.interruptRequestThreads(requestSpecificEnvironment, VmRuntimeUtils.MAX_REQUEST_THREAD_INTERRUPT_WAIT_TIME_MS);
                    if (!VmRuntimeUtils.waitForAsyncApiCalls(requestSpecificEnvironment, wrappedResponse)) {
                        logger.warning("Timed out or interrupted while waiting for async API calls to complete.");
                    }
                    if (!response.isCommitted()) {
                        VmRuntimeUtils.flushLogsAndAddHeader(response, requestSpecificEnvironment);
                    } else {
                        //noinspection ThrowFromFinallyBlock
                        throw new ServletException("Response for request to '" + target + "' was already commited (code=" + response.getStatus() + "). This might result in lost log messages.'");
                    }
                } finally {
                    try {
                        wrappedResponse.commit();
                    } finally {
                        ApiProxy.setEnvironmentForCurrentThread(defaultEnvironment);
                    }
                }
            }
        }
    }
}
