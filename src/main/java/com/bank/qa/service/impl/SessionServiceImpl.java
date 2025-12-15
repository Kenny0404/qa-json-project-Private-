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
 * Session 服務實作 (Session Service Implementation)
 * <p>
 * 功能：
 * 負責管理使用者的對話 Session 生命週期，包括建立、查詢、清除與過期清理。
 * <p>
 * 流程概述：
 * 1. 使用記憶體內的 ConcurrentHashMap 儲存所有活躍 Session (`sessions`)。
 * 2. 透過 `sessionId` 作為 Key 來存取對應的 `ChatSession` 物件。
 * 3. 提供定期清理機制 (`cleanExpiredSessions`)，移除超過閒置時間的 Session 以釋放記憶體。
 */
@Service
public class SessionServiceImpl implements SessionService {

    private static final Logger logger = LoggerFactory.getLogger(SessionServiceImpl.class);

    // 使用 Thread-Safe 的 Map 來儲存 Session，Key 為 sessionId
    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();

    // Session 超時時間（分鐘），預設 30 分鐘，可透過 application.properties 配置
    @Value("${session.timeout-minutes:30}")
    private int sessionTimeoutMinutes;

    /**
     * 取得 Session
     * <p>
     * 功能：根據 sessionId 獲取現有的 Session 物件。
     * <p>
     * 流程：
     * 1. 直接從 Map 中查詢。
     * 2. 若不存在則回傳 null。
     *
     * @param sessionId Session ID
     * @return ChatSession 物件，若不存在則為 null
     */
    @Override
    public ChatSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * 取得或建立 Session
     * <p>
     * 功能：獲取現有 Session，若不存在則建立一個新的。
     * <p>
     * 流程：
     * 1. 檢查 Map 中是否已有該 sessionId。
     * 2. 若有，直接回傳。
     * 3. 若無，使用 `ChatSession::new` 建構子建立新物件並存入 Map (Atomic 操作)。
     *
     * @param sessionId Session ID
     * @return ChatSession 物件 (保證不為 null)
     */
    @Override
    public ChatSession getOrCreateSession(String sessionId) {
        return sessions.computeIfAbsent(sessionId, ChatSession::new);
    }

    /**
     * 清除 Session
     * <p>
     * 功能：手動移除特定的 Session。
     * <p>
     * 流程：
     * 1. 從 Map 中移除指定 Key 的項目。
     * 2. 記錄 Log 訊息。
     *
     * @param sessionId 欲移除的 Session ID
     */
    @Override
    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
        logger.info("已清除 Session: {}", sessionId);
    }

    /**
     * 清理過期 Session
     * <p>
     * 功能：檢查並移除所有超過閒置時間的 Session。
     * <p>
     * 流程：
     * 1. 取得當前時間與計算超時門檻 (sessionTimeoutMinutes * 60 * 1000)。
     * 2. 遍歷 Map 中所有項目。
     * 3. 檢查每個 Session 的 `lastActiveAt` 是否早於 (當前時間 - 超時長度)。
     * 4. 移除符合條件的 Session。
     * 5. 若有移除，記錄清理數量 Log。
     */
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

    /**
     * 取得活躍 Session 數量
     * <p>
     * 功能：清理過期 Session 後，回傳當前剩餘的 Session 總數。
     * <p>
     * 流程：
     * 1. 先呼叫 `cleanExpiredSessions()` 執行清理動作，確保數據準確。
     * 2. 回傳 Map 的大小 (`size()`)。
     *
     * @return 活躍 Session 數量
     */
    @Override
    public int getActiveSessionCount() {
        cleanExpiredSessions();
        return sessions.size();
    }
}
