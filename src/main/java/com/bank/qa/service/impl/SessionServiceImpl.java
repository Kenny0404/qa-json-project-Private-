package com.bank.qa.service.impl;

import com.bank.qa.model.ChatSession;
import com.bank.qa.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session 服務實作
 * 管理聊天 Session 的建立、取得、清除和過期處理
 */
@Service
public class SessionServiceImpl implements SessionService {

    private static final Logger logger = LoggerFactory.getLogger(SessionServiceImpl.class);

    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();

    @Value("${session.timeout-minutes:30}")
    private int sessionTimeoutMinutes;

    @Override
    public ChatSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    @Override
    public ChatSession getOrCreateSession(String sessionId) {
        return sessions.computeIfAbsent(sessionId, ChatSession::new);
    }

    @Override
    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
        logger.info("已清除 Session: {}", sessionId);
    }

    @Override
    public void cleanExpiredSessions() {
        long now = System.currentTimeMillis();
        long sessionTimeoutMs = sessionTimeoutMinutes * 60L * 1000L;
        int before = sessions.size();
        sessions.entrySet().removeIf(entry -> now - entry.getValue().getLastActiveAt() > sessionTimeoutMs);
        int removed = before - sessions.size();
        if (removed > 0) {
            logger.info("已清理 {} 個過期 Session", removed);
        }
    }

    @Override
    public int getActiveSessionCount() {
        cleanExpiredSessions();
        return sessions.size();
    }
}
