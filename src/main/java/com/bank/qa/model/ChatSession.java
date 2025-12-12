package com.bank.qa.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 對話 Session 模型
 * 用於記錄多輪對話歷史
 */
public class ChatSession {

    private String sessionId;
    private List<ChatMessage> messages;
    private long createdAt;
    private long lastActiveAt;
    private String lastContext; // 上一次查詢的上下文
    private int consecutiveUnrelatedCount; // 連續護欄回應計數器

    public ChatSession(String sessionId) {
        this.sessionId = sessionId;
        this.messages = new ArrayList<>();
        this.createdAt = System.currentTimeMillis();
        this.lastActiveAt = this.createdAt;
        this.consecutiveUnrelatedCount = 0;
    }

    /**
     * 新增使用者訊息
     */
    public void addUserMessage(String content) {
        messages.add(new ChatMessage("user", content));
        lastActiveAt = System.currentTimeMillis();
    }

    /**
     * 新增系統回應
     */
    public void addSystemMessage(String content) {
        messages.add(new ChatMessage("system", content));
        lastActiveAt = System.currentTimeMillis();
    }

    /**
     * 取得最近 N 條訊息的上下文
     */
    public String getRecentContext(int n) {
        if (messages.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, messages.size() - n);
        for (int i = start; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if ("user".equals(msg.getRole())) {
                sb.append(msg.getContent()).append(" ");
            }
        }
        return sb.toString().trim();
    }

    // Getters and Setters
    public String getSessionId() {
        return sessionId;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getLastActiveAt() {
        return lastActiveAt;
    }

    public String getLastContext() {
        return lastContext;
    }

    public void setLastContext(String lastContext) {
        this.lastContext = lastContext;
    }

    public int getConsecutiveUnrelatedCount() {
        return consecutiveUnrelatedCount;
    }

    public void incrementUnrelatedCount() {
        this.consecutiveUnrelatedCount++;
    }

    public void resetUnrelatedCount() {
        this.consecutiveUnrelatedCount = 0;
    }

    public boolean shouldEscalateToContact() {
        return this.consecutiveUnrelatedCount >= 3;
    }

    /**
     * 對話訊息
     */
    public static class ChatMessage {
        private String role;
        private String content;
        private long timestamp;

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
            this.timestamp = System.currentTimeMillis();
        }

        public String getRole() {
            return role;
        }

        public String getContent() {
            return content;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }
}
