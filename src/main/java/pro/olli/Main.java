/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */
package pro.olli;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import org.jboss.logging.Logger;
import pro.olli.service.SyncService;
import pro.olli.connector.ConnectorFactory;

@QuarkusMain
public class Main implements QuarkusApplication {

    private static final Logger LOG = Logger.getLogger(Main.class);
    private final SyncService syncService;
    private final ConnectorFactory connectorFactory;

    public Main(SyncService syncService, ConnectorFactory connectorFactory) {
        this.syncService = syncService;
        this.connectorFactory = connectorFactory;
    }

    public static void main(String[] args) {
        Quarkus.run(Main.class, args);
    }

    @Override
    public int run(String... args) {
        LOG.info("Olli AI application starting...");
        LOG.infof("Registered connector providers: %s", connectorFactory.getSupportedProviders());
        Quarkus.waitForExit();
        return 0;
    }
}
