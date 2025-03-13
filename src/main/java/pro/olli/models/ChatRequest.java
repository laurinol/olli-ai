/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.models;

import java.util.List;
import pro.olli.ai.ChatMessage;

public class ChatRequest {

    public String prompt;
    public List<ChatMessage> history;
}
