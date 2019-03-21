/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.felix.http.jetty.internal;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletContext;
import javax.servlet.SessionCookieConfig;
import javax.servlet.SessionTrackingMode;

import org.apache.felix.http.base.internal.HttpServiceController;
import org.apache.felix.http.base.internal.logger.SystemLogger;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ConnectionStatistics;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.UserStore;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.server.session.HouseKeeper;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.http.runtime.HttpServiceRuntimeConstants;
import org.osgi.util.tracker.BundleTracker;
import org.osgi.util.tracker.BundleTrackerCustomizer;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

public final class JettyService extends AbstractLifeCycle.AbstractLifeCycleListener
{
    /** PID for configuration of the HTTP service. */
    public static final String PID = "org.apache.felix.http";

    private static final String HEADER_WEB_CONTEXT_PATH = "Web-ContextPath";
    private static final String HEADER_ACTIVATION_POLICY = "Bundle-ActivationPolicy";
    private static final String WEB_SYMBOLIC_NAME = "osgi.web.symbolicname";
    private static final String WEB_VERSION = "osgi.web.version";
    private static final String WEB_CONTEXT_PATH = "osgi.web.contextpath";
    private static final String OSGI_BUNDLE_CONTEXT = "osgi-bundlecontext";

    private final JettyConfig config;
    private final BundleContext context;
    private final HttpServiceController controller;
    private final Map<String, Deployment> deployments;
    private final ExecutorService executor;

    private volatile ServiceRegistration<?> configServiceReg;
    private volatile Server server;
    private volatile ContextHandlerCollection parent;
    private volatile MBeanServerTracker mbeanServerTracker;
    private volatile BundleTracker<Deployment> bundleTracker;
    private volatile ServiceTracker<Object, Object> eventAdmintTracker;
    private volatile ConnectorFactoryTracker connectorTracker;
    private volatile RequestLogTracker requestLogTracker;
    private volatile LogServiceRequestLog osgiRequestLog;
    private volatile FileRequestLog fileRequestLog;
    private volatile LoadBalancerCustomizerFactoryTracker loadBalancerCustomizerTracker;
    private volatile CustomizerWrapper customizerWrapper;
    private boolean registerManagedService = true;
    private volatile Object eventAdmin;
    private final String jettyVersion;

    public JettyService(final BundleContext context,
            final HttpServiceController controller)
    {
        this.jettyVersion = fixJettyVersion(context);

        this.context = context;
        this.config = new JettyConfig(this.context);
        this.controller = controller;
        this.deployments = new LinkedHashMap<>();
        this.executor = Executors.newSingleThreadExecutor(new ThreadFactory()
        {
            @Override
            public Thread newThread(Runnable runnable)
            {
                Thread t = new Thread(runnable);
                t.setName("Jetty HTTP Service");
                return t;
            }
        });
    }

    public JettyService(final BundleContext context,
            final HttpServiceController controller,
            final Dictionary<String,?> props)
    {
        this(context, controller);
   	    this.config.update(props);
   	    this.registerManagedService = false;
    }

