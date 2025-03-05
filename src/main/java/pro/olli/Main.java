/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import org.jboss.logging.Logger;
import pro.olli.service.SyncService;
import pro.olli.storage.StorageFactory;

@QuarkusMain
public class Main implements QuarkusApplication {

    private static final Logger LOG = Logger.getLogger(Main.class);
    private final SyncService syncService;
    private final StorageFactory storageFactory;

    public Main(SyncService syncService, StorageFactory storageFactory) {
        this.syncService = syncService;
        this.storageFactory = storageFactory;
    }

    public static void main(String[] args) {
        Quarkus.run(Main.class, args);
    }

    @Override
    public int run(String... args) {
        LOG.info("Olli AI application starting...");
        LOG.infof("Registered storage providers: %s", storageFactory.getSupportedProviders());
        Quarkus.waitForExit();
        return 0;
    }
}
