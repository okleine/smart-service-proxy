package eu.spitfire.ssp;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import eu.spitfire.ssp.backends.external.n3files.N3FileBackendComponentFactory;
import eu.spitfire.ssp.backends.generic.BackendComponentFactory;
import eu.spitfire.ssp.backends.internal.se.SemanticEntityBackendComponentFactory;
import eu.spitfire.ssp.backends.internal.vs.VirtualSensorBackendComponentFactory;
import eu.spitfire.ssp.server.handler.cache.DummySemanticCache;
import eu.spitfire.ssp.server.handler.cache.LuposdateSemanticCache;
import eu.spitfire.ssp.server.handler.cache.SemanticCache;
import eu.spitfire.ssp.server.internal.messages.requests.WebserviceRegistration;
import eu.spitfire.ssp.server.handler.HttpRequestDispatcher;
import eu.spitfire.ssp.server.pipelines.HttpProxyPipelineFactory;
import eu.spitfire.ssp.server.webservices.*;
import eu.spitfire.ssp.server.webservices.Styles;
import eu.spitfire.ssp.server.pipelines.InternalPipelineFactory;
import org.apache.commons.configuration.Configuration;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.*;
import org.jboss.netty.channel.local.DefaultLocalServerChannelFactory;
import org.jboss.netty.channel.local.LocalServerChannel;
import org.jboss.netty.channel.local.LocalServerChannelFactory;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.execution.ExecutionHandler;
import org.jboss.netty.handler.execution.OrderedMemoryAwareThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.concurrent.*;

/**
 * Created with IntelliJ IDEA.
 * User: olli
 * Date: 03.10.13
 * Time: 21:07
 * To change this template use File | Settings | File Templates.
 */
public class Initializer {

    private Logger log = LoggerFactory.getLogger(this.getClass().getName());

    private Configuration config;

    private ScheduledExecutorService internalTasksExecutor;
    private OrderedMemoryAwareThreadPoolExecutor ioExecutor;
    private ServerBootstrap serverBootstrap;

    private LocalServerChannelFactory localChannelFactory;
    private InternalPipelineFactory internalPipelineFactory;

    private ExecutionHandler executionHandler;
    private HttpRequestDispatcher httpRequestDispatcher;
    private SemanticCache semanticCache;

    private Collection<BackendComponentFactory> componentFactories;


    public Initializer(Configuration config) throws Exception {
        this.config = config;
        this.localChannelFactory = new DefaultLocalServerChannelFactory();

        //Create Executor Services
        createInternalTasksExecutorService(config);
        createIoExecutorService(config);

        //Create Pipeline Components
        createMqttResourceHandler(config);
        createSemanticCache(config);
        createHttpRequestDispatcher();

        //create local pipeline factory
        createLocalPipelineFactory();

        //create I/O channel
        createExecutionHandler();
        createServerBootstrap(config);

        //Create backend component factories
        createBackendComponentFactories(config);

        //Create and register initial Webservices
//        registerStylesheet();
        registerHomepage();
        registerFavicon();
        registerSparqlEndpoint();

        registerTrafficMonitoring();

//        registerSlseDefinitionService();
//        registerSlseCreationService();
//
//        registerVirtualSensorDefinitionService();
    }


    private void createInternalTasksExecutorService(Configuration config) {

        //Scheduled Executor Service for management tasks, i.e. everything that is not I/O
        ThreadFactory threadFactory = new ThreadFactoryBuilder().setNameFormat("SSP Internal Thread #%d")
                .build();

        int threadCount = Math.max(Runtime.getRuntime().availableProcessors() * 2,
                config.getInt("SSP_INTERNAL_THREADS", 0));

        this.internalTasksExecutor = Executors.newScheduledThreadPool(threadCount, threadFactory);
        log.info("Management Executor Service created with {} threads.", threadCount);
    }


    private void createIoExecutorService(Configuration config) {

        int threadCount = Math.max(Runtime.getRuntime().availableProcessors() * 2,
                config.getInt("SSP_I/O_THREADS", 0));

        ThreadFactory threadFactory = new ThreadFactoryBuilder().setNameFormat("SSP I/O Thread #%d")
                .build();

        this.ioExecutor = new OrderedMemoryAwareThreadPoolExecutor(threadCount, 0, 0, 60, TimeUnit.SECONDS,
                threadFactory);

        log.info("I/O-Executor-Service created with {} threads", threadCount);
    }


