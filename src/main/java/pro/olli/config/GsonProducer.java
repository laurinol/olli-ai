/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
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
