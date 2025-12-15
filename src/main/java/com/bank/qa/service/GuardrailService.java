package com.bank.qa.service;

import com.bank.qa.model.ChatSession;
import com.bank.qa.model.IntentResult;

/**
 * 護欄服務介面
 * 負責意圖分類、護欄判斷、升級聯繫邏輯
 */
public interface GuardrailService {

    /**
     * 分類使用者問題的意圖
     *
     * @param question 使用者問題
     * @return 意圖分類結果
     */
    IntentResult classifyIntent(String question);

    /**
     * 處理護欄回應（包含升級邏輯）
     *
     * @param session      聊天 Session
     * @param intentResult 意圖分類結果
     * @return 護欄回應訊息
     */
    String handleGuardrail(ChatSession session, IntentResult intentResult);

    /**
     * 檢查是否應該升級到聯繫資訊
     *
     * @param session 聊天 Session
     * @return true 如果應該顯示聯繫資訊
     */
    boolean shouldEscalate(ChatSession session);

    /**
     * 取得聯繫資訊訊息
     */
    String getContactInfo();
}
