/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.ai;

public class ChatMessage {
    public String role; // "user" or "assistant"
    public String content;

    public ChatMessage() {} // Default constructor for JSON deserialization

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }
}