    private void createExecutionHandler() {
        this.executionHandler = new ExecutionHandler(this.ioExecutor);
        log.debug("Execution Handler created.");
    }


    public void initialize(){
        //Start proxy server
        int port = this.config.getInt("SSP_HTTP_PORT", 8080);
        this.serverBootstrap.bind(new InetSocketAddress(port));
        log.info("HTTP proxy started (listening on port {})", port);
    }


    public Collection<BackendComponentFactory> getComponentFactories() {
        return this.componentFactories;
    }


    private void createBackendComponentFactories(Configuration config) throws Exception {
        String[] enabledBackends = config.getStringArray("ENABLED_BACKEND");
        this.componentFactories = new ArrayList<>(enabledBackends.length + 1);

        //Add backend for semantic entities (default)
        ChannelPipeline localPipeline = internalPipelineFactory.getPipeline();
        LocalServerChannel localChannel = localChannelFactory.newChannel(localPipeline);
        this.componentFactories.add(new SemanticEntityBackendComponentFactory(
                "se", config, localChannel, this.internalTasksExecutor, this.ioExecutor)
        );

        //Add backend for virtual sensors (default)
        localPipeline = internalPipelineFactory.getPipeline();
        localChannel = localChannelFactory.newChannel(localPipeline);
        this.componentFactories.add(new VirtualSensorBackendComponentFactory(
                "vs", config, localChannel, this.internalTasksExecutor, this.ioExecutor)
        );


        //Add other backends
        for (String backendName : enabledBackends) {
            localPipeline = internalPipelineFactory.getPipeline();
            localChannel = localChannelFactory.newChannel(localPipeline);

            switch (backendName) {
                case "n3files": {
                    this.componentFactories.add(new N3FileBackendComponentFactory(
                            "n3files", config, localChannel, this.internalTasksExecutor, this.ioExecutor)
                    );
                    continue;
                }

//                case "coap": {
//                    componentFactory = new CoapBackendComponentFactory("coap", config,
//                            this.internalTasksExecutor);
//                    break;
//                }

                //Unknown backend
                default: {
                    log.error("Config file error: Unknown backend (\"" + backendName + "\")!");
                }
            }
        }
    }


    private void createLocalPipelineFactory() {
        LinkedHashSet<ChannelHandler> handler = new LinkedHashSet<>();
//        if (!(mqttHandler == null))
//            handler.add(mqttHandler);

        handler.add(semanticCache);
        handler.add(httpRequestDispatcher);

        this.internalPipelineFactory = new InternalPipelineFactory(handler);
        log.debug("Local Pipeline Factory created.");
    }


    private void createServerBootstrap(Configuration config) throws Exception {
        //read parameters from config
        boolean tcpNoDelay = config.getBoolean("SSP_TCP_NODELAY", false);
        int ioThreads = config.getInt("SSP_I/O_THREADS");

        //create the bootstrap
        Executor ioExecutor = Executors.newCachedThreadPool();
        this.serverBootstrap = new ServerBootstrap(new NioServerSocketChannelFactory(
                Executors.newCachedThreadPool(),
                ioExecutor, ioThreads)
        );

        this.serverBootstrap.setOption("reuseAddress", true);
        this.serverBootstrap.setOption("tcpNoDelay", tcpNoDelay);

        LinkedHashSet<ChannelHandler> handler = new LinkedHashSet<>();

        handler.add(executionHandler);

//        if (!(mqttHandler == null))
//            handler.add(mqttHandler);

        handler.add(semanticCache);
        handler.add(httpRequestDispatcher);

        this.serverBootstrap.setPipelineFactory(new HttpProxyPipelineFactory(handler));
        log.debug("Server Bootstrap created.");
    }


    private void createHttpRequestDispatcher() throws Exception {
        Styles styleWebservice = new Styles(
                this.ioExecutor, this.internalTasksExecutor, null
        );

        this.httpRequestDispatcher = new HttpRequestDispatcher(styleWebservice);
        log.debug("HTTP Request Dispatcher created.");
    }


    private void createMqttResourceHandler(Configuration config) throws Exception {
        if (config.getBoolean("ENABLE_MQTT", false)) {
            String mqttBrokerUri = config.getString("MQTT_BROKER_URI");
            int mqttBrokerHttpPort = config.getInt("MQTT_BROKER_HTTP_PORT");
//            this.mqttHandler = new MqttHandler(mqttBrokerUri, mqttBrokerHttpPort);
            log.debug("MQTT Handler created.");
        } else {
//            this.mqttHandler = null;
            log.debug("MQTT was disabled.");
        }
    }