    public void start() throws Exception
    {
        // FELIX-4422: start Jetty synchronously...
        startJetty();

        if (this.registerManagedService) {
			final Dictionary<String, Object> props = new Hashtable<>();
			props.put(Constants.SERVICE_PID, PID);
	        props.put(Constants.SERVICE_VENDOR, "The Apache Software Foundation");
	        props.put(Constants.SERVICE_DESCRIPTION, "Managed Service for the Jetty Http Service");
			this.configServiceReg = this.context.registerService("org.osgi.service.cm.ManagedService",
	                new ServiceFactory()
	                {

	                    @Override
	                    public Object getService(final Bundle bundle, final ServiceRegistration registration)
	                    {
	                        return new JettyManagedService(JettyService.this);
	                    }

	                    @Override
	                    public void ungetService(Bundle bundle, ServiceRegistration registration, Object service)
	                    {
	                        // nothing to do
	                    }
	                }, props);
        }

        // we use the class name as a String to make the dependency on event admin optional
        this.eventAdmintTracker = new ServiceTracker<>(this.context, "org.osgi.service.event.EventAdmin",
                new ServiceTrackerCustomizer<Object, Object>()
        {
            @Override
            public Object addingService(final ServiceReference<Object> reference)
            {
                final Object service = context.getService(reference);
                eventAdmin = service;
                return service;
            }

            @Override
            public void modifiedService(final ServiceReference<Object> reference, final Object service)
            {
                // nothing to do
            }

            @Override
            public void removedService(final ServiceReference<Object> reference, final Object service)
            {
                eventAdmin = null;
                context.ungetService(reference);
            }
        });
        this.eventAdmintTracker.open();

        this.bundleTracker = new BundleTracker<>(this.context, Bundle.ACTIVE | Bundle.STARTING,
                new BundleTrackerCustomizer<Deployment>() {

            @Override
            public Deployment addingBundle(Bundle bundle, BundleEvent event)
            {
                return detectWebAppBundle(bundle);
            }

            @Override
            public void modifiedBundle(Bundle bundle, BundleEvent event, Deployment object)
            {
                detectWebAppBundle(bundle);
            }

            private Deployment detectWebAppBundle(Bundle bundle)
            {
                if (bundle.getState() == Bundle.ACTIVE || (bundle.getState() == Bundle.STARTING && "Lazy".equals(bundle.getHeaders().get(HEADER_ACTIVATION_POLICY))))
                {

                    String contextPath = bundle.getHeaders().get(HEADER_WEB_CONTEXT_PATH);
                    if (contextPath != null)
                    {
                        return startWebAppBundle(bundle, contextPath);
                    }
                }
                return null;
            }

            @Override
            public void removedBundle(Bundle bundle, BundleEvent event, Deployment object)
            {
                String contextPath = bundle.getHeaders().get(HEADER_WEB_CONTEXT_PATH);
                if (contextPath == null)
                {
                    return;
                }

                Deployment deployment = deployments.remove(contextPath);
                if (deployment != null && deployment.getContext() != null)
                {
                    // remove registration, since bundle is already stopping
                    deployment.setRegistration(null);
                    undeploy(deployment, deployment.getContext());
                }
            }

                });
        this.bundleTracker.open();
    }

