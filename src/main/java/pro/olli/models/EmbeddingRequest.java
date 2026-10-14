/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */

package pro.olli.models;

public class EmbeddingRequest {
    public String model;
    public String prompt;

    public EmbeddingRequest(String model, String prompt) {
        this.model = model;
        this.prompt = prompt;
    }
}
