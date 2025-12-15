package com.bank.qa.service.impl;

import com.bank.qa.model.ChatSession;
import com.bank.qa.service.GuardrailService;
import com.bank.qa.model.IntentResult;
import com.bank.qa.service.OllamaLlmService;
import com.bank.qa.service.RuntimeConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 護欄服務實作 (Guardrail Service Implementation)
 * <p>
 * 功能：
 * 建構系統的第一道防線，透過 LLM 對使用者問題進行意圖分類 (Intent Classification)，
 * 攔截不相關 (Unrelated) 或不明確 (Unclear) 的問題，並提供升級 (Escalation) 機制。
 * <p>
 * 流程概述：
 * 1. 建構詳細的 System Prompt 描述業務範圍。
 * 2. 呼叫 LLM 獲取 JSON 格式的分類結果。
 * 3. 追蹤 Session 的連續失敗次數，決定是否觸發人工轉接。
 */
@Service
public class GuardrailServiceImpl implements GuardrailService {

    private static final Logger logger = LoggerFactory.getLogger(GuardrailServiceImpl.class);

    @Autowired
    private OllamaLlmService llmService;

    @Autowired
    private RuntimeConfigService runtimeConfigService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 意圖分類 (Classify Intent)
     * <p>
     * 功能：
     * 將使用者問題送入 LLM，判斷其是否屬於銀行 Factoring 業務範疇。
     * <p>
     * 流程：
     * 1. 組合 Prompt：明確定義什麼是「相關領域」（如發票管理、額度管理）與「不相關領域」（如信用卡、生活問題）。
     * 2. 呼叫 LLM：要求返回 JSON 格式，包含 `intent` (RELATED/UNRELATED/UNCLEAR) 與建議關鍵字。
     * 3. 解析結果：將 LLM 輸出的文字解析為 `IntentResult` 物件。若解析失敗則預設視為 RELATED 以免誤殺。
     *
     * @param question 使用者問題
     * @return IntentResult 意圖分類結果
     */
    @Override
    public IntentResult classifyIntent(String question) {
        String prompt = String.format(
                """
                        你是銀行業務 QA 系統的意圖分類器。判斷以下問題是否與銀行 Factoring（應收帳款融資）業務相關。

                        相關領域包括：
                        - 交易操作（標準型交易、非標準型交易、預支價金、買方還款）
                        - 發票管理（發票日、到期日、過期發票、發票修改）
                        - 額度管理（總約、附約、維持率、額度凍結）
                        - 流程操作（主管核准、資料修改、系統建檔、交易錯誤處理）
                        - 憑證/傳票/報表（傳票列印、憑證列印、查詢傳票、日結單/傳票相關）

                        不相關的問題包括：
                        - 統一發票中獎、兌獎
                        - 天氣、股票、一般生活問題
                        - 其他銀行業務（存款、信用卡、房貸）

                        問題：%s

                        返回 JSON 格式（suggestKeywords 給出 2-4 個相關的操作關鍵字供用戶選擇）：
                        {"intent": "RELATED" 或 "UNRELATED" 或 "UNCLEAR", "message": "給用戶的回應訊息", "suggestKeywords": ["關鍵字1", "關鍵字2", "關鍵字3"]}
                        """,
                question);

        try {
            String response = llmService.call(prompt, 0.1);
            return parseIntentResult(response);
        } catch (Exception e) {
            logger.error("意圖分類失敗: {}", e.getMessage());
            return new IntentResult("RELATED", null, List.of());
        }
    }

    /**
     * 解析意圖結果 (Parse Intent Result)
     * <p>
     * 功能：
     * 從 LLM 的回應字串中提取並解析 JSON 物件。
     * <p>
     * 流程：
     * 1. 使用字串定位 `{` 與 `}` 找到 JSON 區塊。
     * 2. 使用 Jackson ObjectMapper 解析 JSON。
     * 3. 提取 `intent`、`message` 與 `suggestKeywords` 欄位。
     */
    private IntentResult parseIntentResult(String response) {
        try {
            // 提取 JSON
            int start = response.indexOf('{');
            int end = response.lastIndexOf('}') + 1;
            if (start >= 0 && end > start) {
                String json = response.substring(start, end);
                JsonNode node = objectMapper.readTree(json);

                String intent = node.has("intent") ? node.get("intent").asText() : "RELATED";
                String message = node.has("message") ? node.get("message").asText() : null;

                List<String> keywords = new ArrayList<>();
                if (node.has("suggestKeywords") && node.get("suggestKeywords").isArray()) {
                    for (JsonNode kw : node.get("suggestKeywords")) {
                        keywords.add(kw.asText());
                    }
                }

                return new IntentResult(intent, message, keywords);
            }
        } catch (Exception e) {
            logger.warn("解析意圖結果失敗: {}", e.getMessage());
        }
        return new IntentResult("RELATED", null, List.of());
    }

    @Override
    public String handleGuardrail(ChatSession session, IntentResult intentResult) {
        session.incrementUnrelatedCount();

        if (shouldEscalate(session)) {
            return getContactInfo();
        }

        return intentResult.message() != null
                ? intentResult.message()
                : "抱歉，此問題不在本系統服務範圍內。";
    }

    /**
     * 判斷是否需要升級 (Check Escalation)
     * <p>
     * 功能：
     * 檢查使用者的連續失敗次數是否達到閾值，決定是否提供人工聯繫資訊。
     * <p>
     * 流程：
     * 1. 從 Session 取得 `consecutiveUnrelatedCount`。
     * 2. 從 `RuntimeConfigService` 取得設定的閾值 (`guardrailEscalateAfter`)。
     * 3. 比較兩者大小。
     *
     * @param session 當前對話 Session
     * @return true 若需要跳轉人工
     */
    @Override
    public boolean shouldEscalate(ChatSession session) {
        return session.getConsecutiveUnrelatedCount() >= runtimeConfigService.getGuardrailEscalateAfter();
    }

    @Override
    public String getContactInfo() {
        return String.format(
                "抱歉，該問題請與%s聯繫：\n電話：%s\nEmail：%s",
                runtimeConfigService.getGuardrailContactName(),
                runtimeConfigService.getGuardrailContactPhone(),
                runtimeConfigService.getGuardrailContactEmail());
    }
}