    public void stop() throws Exception
    {
        if (this.configServiceReg != null)
        {
            this.configServiceReg.unregister();
            this.configServiceReg = null;
        }
        if (this.bundleTracker != null)
        {
            this.bundleTracker.close();
            this.bundleTracker = null;
        }
        if (this.eventAdmintTracker != null)
        {
            this.eventAdmintTracker.close();
            this.eventAdmintTracker = null;
        }

        // FELIX-4422: stop Jetty synchronously...
        stopJetty();

        if (isExecutorServiceAvailable())
        {
            this.executor.shutdown();
            // FELIX-4423: make sure to await the termination of the executor...
            this.executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private Hashtable<String, Object> getServiceProperties()
    {
        Hashtable<String, Object> props = new Hashtable<>();
        // Add some important configuration properties...
        this.config.setServiceProperties(props);
        addEndpointProperties(props, null);

        // propagate the new service properties to the actual HTTP service...
        return props;
    }

    public void updated(final Dictionary<String, ?> props)
    {
        if (this.config.update(props))
        {
            // Something changed in our configuration, restart Jetty...
            stopJetty();
            startJetty();
        }
    }

    private void startJetty()
    {
        try
        {
            initializeJetty();
        }
        catch (Exception e)
        {
            SystemLogger.error("Exception while initializing Jetty.", e);
        }
    }

    private void stopJetty()
    {
        if (this.server != null)
        {
            this.controller.getEventDispatcher().setActive(false);
            this.controller.unregister();

            if (this.fileRequestLog != null)
            {
                this.fileRequestLog.stop();
                this.fileRequestLog = null;
            }
            if (this.osgiRequestLog != null)
            {
                this.osgiRequestLog.unregister();
                this.osgiRequestLog = null;
            }
            if (this.requestLogTracker != null)
            {
                this.requestLogTracker.close();
                this.requestLogTracker = null;
            }

            if (this.connectorTracker != null)
            {
                this.connectorTracker.close();
                this.connectorTracker = null;
            }

            if (this.loadBalancerCustomizerTracker != null)
            {
                this.loadBalancerCustomizerTracker.close();
                this.loadBalancerCustomizerTracker = null;
            }

            try
            {
                this.server.stop();
                this.server = null;
                SystemLogger.info("Stopped Jetty.");
            }
            catch (Exception e)
            {
                SystemLogger.error("Exception while stopping Jetty.", e);
            }

            if (this.mbeanServerTracker != null)
            {
                this.mbeanServerTracker.close();
                this.mbeanServerTracker = null;
            }
        }
    }

    private void initializeJetty() throws Exception
    {
        if (this.config.isUseHttp() || this.config.isUseHttps())
        {

            final int threadPoolMax = this.config.getThreadPoolMax();
            if (threadPoolMax >= 0) {
                this.server = new Server( new QueuedThreadPool(threadPoolMax) );
            } else {
                this.server = new Server();
            }
            this.server.addLifeCycleListener(this);

            // FELIX-5931 : PropertyUserStore used as default by HashLoginService has changed in 9.4.12.v20180830
            //              and fails without a config, therefore using plain UserStore
            final HashLoginService loginService = new HashLoginService("OSGi HTTP Service Realm");
            loginService.setUserStore(new UserStore());
            this.server.addBean(loginService);

            this.parent = new ContextHandlerCollection();

            ServletContextHandler context = new ServletContextHandler(this.parent,
                    this.config.getContextPath(),
                    ServletContextHandler.SESSIONS);

            configureSessionManager(context);
            this.controller.getEventDispatcher().setActive(true);
            context.addEventListener(controller.getEventDispatcher());
            context.getSessionHandler().addEventListener(controller.getEventDispatcher());

            final ServletHolder holder = new ServletHolder(this.controller.createDispatcherServlet());
            holder.setAsyncSupported(true);
            context.addServlet(holder, "/*");
            context.setMaxFormContentSize(this.config.getMaxFormSize());

            if (this.config.isRegisterMBeans())
            {
                this.mbeanServerTracker = new MBeanServerTracker(this.context, this.server);
                this.mbeanServerTracker.open();
                if (!this.config.isStatisticsHandlerEnabled()) {
                  context.addBean(new StatisticsHandler());
                }
            }

            this.server.setHandler(this.parent);

            if (this.config.isStatisticsHandlerEnabled()) {
              StatisticsHandler statisticsHandler = new StatisticsHandler();
              this.server.insertHandler(statisticsHandler);
              if (this.config.isRegisterMBeans())
              {
                context.addBean(statisticsHandler);
              }
            }

            if (this.config.isGzipHandlerEnabled())
            {
            	GzipHandler gzipHandler = new GzipHandler();
            	gzipHandler.setMinGzipSize(this.config.getGzipMinGzipSize());
            	gzipHandler.setCompressionLevel(this.config.getGzipCompressionLevel());
            	gzipHandler.setInflateBufferSize(this.config.getGzipInflateBufferSize());
            	gzipHandler.setSyncFlush(this.config.isGzipSyncFlush());
            	gzipHandler.addExcludedAgentPatterns(this.config.getGzipExcludedUserAgent());
            	gzipHandler.addIncludedMethods(this.config.getGzipIncludedMethods());
            	gzipHandler.addExcludedMethods(this.config.getGzipExcludedMethods());
            	gzipHandler.addIncludedPaths(this.config.getGzipIncludedPaths());
            	gzipHandler.addExcludedPaths(this.config.getGzipExcludedPaths());
            	gzipHandler.addIncludedMimeTypes(this.config.getGzipIncludedMimeTypes());
            	gzipHandler.addExcludedMimeTypes(this.config.getGzipExcludedMimeTypes());

            	this.server.insertHandler(gzipHandler);
            }

            this.server.start();

            // session id manager is only available after server is started
            context.getSessionHandler().getSessionIdManager().getSessionHouseKeeper().setIntervalSec(
                    this.config.getLongProperty(JettyConfig.FELIX_JETTY_SESSION_SCAVENGING_INTERVAL,
                            HouseKeeper.DEFAULT_PERIOD_MS / 1000L));

            if (this.config.isProxyLoadBalancerConnection())
            {
                customizerWrapper = new CustomizerWrapper();
                this.loadBalancerCustomizerTracker = new LoadBalancerCustomizerFactoryTracker(this.context, customizerWrapper);
                this.loadBalancerCustomizerTracker.open();
            }

            final StringBuilder message = new StringBuilder("Started Jetty ").append(this.jettyVersion).append(" at port(s)");
            if (this.config.isUseHttp() && initializeHttp())
            {
                message.append(" HTTP:").append(this.config.getHttpPort());
            }

            if (this.config.isUseHttps() && initializeHttps())
            {
                message.append(" HTTPS:").append(this.config.getHttpsPort());
            }

            this.connectorTracker = new ConnectorFactoryTracker(this.context, this.server);
            this.connectorTracker.open();

            if (this.server.getConnectors() != null && this.server.getConnectors().length > 0)
            {
                message.append(" on context path ").append(this.config.getContextPath());

                message.append(" [");
                ThreadPool threadPool = this.server.getThreadPool();
                if (threadPool instanceof ThreadPool.SizedThreadPool) {
                    ThreadPool.SizedThreadPool sizedThreadPool = (ThreadPool.SizedThreadPool) threadPool;
                    message.append("minThreads=").append(sizedThreadPool.getMinThreads()).append(",");
                    message.append("maxThreads=").append(sizedThreadPool.getMaxThreads()).append(",");
                }
                Connector connector = this.server.getConnectors()[0];
                if (connector instanceof ServerConnector) {
                    @SuppressWarnings("resource")
                    ServerConnector serverConnector = (ServerConnector) connector;
                    message.append("acceptors=").append(serverConnector.getAcceptors()).append(",");
                    message.append("selectors=").append(serverConnector.getSelectorManager().getSelectorCount());
                }
                message.append("]");

                SystemLogger.info(message.toString());
                this.controller.register(context.getServletContext(), getServiceProperties());
            }
            else
            {
                this.stopJetty();
                SystemLogger.error("Jetty stopped (no connectors available)", null);
            }

            try {
                this.requestLogTracker = new RequestLogTracker(this.context, this.config.getRequestLogFilter());
                this.requestLogTracker.open();
                this.server.setRequestLog(requestLogTracker);
            } catch (InvalidSyntaxException e) {
                SystemLogger.error("Invalid filter syntax in request log tracker", e);
            }

            if (this.config.isRequestLogOSGiEnabled()) {
                this.osgiRequestLog = new LogServiceRequestLog(this.config);
                this.osgiRequestLog.register(this.context);
                SystemLogger.info("Directing Jetty request logs to the OSGi Log Service");
            }

            if (this.config.getRequestLogFilePath() != null && !this.config.getRequestLogFilePath().isEmpty()) {
                this.fileRequestLog = new FileRequestLog(config);
                this.fileRequestLog.start(this.context);
                SystemLogger.info("Directing Jetty request logs to " + this.config.getRequestLogFilePath());
            }
        }
        else
        {
            SystemLogger.warning("Jetty not started (HTTP and HTTPS disabled)", null);
        }
    }

    private static String fixJettyVersion(final BundleContext ctx)
    {
        // FELIX-4311: report the real version of Jetty...
        final Dictionary<String, String> headers = ctx.getBundle().getHeaders();
        String version = headers.get("X-Jetty-Version");
        if (version != null)
        {
            System.setProperty("jetty.version", version);
        }
        else
        {
            version = Server.getVersion();
        }
        return version;
    }

    private boolean initializeHttp()
    {
        HttpConnectionFactory connFactory = new HttpConnectionFactory();
        configureHttpConnectionFactory(connFactory);

        ServerConnector connector = new ServerConnector(
            server,
            config.getAcceptors(),
            config.getSelectors(),
            connFactory
        );

        configureConnector(connector, this.config.getHttpPort());

        if (this.config.isProxyLoadBalancerConnection())
        {
            connFactory.getHttpConfiguration().addCustomizer(customizerWrapper);
        }
        return startConnector(connector);
    }

    private boolean initializeHttps()
    {
        HttpConnectionFactory connFactory = new HttpConnectionFactory();
        configureHttpConnectionFactory(connFactory);

        SslContextFactory sslContextFactory = new SslContextFactory();
        configureSslContextFactory(sslContextFactory);

        ServerConnector connector = new ServerConnector(
            server,
            config.getAcceptors(),
            config.getSelectors(),
            new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.toString()),
            connFactory
        );

        HttpConfiguration httpConfiguration = connFactory.getHttpConfiguration();
        httpConfiguration.addCustomizer(new SecureRequestCustomizer());

        if (this.config.isProxyLoadBalancerConnection())
        {
            httpConfiguration.addCustomizer(customizerWrapper);
        }

        configureConnector(connector, this.config.getHttpsPort());
        return startConnector(connector);
    }

    private void configureSslContextFactory(final SslContextFactory connector)
    {
        if (this.config.getKeystoreType() != null)
        {
            connector.setKeyStoreType(this.config.getKeystoreType());
        }

        if (this.config.getKeystore() != null)
        {
            connector.setKeyStorePath(this.config.getKeystore());
        }

        if (this.config.getPassword() != null)
        {
            connector.setKeyStorePassword(this.config.getPassword());
        }

        if (this.config.getKeyPassword() != null)
        {
            connector.setKeyManagerPassword(this.config.getKeyPassword());
        }

        if (this.config.getTruststoreType() != null)
        {
            connector.setTrustStoreType(this.config.getTruststoreType());
        }

        if (this.config.getTruststore() != null)
        {
            connector.setTrustStorePath(this.config.getTruststore());
        }

        if (this.config.getTrustPassword() != null)
        {
            connector.setTrustStorePassword(this.config.getTrustPassword());
        }

        if ("wants".equalsIgnoreCase(this.config.getClientcert()))
        {
            connector.setWantClientAuth(true);
        }
        else if ("needs".equalsIgnoreCase(this.config.getClientcert()))
        {
            connector.setNeedClientAuth(true);
        }

        if (this.config.getExcludedCipherSuites() != null)
        {
            connector.setExcludeCipherSuites(this.config.getExcludedCipherSuites());
        }

        if (this.config.getIncludedCipherSuites() != null)
        {
            connector.setIncludeCipherSuites(this.config.getIncludedCipherSuites());
        }

        if (this.config.getIncludedProtocols() != null)
        {
            connector.setIncludeProtocols(this.config.getIncludedProtocols());
        }

        if (this.config.getExcludedProtocols() != null)
        {
            connector.setExcludeProtocols(this.config.getExcludedProtocols());
        }

        connector.setRenegotiationAllowed(this.config.isRenegotiationAllowed());
    }

    private void configureConnector(final ServerConnector connector, int port)
    {
        connector.setPort(port);
        connector.setHost(this.config.getHost());
        connector.setIdleTimeout(this.config.getHttpTimeout());

        if (this.config.isRegisterMBeans())
        {
            connector.addBean(new ConnectionStatistics());
        }
    }

    private void configureHttpConnectionFactory(HttpConnectionFactory connFactory)
    {
        HttpConfiguration config = connFactory.getHttpConfiguration();
        config.setRequestHeaderSize(this.config.getHeaderSize());
        config.setResponseHeaderSize(this.config.getHeaderSize());
        config.setOutputBufferSize(this.config.getResponseBufferSize());

        // HTTP/1.1 requires Date header if possible (it is)
        config.setSendDateHeader(true);
        config.setSendServerVersion(this.config.isSendServerHeader());
        config.setSendXPoweredBy(this.config.isSendServerHeader());

        connFactory.setInputBufferSize(this.config.getRequestBufferSize());
    }

    private void configureSessionManager(final ServletContextHandler context) throws Exception
    {
        final SessionHandler sessionHandler = context.getSessionHandler();
        sessionHandler.setMaxInactiveInterval(this.config.getSessionTimeout() * 60);
        sessionHandler.setSessionIdPathParameterName(this.config.getProperty(JettyConfig.FELIX_JETTY_SERVLET_SESSION_ID_PATH_PARAMETER_NAME, SessionHandler.__DefaultSessionIdPathParameterName));
        sessionHandler.setCheckingRemoteSessionIdEncoding(this.config.getBooleanProperty(JettyConfig.FELIX_JETTY_SERVLET_CHECK_REMOTE_SESSION_ENCODING, true));
        sessionHandler.setSessionTrackingModes(Collections.singleton(SessionTrackingMode.COOKIE));

        final SessionCookieConfig cookieConfig = sessionHandler.getSessionCookieConfig();
        cookieConfig.setName(this.config.getProperty(JettyConfig.FELIX_JETTY_SERVLET_SESSION_COOKIE_NAME, SessionHandler.__DefaultSessionCookie));
        cookieConfig.setDomain(this.config.getProperty(JettyConfig.FELIX_JETTY_SERVLET_SESSION_DOMAIN, SessionHandler.__DefaultSessionDomain));
        cookieConfig.setPath(this.config.getProperty(JettyConfig.FELIX_JETTY_SERVLET_SESSION_PATH, context.getContextPath()));
        cookieConfig.setMaxAge(this.config.getIntProperty(JettyConfig.FELIX_JETTY_SERVLET_SESSION_MAX_AGE, -1));
        cookieConfig.setHttpOnly(this.config.getBooleanProperty(JettyConfig.FELIX_JETTY_SESSION_COOKIE_HTTP_ONLY, true));
        cookieConfig.setSecure(this.config.getBooleanProperty(JettyConfig.FELIX_JETTY_SESSION_COOKIE_SECURE, false));
    }

    private boolean startConnector(Connector connector)
    {
        this.server.addConnector(connector);
        try
        {
            connector.start();
            return true;
        }
        catch (Exception e)
        {
            this.server.removeConnector(connector);
            SystemLogger.error("Failed to start Connector: " + connector, e);
        }

        return false;
    }

    private String getEndpoint(final Connector listener, final InetAddress ia)
    {
        if (ia.isLoopbackAddress())
        {
            return null;
        }

        String address = ia.getHostAddress().trim().toLowerCase();
        if (ia instanceof Inet6Address)
        {
            // skip link-local
            if (address.startsWith("fe80:0:0:0:"))
            {
                return null;
            }
            address = "[" + address + "]";
        }
        else if (!(ia instanceof Inet4Address))
        {
            return null;
        }

        return getEndpoint(listener, address);
    }

    private ServerConnector getServerConnector(Connector connector)
    {
        if (connector instanceof ServerConnector)
        {
            return (ServerConnector) connector;
        }
        throw new IllegalArgumentException("Connection instance not of type ServerConnector " + connector);
    }

    private String getEndpoint(final Connector listener, final String hostname)
    {
        final StringBuilder sb = new StringBuilder();
        sb.append("http");
        int defaultPort = 80;
        //SslConnectionFactory protocol is SSL-HTTP1.0
        if (getServerConnector(listener).getDefaultProtocol().startsWith("SSL"))
        {
            sb.append('s');
            defaultPort = 443;
        }
        sb.append("://");
        sb.append(hostname);
        if (getServerConnector(listener).getPort() != defaultPort)
        {
            sb.append(':');
            sb.append(String.valueOf(getServerConnector(listener).getPort()));
        }
        sb.append(config.getContextPath());

        return sb.toString();
    }

    private List<String> getEndpoints(final Connector connector, final List<NetworkInterface> interfaces)
    {
        final List<String> endpoints = new ArrayList<>();
        for (final NetworkInterface ni : interfaces)
        {
            final Enumeration<InetAddress> ias = ni.getInetAddresses();
            while (ias.hasMoreElements())
            {
                final InetAddress ia = ias.nextElement();
                final String endpoint = this.getEndpoint(connector, ia);
                if (endpoint != null)
                {
                    endpoints.add(endpoint);
                }
            }
        }
        return endpoints;
    }

    private void addEndpointProperties(final Hashtable<String, Object> props, Object container)
    {
        final List<String> endpoints = new ArrayList<>();

        final Connector[] connectors = this.server.getConnectors();
        if (connectors != null)
        {
            for (int i = 0; i < connectors.length; i++)
            {
                final Connector connector = connectors[i];

                if (getServerConnector(connector).getHost() == null || "0.0.0.0".equals(getServerConnector(connector).getHost()))
                {
                    try
                    {
                        final List<NetworkInterface> interfaces = new ArrayList<>();
                        final List<NetworkInterface> loopBackInterfaces = new ArrayList<>();
                        final Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
                        while (nis.hasMoreElements())
                        {
                            final NetworkInterface ni = nis.nextElement();
                            if (ni.isLoopback())
                            {
                                loopBackInterfaces.add(ni);
                            }
                            else
                            {
                                interfaces.add(ni);
                            }
                        }

                        // only add loop back endpoints to the endpoint property if no other endpoint is available.
                        if (!interfaces.isEmpty())
                        {
                            endpoints.addAll(getEndpoints(connector, interfaces));
                        }
                        else
                        {
                            endpoints.addAll(getEndpoints(connector, loopBackInterfaces));
                        }
                    }
                    catch (final SocketException se)
                    {
                        // we ignore this
                    }
                }
                else
                {
                    final String endpoint = this.getEndpoint(connector, getServerConnector(connector).getHost());
                    if (endpoint != null)
                    {
                        endpoints.add(endpoint);
                    }
                }
            }
        }
        props.put(HttpServiceRuntimeConstants.HTTP_SERVICE_ENDPOINT,
                endpoints.toArray(new String[endpoints.size()]));
    }

    private Deployment startWebAppBundle(Bundle bundle, String contextPath)
    {
        postEvent(WebEvent.TOPIC_DEPLOYING, bundle, this.context.getBundle(), null, null, null);

        // check existing deployments
        Deployment deployment = this.deployments.get(contextPath);
        if (deployment != null)
        {
            SystemLogger.warning(String.format("Web application bundle %s has context path %s which is already registered", bundle.getSymbolicName(), contextPath), null);
            postEvent(WebEvent.TOPIC_FAILED, bundle, this.context.getBundle(), null, contextPath, deployment.getBundle().getBundleId());
            return null;
        }

        // check context path belonging to Http Service implementation
        if (contextPath.equals("/"))
        {
            SystemLogger.warning(String.format("Web application bundle %s has context path %s which is reserved", bundle.getSymbolicName(), contextPath), null);
            postEvent(WebEvent.TOPIC_FAILED, bundle, this.context.getBundle(), null, contextPath, this.context.getBundle().getBundleId());
            return null;
        }

        // check against excluded paths
        for (String path : this.config.getPathExclusions())
        {
            if (contextPath.startsWith(path))
            {
                SystemLogger.warning(String.format("Web application bundle %s has context path %s which clashes with excluded path prefix %s", bundle.getSymbolicName(), contextPath, path), null);
                postEvent(WebEvent.TOPIC_FAILED, bundle, this.context.getBundle(), null, path, null);
                return null;
            }
        }

        deployment = new Deployment(contextPath, bundle);
        this.deployments.put(contextPath, deployment);

        WebAppBundleContext context = new WebAppBundleContext(contextPath, bundle, this.getClass().getClassLoader());
        deploy(deployment, context);
        return deployment;
    }

    public void deploy(final Deployment deployment, final WebAppBundleContext context)
    {
        if (!isExecutorServiceAvailable())
        {
            // Shutting down...?
            return;
        }

        this.executor.submit(new JettyOperation()
        {
            @Override
            protected void doExecute()
            {
                final Bundle webAppBundle = deployment.getBundle();
                final Bundle extenderBundle = JettyService.this.context.getBundle();

                try
                {
                    context.getServletContext().setAttribute(OSGI_BUNDLE_CONTEXT, webAppBundle.getBundleContext());

                    JettyService.this.parent.addHandler(context);
                    context.start();

                    Dictionary<String, Object> props = new Hashtable<>();
                    props.put(WEB_SYMBOLIC_NAME, webAppBundle.getSymbolicName());
                    props.put(WEB_VERSION, webAppBundle.getVersion());
                    props.put(WEB_CONTEXT_PATH, deployment.getContextPath());
                    deployment.setRegistration(webAppBundle.getBundleContext().registerService(ServletContext.class, context.getServletContext(), props));

                    postEvent(WebEvent.TOPIC_DEPLOYED, webAppBundle, extenderBundle, null, null, null);
                }
                catch (Exception e)
                {
                    SystemLogger.error(String.format("Deploying web application bundle %s failed.", webAppBundle.getSymbolicName()), e);
                    postEvent(WebEvent.TOPIC_FAILED, webAppBundle, extenderBundle, e, null, null);
                    deployment.setContext(null);
                }
            }
        });
        deployment.setContext(context);
    }

    public void undeploy(final Deployment deployment, final WebAppBundleContext context)
    {
        if (!isExecutorServiceAvailable())
        {
            // Already stopped...?
            return;
        }

        this.executor.submit(new JettyOperation()
        {
            @Override
            protected void doExecute()
            {
                final Bundle webAppBundle = deployment.getBundle();
                final Bundle extenderBundle = JettyService.this.context.getBundle();

                try
                {
                    postEvent(WebEvent.TOPIC_UNDEPLOYING, webAppBundle, extenderBundle, null, null, null);

                    context.getServletContext().removeAttribute(OSGI_BUNDLE_CONTEXT);

                    ServiceRegistration<ServletContext> registration = deployment.getRegistration();
                    if (registration != null)
                    {
                        registration.unregister();
                    }
                    deployment.setRegistration(null);
                    deployment.setContext(null);
                    context.stop();
                }
                catch (Exception e)
                {
                    SystemLogger.error(String.format("Undeploying web application bundle %s failed.", webAppBundle.getSymbolicName()), e);
                }
                finally
                {
                    postEvent(WebEvent.TOPIC_UNDEPLOYED, webAppBundle, extenderBundle, null, null, null);
                }
            }
        });
    }

    private void postEvent(final String topic,
            final Bundle webAppBundle,
            final Bundle extenderBundle,
            final Throwable exception,
            final String collision,
            final Long collisionBundles)
    {
        final Object ea = this.eventAdmin;
        if (ea != null)
        {
            WebEvent.postEvent(ea, topic, webAppBundle, extenderBundle, exception, collision, collisionBundles);
        }
    }

    @Override
    public void lifeCycleStarted(LifeCycle event)
    {
        for (Deployment deployment : this.deployments.values())
        {
            if (deployment.getContext() == null)
            {
                postEvent(WebEvent.TOPIC_DEPLOYING, deployment.getBundle(), this.context.getBundle(), null, null, null);
                WebAppBundleContext context = new WebAppBundleContext(deployment.getContextPath(), deployment.getBundle(), this.getClass().getClassLoader());
                deploy(deployment, context);
            }
        }
    }

    @Override
    public void lifeCycleStopping(LifeCycle event)
    {
        for (Deployment deployment : this.deployments.values())
        {
            if (deployment.getContext() != null)
            {
                undeploy(deployment, deployment.getContext());
            }
        }
    }

    /**
     * A deployment represents a web application bundle that may or may not be deployed.
     */
    static class Deployment
    {
        private String contextPath;
        private Bundle bundle;
        private WebAppBundleContext context;
        private ServiceRegistration<ServletContext> registration;

        public Deployment(String contextPath, Bundle bundle)
        {
            this.contextPath = contextPath;
            this.bundle = bundle;
        }

        public Bundle getBundle()
        {
            return this.bundle;
        }

        public String getContextPath()
        {
            return this.contextPath;
        }

        public WebAppBundleContext getContext()
        {
            return this.context;
        }

        public void setContext(WebAppBundleContext context)
        {
            this.context = context;
        }

        public ServiceRegistration<ServletContext> getRegistration()
        {
            return this.registration;
        }

        public void setRegistration(ServiceRegistration<ServletContext> registration)
        {
            this.registration = registration;
        }
    }

    /**
     * A Jetty operation is executed with the context class loader set to this class's
     * class loader.
     */
    abstract static class JettyOperation implements Callable<Void>
    {
        @Override
        public Void call() throws Exception
        {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();

            try
            {
                Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader());
                doExecute();
                return null;
            }
            finally
            {
                Thread.currentThread().setContextClassLoader(cl);
            }
        }

        protected abstract void doExecute() throws Exception;
    }

    /**
     * @return <code>true</code> if there is a valid executor service available, <code>false</code> otherwise.
     */
    private boolean isExecutorServiceAvailable()
    {
        return this.executor != null && !this.executor.isShutdown();
    }
}
