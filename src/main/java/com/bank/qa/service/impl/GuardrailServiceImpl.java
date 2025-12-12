package com.bank.qa.service.impl;

import com.bank.qa.model.ChatSession;
import com.bank.qa.service.GuardrailService;
import com.bank.qa.model.IntentResult;
import com.bank.qa.service.OllamaLlmService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 護欄服務實作
 * 處理意圖分類、護欄回應、升級邏輯
 */
@Service
public class GuardrailServiceImpl implements GuardrailService {

    private static final Logger logger = LoggerFactory.getLogger(GuardrailServiceImpl.class);

    @Autowired
    private OllamaLlmService llmService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${guardrail.escalate-after:3}")
    private int escalateAfterCount;

    @Value("${guardrail.contact-name:李小姐}")
    private String contactName;

    @Value("${guardrail.contact-phone:(02)2883-4228 #6633}")
    private String contactPhone;

    @Value("${guardrail.contact-email:lara.li@vteamsystem.com.tw}")
    private String contactEmail;

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

    @Override
    public boolean shouldEscalate(ChatSession session) {
        return session.getConsecutiveUnrelatedCount() >= escalateAfterCount;
    }

    @Override
    public String getContactInfo() {
        return String.format(
                "抱歉，該問題請與%s聯繫：\n電話：%s\nEmail：%s",
                contactName, contactPhone, contactEmail);
    }
}
