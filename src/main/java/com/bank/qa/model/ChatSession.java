package com.bank.qa.model;

/**
 * 對話 Session 模型
 * 用於記錄多輪對話歷史
 */
public class ChatSession {

    private String sessionId;
    private long createdAt;
    private long lastActiveAt;
    private int consecutiveUnrelatedCount; // 連續護欄回應計數器
    private final Object lock = new Object();

    public ChatSession(String sessionId) {
        this.sessionId = sessionId;
        this.createdAt = System.currentTimeMillis();
        this.lastActiveAt = this.createdAt;
        this.consecutiveUnrelatedCount = 0;
    }

    // Getters and Setters
    public String getSessionId() {
        return sessionId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getLastActiveAt() {
        synchronized (lock) {
            return lastActiveAt;
        }
    }

    public int getConsecutiveUnrelatedCount() {
        synchronized (lock) {
            return consecutiveUnrelatedCount;
        }
    }

    public void incrementUnrelatedCount() {
        synchronized (lock) {
            this.consecutiveUnrelatedCount++;
            lastActiveAt = System.currentTimeMillis();
        }
    }

    public void resetUnrelatedCount() {
        synchronized (lock) {
            this.consecutiveUnrelatedCount = 0;
            lastActiveAt = System.currentTimeMillis();
        }
    }

    public boolean shouldEscalateToContact() {
        synchronized (lock) {
            return this.consecutiveUnrelatedCount >= 3;
        }
    }
}