    private void createSemanticCache(Configuration config) throws Exception{
        String cacheType = config.getString("cache");

        if ("dummy".equals(cacheType)) {
            this.semanticCache = new DummySemanticCache(this.ioExecutor, this.internalTasksExecutor);
            log.info("Semantic Cache is of type {}", this.semanticCache.getClass().getSimpleName());
            return;
        }

//        if ("jenaTDB".equals(cacheType)) {
//            String dbDirectory = config.getString("cache.jenaTDB.dbDirectory");
//            if (dbDirectory == null)
//                throw new RuntimeException("'cache.jenaTDB.dbDirectory' missing in ssp.properties");
//
//            String spatialIndexDirectory = config.getString("cache.spatial.index.directory");
//            if (spatialIndexDirectory == null)
//                throw new RuntimeException("'cache.spatial.index.directory' missing in ssp.properties");
//
//            Path directoryPath = Paths.get(dbDirectory);
//            Path spatialIndexDirectoryPath = Paths.get(spatialIndexDirectory);
//
//            if(!Files.isDirectory(directoryPath))
//                throw new IllegalArgumentException("The given path for Jena TDB does not refer to a directory!");
//
//            this.semanticCache = new JenaTdbSemanticCache(this.ioExecutor, this.internalTasksExecutor,
//                    directoryPath, spatialIndexDirectoryPath);
//
//            return;
//        }
//
        if("luposdate".equals(cacheType)){
            this.semanticCache = new LuposdateSemanticCache(this.ioExecutor, this.internalTasksExecutor);
            return;

        }
//        if("jenaSDB".equals(cacheType)){
//            String jdbcUri = config.getString("cache.jenaSDB.jdbc.url");
//            String jdbcUser = config.getString("cache.jenaSDB.jdbc.user");
//            String jdbcPassword = config.getString("cache.jenaSDB.jdbc.password");
//
//            this.semanticCache = new JenaSdbSemanticCache(this.internalTasksExecutor, jdbcUri, jdbcUser, jdbcPassword);
//            return;
//        }

        throw new RuntimeException("No cache type defined in ssp.properties");
    }


    private void registerHomepage() throws Exception{
        registerHttpWebservice(
                new URI(null, null, null, -1, "/", null, null),
                new Homepage(this.ioExecutor, this.internalTasksExecutor)
        );
    }

    /**
     * Registers the service to provide the favicon.ico
     */
    private void registerFavicon() throws Exception {
        URI uri = new URI(null, null, null, -1, "/favicon.ico", null, null);
        HttpWebservice httpWebservice = new Favicon(this.ioExecutor);
        registerHttpWebservice(uri, httpWebservice);
    }


    private void registerSparqlEndpoint() throws Exception{
        URI uri = new URI(null, null, null, -1, "/services/sparql-endpoint", null, null);

        LocalServerChannel localChannel = this.localChannelFactory.newChannel(
                this.internalPipelineFactory.getPipeline()
        );

        HttpWebservice httpWebservice = new SparqlEndpoint(
                this.ioExecutor, this.internalTasksExecutor, localChannel
        );

        registerHttpWebservice(uri, httpWebservice);
    }


    private void registerTrafficMonitoring() throws Exception{
        URI uri = new URI(null, null, null, -1, "/services/geo-views/traffic-monitoring", null, null);

        LocalServerChannel localChannel = this.localChannelFactory.newChannel(
                this.internalPipelineFactory.getPipeline()
        );

        HttpWebservice httpWebservice = new TrafficMonitoring(
                this.ioExecutor, this.internalTasksExecutor
        );

        registerHttpWebservice(uri, httpWebservice);

    }

    private void registerHttpWebservice(final URI webserviceUri, HttpWebservice httpWebservice) throws Exception{

        LocalServerChannel localChannel = localChannelFactory.newChannel(internalPipelineFactory.getPipeline());
        WebserviceRegistration registrationMessage = new WebserviceRegistration(webserviceUri,
                httpWebservice);

        ChannelFuture future = Channels.write(localChannel, registrationMessage);

        future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (future.isSuccess())
                    log.info("Successfully registered HTTP Webservice at URI {}", webserviceUri);
                else
                    log.error("Could not register HTTP Webservice!", future.getCause());
            }
        });

    }

}