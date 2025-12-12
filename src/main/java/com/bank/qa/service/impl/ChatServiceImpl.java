package com.bank.qa.service.impl;

import com.bank.qa.model.*;
import com.bank.qa.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聊天服務實作
 * 封裝 SSE 聊天流程邏輯
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatServiceImpl.class);

    @Autowired
    private FaqService faqService;

    @Autowired
    private GuardrailService guardrailService;

    @Autowired
    private OllamaLlmService llmService;

    @Autowired
    private SessionService sessionService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Override
    public void processStreamingChat(String question, String sessionId, int topN, SseEmitter emitter) {
        AtomicBoolean isCompleted = new AtomicBoolean(false);

        // 監聽客戶端斷線
        emitter.onCompletion(() -> {
            isCompleted.set(true);
            logger.debug("SSE 連線完成");
        });
        emitter.onTimeout(() -> {
            isCompleted.set(true);
            logger.warn("SSE 連線超時");
        });
        emitter.onError(e -> {
            isCompleted.set(true);
            logger.debug("SSE 連線錯誤: {}", e.getMessage());
        });

        executor.execute(() -> {
            try {
                // 生成新 sessionId 如果沒有提供
                boolean newSession = (sessionId == null || sessionId.isEmpty());
                String sid = newSession ? UUID.randomUUID().toString() : sessionId;

                if (isCompleted.get())
                    return;

                // 發送 session 資訊
                sendSseEvent(emitter, "session", Map.of(
                        "type", "session",
                        "sessionId", sid,
                        "newSession", newSession));

                if (isCompleted.get())
                    return;

                // === 安全護欄：意圖分類 ===
                IntentResult intentResult = guardrailService.classifyIntent(question);

                if (isCompleted.get())
                    return;

                // 發送意圖分類結果
                Map<String, Object> intentData = new HashMap<>();
                intentData.put("type", "intent");
                intentData.put("intent", intentResult.intent());
                intentData.put("message", intentResult.message());
                intentData.put("suggestKeywords", intentResult.suggestKeywords());
                sendSseEvent(emitter, "intent", intentData);

                // 取得或建立 Session
                ChatSession session = sessionService.getOrCreateSession(sid);

                // 如果問題不相關，直接返回護欄回應
                if (intentResult.isUnrelated()) {
                    handleUnrelatedQuestion(emitter, session, intentResult, isCompleted);
                    return;
                }

                // 如果問題不明確，提供釐清建議
                if (intentResult.isUnclear()) {
                    handleUnclearQuestion(emitter, session, intentResult, isCompleted);
                    return;
                }

                // === 問題相關，重置護欄計數器並繼續 RAG 流程 ===
                session.resetUnrelatedCount();

                // 進行 Multi-Query 檢索
                MultiQueryResult multiQuery = llmService.expandQuery(question);

                // 發送 Multi-Query 資訊
                sendSseEvent(emitter, "multiquery", Map.of(
                        "type", "multiquery",
                        "original", multiQuery.original(),
                        "keyword", multiQuery.keyword(),
                        "colloquial", multiQuery.colloquial()));

                if (isCompleted.get())
                    return;

                // 智慧上下文：判斷新問題是否與前一個問題相關
                handleSmartContext(session, question);

                // 進行檢索
                RagSearchResult result = faqService.searchRag(question, sid, topN);

                // 發送來源 FAQ
                sendSseEvent(emitter, "sources", Map.of(
                        "type", "sources",
                        "sources", result.getSources(),
                        "count", result.getSources().size()));

                if (isCompleted.get())
                    return;

                // 發送心跳（通知前端 LLM 正在思考）
                sendSseEvent(emitter, "thinking", Map.of(
                        "type", "thinking",
                        "message", "正在生成回答..."));

                // 追蹤完整回應內容
                StringBuilder fullResponse = new StringBuilder();

                // Streaming 生成答案
                llmService.generateAnswerStreaming(question,
                        result.getSources().stream()
                                .limit(3)
                                .map(f -> String.format("【FAQ #%d】\n問：%s\n答：%s",
                                        f.getId(), f.getQuestion(), f.getAnswer()))
                                .toList(),
                        chunk -> {
                            if (isCompleted.get())
                                return;
                            try {
                                fullResponse.append(chunk);
                                sendSseEvent(emitter, "chunk", Map.of(
                                        "type", "chunk",
                                        "content", chunk));
                            } catch (Exception e) {
                                isCompleted.set(true);
                                logger.debug("發送 chunk 失敗（客戶端可能已斷線）");
                            }
                        });

                // 檢查 LLM 是否回答「無法回答」
                handleLlmResponse(emitter, session, fullResponse.toString(), isCompleted);

                // 發送完成訊號
                if (!isCompleted.get()) {
                    sendSseEvent(emitter, "done", Map.of("type", "done"));
                    emitter.complete();
                }

            } catch (Exception e) {
                handleStreamingError(emitter, e, isCompleted);
            }
        });
    }

    @Override
    public ChatResult processChat(String question, String sessionId, int topN) {
        boolean newSession = (sessionId == null || sessionId.isEmpty());
        String sid = newSession ? UUID.randomUUID().toString() : sessionId;

        // 意圖分類
        IntentResult intentResult = guardrailService.classifyIntent(question);

        if (intentResult.isUnrelated() || intentResult.isUnclear()) {
            ChatSession session = sessionService.getOrCreateSession(sid);
            session.incrementUnrelatedCount();

            String message = session.shouldEscalateToContact()
                    ? guardrailService.getContactInfo()
                    : intentResult.message();

            return new ChatResult(sid, newSession, intentResult.intent(), message, List.of(), null);
        }

        // RAG 搜尋
        RagSearchResult result = faqService.searchRag(question, sid, topN);

        return new ChatResult(
                sid,
                newSession,
                intentResult.intent(),
                result.getAnswer(),
                result.getSources(),
                result.getMultiQuery());
    }

    // ========== 私有輔助方法 ==========

    private void handleUnrelatedQuestion(SseEmitter emitter, ChatSession session,
            IntentResult intentResult, AtomicBoolean isCompleted) throws Exception {
        session.incrementUnrelatedCount();

        String response = session.shouldEscalateToContact()
                ? guardrailService.getContactInfo()
                : (intentResult.message() != null ? intentResult.message() : "抱歉，此問題不在本系統服務範圍內。");

        sendSseEvent(emitter, "chunk", Map.of("type", "chunk", "content", response));
        sendSseEvent(emitter, "sources", Map.of("type", "sources", "sources", List.of(), "count", 0));
        sendSseEvent(emitter, "done", Map.of("type", "done"));
        emitter.complete();
    }

    private void handleUnclearQuestion(SseEmitter emitter, ChatSession session,
            IntentResult intentResult, AtomicBoolean isCompleted) throws Exception {
        session.incrementUnrelatedCount();

        String response = session.shouldEscalateToContact()
                ? guardrailService.getContactInfo()
                : (intentResult.message() != null ? intentResult.message() : "請問您想了解的是關於交易、發票還是額度方面的問題呢？");

        sendSseEvent(emitter, "chunk", Map.of("type", "chunk", "content", response));
        sendSseEvent(emitter, "sources", Map.of("type", "sources", "sources", List.of(), "count", 0));
        sendSseEvent(emitter, "done", Map.of("type", "done"));
        emitter.complete();
    }

    private void handleSmartContext(ChatSession session, String question) {
        String previousQuery = session.getLastContext();
        if (previousQuery != null && !previousQuery.isEmpty()) {
            boolean queryRelated = llmService.isQueryRelated(question, previousQuery);
            if (!queryRelated) {
                session.setLastContext(null);
                logger.info("智慧上下文：問題不相關，已清除上下文");
            }
        }
    }

    private void handleLlmResponse(SseEmitter emitter, ChatSession session,
            String responseText, AtomicBoolean isCompleted) throws Exception {
        if (responseText.contains("無法回答") || responseText.contains("不在我的知識範圍")
                || responseText.contains("不在知識範圍") || responseText.contains("不在本系統服務範圍")) {
            session.incrementUnrelatedCount();
            logger.info("LLM 回應無法回答，護欄計數器: {}", session.getConsecutiveUnrelatedCount());

            if (session.shouldEscalateToContact() && !isCompleted.get()) {
                String contactInfo = "\n\n---\n" + guardrailService.getContactInfo();
                sendSseEvent(emitter, "chunk", Map.of("type", "chunk", "content", contactInfo));
            }
        } else {
            session.resetUnrelatedCount();
        }
    }

    private void handleStreamingError(SseEmitter emitter, Exception e, AtomicBoolean isCompleted) {
        if (!isCompleted.get()) {
            logger.error("Streaming 搜尋錯誤: {}", e.getMessage());
            try {
                sendSseEvent(emitter, "error", Map.of("type", "error", "message", e.getMessage()));
            } catch (Exception ex) {
                // ignore
            }
            emitter.completeWithError(e);
        }
    }

    private void sendSseEvent(SseEmitter emitter, String eventName, Map<String, Object> data) throws Exception {
        emitter.send(SseEmitter.event()
                .name(eventName)
                .data(objectMapper.writeValueAsString(data)));
    }
}
