/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.api;

import io.xnatworks.router.anon.ScriptLibrary;
import io.xnatworks.router.archive.ArchiveManager;
import io.xnatworks.router.broker.HonestBrokerService;
import io.xnatworks.router.config.AppConfig;
import io.xnatworks.router.index.DicomIndexer;
import io.xnatworks.router.metrics.MetricsCollector;
import io.xnatworks.router.ocr.OcrService;
import io.xnatworks.router.review.DicomComparisonService;
import io.xnatworks.router.routing.DestinationManager;
import io.xnatworks.router.store.RouterStore;
import io.xnatworks.router.tracking.TransferTracker;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.Resource;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;

/**
 * Embedded Jetty server for admin REST API and web UI.
 */
public class AdminServer {
    private static final Logger log = LoggerFactory.getLogger(AdminServer.class);

    private final int port;
    private final String host;
    private final AppConfig config;
    private final DestinationManager destinationManager;
    private final TransferTracker transferTracker;
    private final ScriptLibrary scriptLibrary;
    private final MetricsCollector metricsCollector;
    private final RouterStore routerStore;
    private final DicomIndexer dicomIndexer;
    private final boolean headless;
    private final ArchiveManager archiveManager;
    private final OcrService ocrService;

    private Server server;

    public AdminServer(int port, String host, AppConfig config,
                       DestinationManager destinationManager,
                       TransferTracker transferTracker,
                       ScriptLibrary scriptLibrary) {
        this(port, host, config, destinationManager, transferTracker, scriptLibrary, null, null, null, false, null, null);
    }

    public AdminServer(int port, String host, AppConfig config,
                       DestinationManager destinationManager,
                       TransferTracker transferTracker,
                       ScriptLibrary scriptLibrary,
                       boolean headless) {
        this(port, host, config, destinationManager, transferTracker, scriptLibrary, null, null, null, headless, null, null);
    }

    public AdminServer(int port, String host, AppConfig config,
                       DestinationManager destinationManager,
                       TransferTracker transferTracker,
                       ScriptLibrary scriptLibrary,
                       MetricsCollector metricsCollector,
                       boolean headless) {
        this(port, host, config, destinationManager, transferTracker, scriptLibrary, metricsCollector, null, null, headless, null, null);
    }

    public AdminServer(int port, String host, AppConfig config,
                       DestinationManager destinationManager,
                       TransferTracker transferTracker,
                       ScriptLibrary scriptLibrary,
                       MetricsCollector metricsCollector,
                       RouterStore routerStore,
                       DicomIndexer dicomIndexer,
                       boolean headless) {
        this(port, host, config, destinationManager, transferTracker, scriptLibrary, metricsCollector, routerStore, dicomIndexer, headless, null, null);
    }

    public AdminServer(int port, String host, AppConfig config,
                       DestinationManager destinationManager,
                       TransferTracker transferTracker,
                       ScriptLibrary scriptLibrary,
                       MetricsCollector metricsCollector,
                       RouterStore routerStore,
                       DicomIndexer dicomIndexer,
                       boolean headless,
                       ArchiveManager archiveManager,
                       OcrService ocrService) {
        this.port = port;
        this.host = host;
        this.config = config;
        this.destinationManager = destinationManager;
        this.transferTracker = transferTracker;
        this.scriptLibrary = scriptLibrary;
        this.metricsCollector = metricsCollector;
        this.routerStore = routerStore;
        this.dicomIndexer = dicomIndexer;
        this.headless = headless;
        this.archiveManager = archiveManager;
        this.ocrService = ocrService;
    }

