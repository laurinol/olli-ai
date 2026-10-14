/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */

package pro.olli.config;

import com.google.gson.Gson;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class GsonProducer {

    @Produces
    @ApplicationScoped
    public Gson produceGson() {
        return new Gson();
    }
}
