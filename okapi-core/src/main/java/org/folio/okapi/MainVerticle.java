package org.folio.okapi;

import org.folio.okapi.managers.ModuleManager;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.spi.cluster.ClusterManager;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.CorsHandler;
import static java.lang.System.getenv;
import java.lang.management.ManagementFactory;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.folio.okapi.bean.ModuleDescriptor;
import org.folio.okapi.bean.Ports;
import org.folio.okapi.bean.Tenant;
import org.folio.okapi.common.Config;
import static org.folio.okapi.common.ErrorType.NOT_FOUND;
import org.folio.okapi.common.Messages;
import org.folio.okapi.common.ModuleVersionReporter;
import org.folio.okapi.common.OkapiLogger;
import org.folio.okapi.managers.DeploymentManager;
import org.folio.okapi.service.ModuleStore;
import org.folio.okapi.managers.ProxyService;
import org.folio.okapi.managers.TenantManager;
import org.folio.okapi.service.TenantStore;
import org.folio.okapi.util.LogHelper;
import org.folio.okapi.common.XOkapiHeaders;
import org.folio.okapi.managers.DiscoveryManager;
import org.folio.okapi.managers.EnvManager;
import org.folio.okapi.managers.PullManager;
import org.folio.okapi.service.impl.Storage;
import static org.folio.okapi.service.impl.Storage.InitMode.*;
import org.folio.okapi.common.ModuleId;
import org.folio.okapi.managers.InternalModule;

@java.lang.SuppressWarnings({"squid:S1192"})
public class MainVerticle extends AbstractVerticle {

  private final Logger logger = OkapiLogger.get();
  private final LogHelper logHelper = new LogHelper();

  private ModuleManager moduleManager;
  private TenantManager tenantManager;
  private EnvManager envManager;
  private ProxyService proxyService;
  private DeploymentManager deploymentManager;
  private DiscoveryManager discoveryManager;
  private ClusterManager clusterManager;
  private Storage storage;
  private Storage.InitMode initMode = NORMAL;
  private int port;
  private String okapiVersion = null;
  private Messages messages = Messages.getInstance();

  public void setClusterManager(ClusterManager mgr) {
    clusterManager = mgr;
  }