    /**
     * Start the admin server.
     */
    public void start() throws Exception {
        server = new Server();

        // Configure HTTP with lenient URI compliance to allow empty segments
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setUriCompliance(UriCompliance.LEGACY);

        HttpConnectionFactory httpFactory = new HttpConnectionFactory(httpConfig);

        ServerConnector connector = new ServerConnector(server, httpFactory);
        connector.setPort(port);
        server.addConnector(connector);

        // Configure Jersey with HK2 binder for dependency injection
        final StatusResource statusResource = new StatusResource(config, destinationManager, transferTracker);
        final RoutesResource routesResource = new RoutesResource(config);
        final DestinationsResource destinationsResource = new DestinationsResource(config, destinationManager);
        final ScriptsResource scriptsResource = new ScriptsResource(scriptLibrary);
        final TransfersResource transfersResource = new TransfersResource(transferTracker);
        final ConfigResource configResource = new ConfigResource(config);
        final AuthResource authResource = new AuthResource(config);
        final StorageResource storageResource = new StorageResource(config);
        final LogsResource logsResource = new LogsResource(config);
        final HonestBrokerService honestBrokerService = new HonestBrokerService(config);
        final HonestBrokersResource honestBrokersResource = new HonestBrokersResource(config, honestBrokerService);
        final ImportResource importResource = new ImportResource(config, destinationManager, transferTracker, scriptLibrary, honestBrokerService);
        final OcrResource ocrResource = new OcrResource(config);
        final QueryRetrieveResource queryRetrieveResource = new QueryRetrieveResource(config, destinationManager, transferTracker);
        final MetricsResource metricsResource = metricsCollector != null ? new MetricsResource(metricsCollector) : null;
        final SearchResource searchResource = (routerStore != null && dicomIndexer != null)
                ? new SearchResource(config, routerStore, dicomIndexer) : null;
        final AuditResource auditResource = new AuditResource(scriptLibrary, java.nio.file.Paths.get(config.getDataDirectory()));
        final DicomComparisonService comparisonService = (archiveManager != null)
                ? new DicomComparisonService(archiveManager, ocrService) : null;
        final DicomCompareResource dicomCompareResource = (comparisonService != null)
                ? new DicomCompareResource(comparisonService, config) : null;
        final AuthFilter authFilter = new AuthFilter(config);

        ResourceConfig resourceConfig = new ResourceConfig();

        // Register singleton binder
        resourceConfig.register(new org.glassfish.jersey.internal.inject.AbstractBinder() {
            @Override
            protected void configure() {
                bind(statusResource).to(StatusResource.class);
                bind(routesResource).to(RoutesResource.class);
                bind(destinationsResource).to(DestinationsResource.class);
                bind(scriptsResource).to(ScriptsResource.class);
                bind(transfersResource).to(TransfersResource.class);
                bind(configResource).to(ConfigResource.class);
                bind(authResource).to(AuthResource.class);
                bind(storageResource).to(StorageResource.class);
                bind(logsResource).to(LogsResource.class);
                bind(honestBrokersResource).to(HonestBrokersResource.class);
                bind(importResource).to(ImportResource.class);
                bind(ocrResource).to(OcrResource.class);
                bind(queryRetrieveResource).to(QueryRetrieveResource.class);
                if (metricsResource != null) {
                    bind(metricsResource).to(MetricsResource.class);
                }
                if (searchResource != null) {
                    bind(searchResource).to(SearchResource.class);
                }
                bind(auditResource).to(AuditResource.class);
                if (dicomCompareResource != null) {
                    bind(dicomCompareResource).to(DicomCompareResource.class);
                }
            }
        });

        // Register resource classes
        resourceConfig.register(StatusResource.class);
        resourceConfig.register(RoutesResource.class);
        resourceConfig.register(DestinationsResource.class);
        resourceConfig.register(ScriptsResource.class);
        resourceConfig.register(TransfersResource.class);
        resourceConfig.register(ConfigResource.class);
        resourceConfig.register(AuthResource.class);
        resourceConfig.register(StorageResource.class);
        resourceConfig.register(LogsResource.class);
        resourceConfig.register(HonestBrokersResource.class);
        resourceConfig.register(ImportResource.class);
        resourceConfig.register(OcrResource.class);
        resourceConfig.register(QueryRetrieveResource.class);
        if (metricsResource != null) {
            resourceConfig.register(MetricsResource.class);
        }
        if (searchResource != null) {
            resourceConfig.register(SearchResource.class);
        }
        resourceConfig.register(AuditResource.class);
        if (dicomCompareResource != null) {
            resourceConfig.register(DicomCompareResource.class);
        }

        // Register auth filter
        resourceConfig.register(authFilter);

        // Enable JSON
        resourceConfig.register(org.glassfish.jersey.jackson.JacksonFeature.class);

        // Enable multipart file uploads
        resourceConfig.register(org.glassfish.jersey.media.multipart.MultiPartFeature.class);

        // CORS filter
        resourceConfig.register(CorsFilter.class);

        // Create servlet
        ServletContainer servletContainer = new ServletContainer(resourceConfig);
        ServletHolder servletHolder = new ServletHolder(servletContainer);

        // Create context
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");
        context.addServlet(servletHolder, "/api/*");

        // Serve static files for UI from classpath resources (unless headless)
        if (!headless) {
            URL adminUrl = getClass().getClassLoader().getResource("META-INF/resources/admin");
            if (adminUrl != null) {
                context.setBaseResource(Resource.newResource(adminUrl));
                context.setWelcomeFiles(new String[]{"index.html"});

                // Default servlet for static files (js, css, images, etc.)
                ServletHolder defaultHolder = new ServletHolder("default", DefaultServlet.class);
                defaultHolder.setInitParameter("dirAllowed", "false");
                defaultHolder.setInitParameter("welcomeServlets", "false");
                defaultHolder.setInitParameter("redirectWelcome", "false");
                context.addServlet(defaultHolder, "/assets/*");

                // SPA fallback servlet - serves index.html for root and client-side routes
                // This enables browser refresh and direct navigation to routes like /storage, /routes, etc.
                ServletHolder spaHolder = new ServletHolder("spa-fallback", SpaFallbackServlet.class);
                context.addServlet(spaHolder, "/");
                context.addServlet(spaHolder, "/*");

                log.info("Serving admin UI from classpath: {}", adminUrl);
            } else {
                log.warn("Admin UI resources not found in classpath");
            }
        } else {
            log.info("Running in headless mode - API only, no UI");
        }

        server.setHandler(context);
        server.start();

        if (config.isAuthRequired()) {
            log.info("Admin server started on http://{}:{} (authentication required)", host, port);
        } else {
            log.info("Admin server started on http://{}:{} (authentication disabled)", host, port);
        }
    }

    /**
     * Stop the admin server.
     */
    public void stop() throws Exception {
        if (server != null) {
            server.stop();
            log.info("Admin server stopped");
        }
    }

    /**
     * Check if server is running.
     */
    public boolean isRunning() {
        return server != null && server.isRunning();
    }

    /**
     * Wait for server to stop.
     */
    public void join() throws InterruptedException {
        if (server != null) {
            server.join();
        }
    }
}
