/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */

package pro.olli.ai;

import io.smallrye.mutiny.Uni;

import java.util.List;

public interface QueryService {
    Uni<String> query(String prompt, List<ChatMessage> history); // New for chat
}
