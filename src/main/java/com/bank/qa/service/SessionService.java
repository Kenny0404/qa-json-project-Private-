package com.bank.qa.service;

import com.bank.qa.model.ChatSession;

/**
 * Session 服務介面
 * 負責管理聊天 Session 的生命週期
 */
public interface SessionService {

    /**
     * 取得指定 Session
     *
     * @param sessionId Session ID
     * @return ChatSession 或 null
     */
    ChatSession getSession(String sessionId);

    /**
     * 取得或建立 Session
     *
     * @param sessionId Session ID
     * @return ChatSession（若不存在則建立新的）
     */
    ChatSession getOrCreateSession(String sessionId);

    /**
     * 清除指定 Session
     *
     * @param sessionId Session ID
     */
    void clearSession(String sessionId);

    /**
     * 清理所有過期的 Session
     */
    void cleanExpiredSessions();

    /**
     * 取得目前活躍的 Session 數量
     *
     * @return 活躍 Session 數量
     */
    int getActiveSessionCount();
}
