/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.models;

import java.util.List;
import pro.olli.ai.ChatMessage;

public class Conversation {

    public String id;
    public List<ChatMessage> messages;

    public Conversation(String id, List<ChatMessage> messages) {
        this.id = id;
        this.messages = messages;
    }
}