  @Override
  public void init(Vertx vertx, Context context) {
    ModuleVersionReporter m = new ModuleVersionReporter("org.folio.okapi/okapi-core");
    okapiVersion = m.getVersion();
    m.logStart();

    boolean enableProxy = false;
    boolean enableDeployment = false;

    super.init(vertx, context);

    JsonObject config = context.config();
    port = Integer.parseInt(Config.getSysConf("port", "9130", config));
    int portStart = Integer.parseInt(Config.getSysConf("port_start", Integer.toString(port + 1), config));
    int portEnd = Integer.parseInt(Config.getSysConf("port_end", Integer.toString(portStart + 10), config));

    String okapiVersion2 = Config.getSysConf("okapiVersion", null, config);
    if (okapiVersion2 != null) {
      okapiVersion = okapiVersion2;
    }

    if (clusterManager != null) {
      logger.info(messages.getMessage("10000", clusterManager.getNodeID()));
    } else {
      logger.info(messages.getMessage("10001"));
    }
    final String host = Config.getSysConf("host", "localhost", config);
    String okapiUrl = Config.getSysConf("okapiurl", "http://localhost:" + port , config);
    okapiUrl = okapiUrl.replaceAll("/+$", ""); // Remove trailing slash, if there
    final String nodeName = Config.getSysConf("nodename", null, config);
    String storageType = Config.getSysConf("storage", "inmemory", config);
    String loglevel = Config.getSysConf("loglevel", "", config);
    if (!loglevel.isEmpty()) {
      logHelper.setRootLogLevel(loglevel);
    } else {
      String lev = getenv("OKAPI_LOGLEVEL");
      if (lev != null && !lev.isEmpty()) {
        logHelper.setRootLogLevel(loglevel);
      }
    }
    final String logWaitMsStr = Config.getSysConf("logWaitMs", "", config);
    final int waitMs = logWaitMsStr.isEmpty() ? 0 : Integer.parseInt(logWaitMsStr);

    String mode = config.getString("mode", "cluster");
    switch (mode) {
      case "deployment":
        enableDeployment = true;
        break;
      case "proxy":
        enableProxy = true;
        break;
      case "purgedatabase":
        initMode = PURGE;
        enableProxy = true; // so we get to initialize the database. We exit soon after anyway
        break;
      case "initdatabase":
        initMode = INIT;
        enableProxy = true;
        break;
      default: // cluster and dev
        enableDeployment = true;
        enableProxy = true;
        break;
    }

    storage = new Storage(vertx, storageType, config);

    envManager = new EnvManager(storage.getEnvStore());
    discoveryManager = new DiscoveryManager(storage.getDeploymentStore());
    if (clusterManager != null) {
      discoveryManager.setClusterManager(clusterManager);
    }
    if (enableDeployment) {
      Ports ports = new Ports(portStart, portEnd);
      deploymentManager = new DeploymentManager(vertx, discoveryManager, envManager,
        host, ports, port, nodeName);
      Runtime.getRuntime().addShutdownHook(new Thread() {
        @Override
        public void run() {
          CountDownLatch latch = new CountDownLatch(1);
          deploymentManager.shutdown(ar -> latch.countDown());
          try {
            if (!latch.await(2, TimeUnit.MINUTES)) {
              logger.error("Timed out waiting to undeploy all");
            }
          } catch (InterruptedException e) {
            logger.error("Exception while shutting down");
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
        }
      });
    }
    if (enableProxy) {
      ModuleStore moduleStore = storage.getModuleStore();
      moduleManager = new ModuleManager(moduleStore);
      TenantStore tenantStore = storage.getTenantStore();
      tenantManager = new TenantManager(moduleManager, tenantStore);
      moduleManager.setTenantManager(tenantManager);
      discoveryManager.setModuleManager(moduleManager);
      logger.info("Proxy using " + storageType + " storage");
      PullManager pullManager = new PullManager(vertx, moduleManager);
      InternalModule internalModule = new InternalModule(moduleManager,
              tenantManager, deploymentManager, discoveryManager,
              envManager, pullManager,okapiVersion);
      proxyService = new ProxyService(vertx,
        moduleManager, tenantManager, discoveryManager,
        internalModule, okapiUrl, waitMs);
      tenantManager.setProxyService(proxyService);
    } else { // not really proxying, except to /_/deployment
      moduleManager = new ModuleManager(null);
      moduleManager.forceLocalMap(); // make sure it is not shared
      tenantManager = new TenantManager(moduleManager, null);
      tenantManager.forceLocalMap();
      moduleManager.setTenantManager(tenantManager);
      discoveryManager.setModuleManager(moduleManager);
      InternalModule internalModule = new InternalModule(
        null, null, deploymentManager, null,
        envManager, null, okapiVersion);
      // no modules, tenants, or discovery. Only deployment and env.
      proxyService = new ProxyService(vertx,
        moduleManager, tenantManager, discoveryManager,
        internalModule, okapiUrl, waitMs);
    }

  }

  @Override
  public void start(Future<Void> fut) {
    logger.debug("starting");
    if (storage != null) {
      storage.prepareDatabases(initMode, res -> {
        if (res.failed()) {
          logger.fatal("start failed", res.cause());
          fut.fail(res.cause());
        } else {
          if (initMode != NORMAL) {
            logger.info("Database operation " + initMode.toString() + " done. Exiting");
            System.exit(0);
          }
          startModmanager(fut);
        }
      });
    } else {
      startModmanager(fut);
    }
  }

  private void startModmanager(Future<Void> fut) {
    moduleManager.init(vertx, res -> {
      if (res.succeeded()) {
        startTenants(fut);
      } else {
        logger.fatal("ModuleManager init: " + res.cause().getMessage());
        fut.fail(res.cause());
      }
    });
  }

  private void startTenants(Future<Void> fut) {
    tenantManager.init(vertx, res -> {
      if (res.succeeded()) {
        checkInternalModules(fut);
      } else {
        logger.fatal("load tenants failed: " + res.cause().getMessage());
        fut.fail(res.cause());
      }
    });
  }


  private void checkInternalModules(Future<Void> fut) {
    final ModuleDescriptor md = InternalModule.moduleDescriptor(okapiVersion);
    final String okapiModule = md.getId();
    final String interfaceVersion = md.getProvides()[0].getVersion();
    moduleManager.get(okapiModule, gres -> {
      if (gres.succeeded()) { // we already have one, go on
        logger.debug("checkInternalModules: Already have " + okapiModule
          + " with interface version " + interfaceVersion);
        // See Okapi-359 about version checks across the cluster
        checkSuperTenant(okapiModule, fut);
        return;
      }
      if (gres.getType() != NOT_FOUND) {
        logger.warn("checkInternalModules: Could not get "
          + okapiModule + ": " + gres.cause());
        fut.fail(gres.cause()); // something went badly wrong
        return;
      }
      logger.debug("Creating the internal Okapi module " + okapiModule
        + " with interface version " + interfaceVersion);
      moduleManager.create(md, true, true, true, ires -> {
        if (ires.failed()) {
          logger.warn("Failed to create the internal Okapi module"
            + okapiModule + " " + ires.cause());
          fut.fail(ires.cause()); // something went badly wrong
          return;
        }
        checkSuperTenant(okapiModule, fut);
      });

    });

  }

  /**
   * Create the super tenant, if not already there.
   *
   * @param fut
   */
  private void checkSuperTenant(String okapiModule, Future<Void> fut) {
    tenantManager.get(XOkapiHeaders.SUPERTENANT_ID, gres -> {
      if (gres.succeeded()) { // we already have one, go on
        logger.info("checkSuperTenant: Already have " + XOkapiHeaders.SUPERTENANT_ID);
        Tenant st = gres.result();
        Set<String> enabledMods = st.getEnabled().keySet();
        if (enabledMods.contains(okapiModule)) {
          logger.info("checkSuperTenant: enabled version is OK");
          startEnv(fut);
          return;
        }
        // Check version compatibility
        String enver = "";
        for (String emod : enabledMods) {
          if (emod.startsWith("okapi-")) {
            enver = emod;
          }
        }
        final String ev = enver;
        logger.debug("checkSuperTenant: Enabled version is '" + ev
          + "', not '" + okapiModule + "'");
        // See Okapi-359 about version checks across the cluster
        if (ModuleId.compare(ev, okapiModule) >= 4) {
          logger.warn("checkSuperTenant: This Okapi is too old, "
            + okapiVersion + " we already have " + ev + " in the database. "
            + " Use that!");
        } else {
          logger.info("checkSuperTenant: Need to upgrade the stored version");
          // Use the commit, easier interface.
          // the internal module can not have dependencies
          // See Okapi-359 about version checks across the cluster
          tenantManager.updateModuleCommit(XOkapiHeaders.SUPERTENANT_ID,
            ev, okapiModule, ures -> {
              if (ures.failed()) {
                logger.debug("checkSuperTenant: "
                  + "Updating enabled internalModule failed: " + ures.cause());
                fut.fail(ures.cause());
                return;
              }
            logger.info("Upgraded the InternalModule version"
              + " from '" + ev + "' to '" + okapiModule + "'"
              + " for " + XOkapiHeaders.SUPERTENANT_ID);
            });
        }
        startEnv(fut);
        return;
      }
      if (gres.getType() != NOT_FOUND) {
        logger.warn("checkSuperTenant: Could not get "
          + XOkapiHeaders.SUPERTENANT_ID + ": " + gres.cause());
        fut.fail(gres.cause()); // something went badly wrong
        return;
      }
      logger.info("Creating the superTenant " + XOkapiHeaders.SUPERTENANT_ID);
      final String docTenant = "{"
        + "\"descriptor\" : {"
        + " \"id\" : \"" + XOkapiHeaders.SUPERTENANT_ID + "\","
        + " \"name\" : \"" + XOkapiHeaders.SUPERTENANT_ID + "\","
        + " \"description\" : \"Okapi built-in super tenant\""
        + " },"
        + "\"enabled\" : {"
        + "\"" + okapiModule + "\" : true"
        + "}"
        + "}";
      final Tenant ten = Json.decodeValue(docTenant, Tenant.class);
      tenantManager.insert(ten, ires -> {
        if (ires.failed()) {
          logger.warn("Failed to create the superTenant "
            + XOkapiHeaders.SUPERTENANT_ID + " " + ires.cause());
          fut.fail(ires.cause()); // something went badly wrong
          return;
        }
        startEnv(fut);
      });
    });
  }

  private void startEnv(Future<Void> fut) {
    logger.debug("starting Env");
    envManager.init(vertx, res -> {
      if (res.succeeded()) {
        startDiscovery(fut);
      } else {
        fut.fail(res.cause());
      }
    });
  }

  private void startDiscovery(Future<Void> fut) {
    logger.debug("Starting discovery");
    discoveryManager.init(vertx, res -> {
      if (res.succeeded()) {
        startDeployment(fut);
      } else {
        fut.fail(res.cause());
      }
    });
  }

  private void startDeployment(Future<Void> fut) {
    if (deploymentManager == null) {
      startListening(fut);
    } else {
      logger.debug("Starting deployment");
      deploymentManager.init(res -> {
        if (res.succeeded()) {
          startListening(fut);
        } else {
          fut.fail(res.cause());
        }
      });
    }
  }

  private void startListening(Future<Void> fut) {
    Router router = Router.router(vertx);
    logger.debug("Setting up routes");
    //handle CORS
    router.route().handler(CorsHandler.create("*")
            .allowedMethod(HttpMethod.PUT)
            .allowedMethod(HttpMethod.DELETE)
            .allowedMethod(HttpMethod.GET)
            .allowedMethod(HttpMethod.POST)
            //allow request headers
            .allowedHeader(HttpHeaders.CONTENT_TYPE.toString())
            .allowedHeader(XOkapiHeaders.TENANT)
            .allowedHeader(XOkapiHeaders.TOKEN)
            .allowedHeader(XOkapiHeaders.AUTHORIZATION)
      .allowedHeader(XOkapiHeaders.REQUEST_ID)            //expose response headers
            .exposedHeader(HttpHeaders.LOCATION.toString())
            .exposedHeader(XOkapiHeaders.TRACE)
            .exposedHeader(XOkapiHeaders.TOKEN)
            .exposedHeader(XOkapiHeaders.AUTHORIZATION)
      .exposedHeader(XOkapiHeaders.REQUEST_ID)
    );

    if (proxyService != null) {
      router.routeWithRegex("^/_/invoke/tenant/[^/ ]+/.*")
        .handler(proxyService::redirectProxy);
      // Note: This can not go into the InternalModule, it reads the req body,
      // and then we can not ctx.reroute(). Unless we do something trickier,
      // like a new HTTP request.
    }

    // everything else gets proxified to modules
    // Even internal functions, they are in the InternalModule
    if (proxyService != null) {
      router.route("/*").handler(proxyService::proxy);
    }

    logger.debug("About to start HTTP server");
    HttpServerOptions so = new HttpServerOptions()
            .setHandle100ContinueAutomatically(true);
    vertx.createHttpServer(so)
            .requestHandler(router::accept)
            .listen(port,
                    result -> {
                      if (result.succeeded()) {
                        logger.info("API Gateway started PID "
                                + ManagementFactory.getRuntimeMXBean().getName()
                          + ". Listening on port " + port);
                        startRedeploy(fut);
                      } else {
                        logger.fatal("createHttpServer failed", result.cause());
                        fut.fail(result.cause());
                      }
                    }
            );
  }

  private void startRedeploy(Future<Void> fut) {
    discoveryManager.restartModules(res -> {
      if (res.succeeded()) {
        logger.info("Deploy completed succesfully");
      } else {
        logger.info("Deploy failed: " + res.cause());
      }
      fut.complete();
    });
  }
}
