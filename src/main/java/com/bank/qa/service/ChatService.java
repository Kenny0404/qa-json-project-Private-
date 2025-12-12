package com.bank.qa.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 聊天服務介面
 * 封裝 SSE 聊天流程邏輯
 */
public interface ChatService {

    /**
     * 處理 Streaming 聊天請求
     * 
     * @param question  使用者問題
     * @param sessionId Session ID（可選，如果為 null 則自動生成）
     * @param topN      返回 FAQ 數量
     * @param emitter   SSE 發送器
     */
    void processStreamingChat(String question, String sessionId, int topN, SseEmitter emitter);

    /**
     * 處理非 Streaming 聊天請求
     * 
     * @param question  使用者問題
     * @param sessionId Session ID（可選）
     * @param topN      返回 FAQ 數量
     * @return 聊天結果
     */
    ChatResult processChat(String question, String sessionId, int topN);

    /**
     * 聊天結果（非 Streaming）
     */
    record ChatResult(
            String sessionId,
            boolean newSession,
            String intent,
            String answer,
            java.util.List<com.bank.qa.model.Faq> sources,
            com.bank.qa.model.MultiQueryResult multiQuery) {
    }
}
