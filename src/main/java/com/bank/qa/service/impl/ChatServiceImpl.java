package com.bank.qa.service.impl;

import com.bank.qa.model.*;
import com.bank.qa.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 聊天服務實作 (Chat Service Implementation)
 * <p>
 * 功能：
 * 作為整個問答系統的核心協調者，負責接收使用者問題，串接護欄檢查、Session 管理、FAQ 檢索與 LLM 生成。
 * <p>
 * 核心流程：
 * 1. 接收請求與 Session 初始化。
 * 2. 透過 {@link GuardrailService} 進行意圖分類 (Intent Classification)。
 * 3. 處理不相關問題與升級 (Escalation) 機制。
 * 4. 若問題相關，呼叫 {@link OllamaLlmService} 進行 Multi-Query 擴展。
 * 5. 呼叫 {@link FaqService} 進行混合檢索 (Hybrid Retrieval)。
 * 6. 最終呼叫 LLM 進行 RAG 增強生成並回傳 (支援 SSE 串流)。
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
    private static final double LUCENE_OVERRIDE_MIN_JACCARD = 0.18;
    private static final double LUCENE_OVERRIDE_MIN_QUERY_BIGRAM_COVERAGE = 0.6;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            8,
            32,
            60L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(200),
            new ThreadPoolExecutor.AbortPolicy());

    @PreDestroy
    public void shutdownExecutor() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 處理串流聊天 (Process Streaming Chat)
     * <p>
     * 功能：
     * 處理使用者的即時問答請求，並透過 Server-Sent Events (SSE) 逐步回傳處理狀態與 LLM 生成內容。
     * <p>
     * 詳細流程：
     * 1. 建立 SSE 連線並監聽完成/錯誤事件。
     * 2. 在 ThreadPool 中非同步執行處理邏輯，避免阻塞主要連線。
     * 3. **Session 處理**：建立或取得 Session，並發送 `session` 事件給前端。
     * 4. **意圖分類**：呼叫護欄服務判斷問題是否相關 (`intent`)。
     * - 若判定為不相關/不明確，且無法強制覆蓋 (keyword match)，則直接回傳護欄訊息並結束。
     * - 若判定需要人工升級，則回傳聯繫資訊。
     * 5. **查詢擴展**：若問題相關，呼叫 LLM 進行 Multi-Query 擴展 (`multiquery`)。
     * 6. **檢索**：利用擴展後的查詢進行 RAG 檢索 (`sources`)。
     * 7. **生成**：將檢索到的前幾筆 FAQ 作為 Context 送入 LLM 進行串流生成 (`chunk`)。
     * 8. **完成**：發送 `done` 事件並關閉 SSE 連線。
     *
     * @param question  使用者問題
     * @param sessionId Session ID (可選)
     * @param topN      檢索數量
     * @param emitter   SSE 發射器
     */
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
            logger.debug("SSE 連線超時或客戶端中止");
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // ignore
            }
        });
        emitter.onError(e -> {
            isCompleted.set(true);
            logger.debug("SSE 連線錯誤: {}: {}", e.getClass().getSimpleName(), e.getMessage());
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // ignore
            }
        });

        try {
            executor.execute(() -> {
                try {
                    final long t0 = System.nanoTime();

                    // 生成新 sessionId 如果沒有提供
                    boolean newSession = (sessionId == null || sessionId.isEmpty());
                    String sid = newSession ? UUID.randomUUID().toString() : sessionId;

                    final AtomicLong tIntentDone = new AtomicLong(0L);
                    final AtomicLong tMultiQueryDone = new AtomicLong(0L);
                    final AtomicLong tRetrievalDone = new AtomicLong(0L);
                    final AtomicLong tFirstChunk = new AtomicLong(0L);

                    if (isCompleted.get())
                        return;

                    // 發送 session 資訊
                    sendSseEvent(emitter, "session", Map.of(
                            "type", "session",
                            "sessionId", sid,
                            "newSession", newSession));

                    if (isCompleted.get())
                        return;

                    sendSseEvent(emitter, "thinking", Map.of(
                            "type", "thinking",
                            "message", "判斷問題類型中..."));

                    // === 安全護欄：意圖分類 ===
                    IntentResult intentResult = guardrailService.classifyIntent(question);
                    tIntentDone.set(System.nanoTime());

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
                    if (intentResult.isUnrelated() || intentResult.isUnclear()) {
                        List<Faq> quickHits = faqService.searchLuceneOnly(question, 3);
                        boolean overrideGuardrail = shouldOverrideGuardrail(question, quickHits);

                        if (!overrideGuardrail) {
                            if (intentResult.isUnrelated()) {
                                handleUnrelatedQuestion(emitter, session, intentResult, isCompleted);
                                return;
                            }
                            handleUnclearQuestion(emitter, session, intentResult, isCompleted);
                            return;
                        }
                    }

                    // === 問題相關，重置護欄計數器並繼續 RAG 流程 ===
                    session.resetUnrelatedCount();

                    sendSseEvent(emitter, "thinking", Map.of(
                            "type", "thinking",
                            "message", "整理關鍵字中..."));

                    // 進行 Multi-Query 檢索
                    MultiQueryResult multiQuery = llmService.expandQuery(question);
                    tMultiQueryDone.set(System.nanoTime());

                    // 發送 Multi-Query 資訊
                    sendSseEvent(emitter, "multiquery", Map.of(
                            "type", "multiquery",
                            "original", multiQuery.original(),
                            "keyword", multiQuery.keyword(),
                            "colloquial", multiQuery.colloquial()));

                    if (isCompleted.get())
                        return;

                    sendSseEvent(emitter, "thinking", Map.of(
                            "type", "thinking",
                            "message", "搜尋資料中..."));

                    // 進行檢索
                    RagSearchResult result = faqService.searchRagWithMultiQuery(question, multiQuery, sid, topN);
                    tRetrievalDone.set(System.nanoTime());

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
                                    if (tFirstChunk.get() == 0L) {
                                        tFirstChunk.compareAndSet(0L, System.nanoTime());
                                    }
                                    fullResponse.append(chunk);
                                    sendSseEvent(emitter, "chunk", Map.of(
                                            "type", "chunk",
                                            "content", chunk));
                                } catch (Exception e) {
                                    isCompleted.set(true);
                                    logger.debug("發送 chunk 失敗（客戶端可能已斷線）");
                                    try {
                                        emitter.complete();
                                    } catch (Exception ignored) {
                                        // ignore
                                    }
                                }
                            },
                            isCompleted::get);

                    // 檢查 LLM 是否回答「無法回答」
                    handleLlmResponse(emitter, session, fullResponse.toString(), isCompleted);

                    // 發送完成訊號
                    if (!isCompleted.get()) {
                        sendSseEvent(emitter, "done", Map.of("type", "done"));
                        emitter.complete();
                    }

                    long tEnd = System.nanoTime();
                    long intentMs = tIntentDone.get() == 0L ? -1 : (tIntentDone.get() - t0) / 1_000_000;
                    long multiQueryMs = (tMultiQueryDone.get() == 0L || tIntentDone.get() == 0L)
                            ? -1
                            : (tMultiQueryDone.get() - tIntentDone.get()) / 1_000_000;
                    long retrievalMs = (tRetrievalDone.get() == 0L || tMultiQueryDone.get() == 0L)
                            ? -1
                            : (tRetrievalDone.get() - tMultiQueryDone.get()) / 1_000_000;
                    long firstChunkMs = tFirstChunk.get() == 0L ? -1 : (tFirstChunk.get() - t0) / 1_000_000;
                    long totalMs = (tEnd - t0) / 1_000_000;

                    logger.info(
                            "PERF(stream) sid={} intentMs={} multiQueryMs={} retrievalMs={} firstChunkMs={} totalMs={} topN={}",
                            sid, intentMs, multiQueryMs, retrievalMs, firstChunkMs, totalMs, topN);

                } catch (Exception e) {
                    handleStreamingError(emitter, e, isCompleted);
                }
            });
        } catch (RejectedExecutionException e) {
            isCompleted.set(true);
            logger.warn("Streaming 請求被拒絕（執行緒池已滿）");
            try {
                sendSseEvent(emitter, "error", Map.of("type", "error", "message", "系統忙碌，請稍後再試"));
            } catch (Exception ex) {
                // ignore
            }
            emitter.completeWithError(e);
        }
    }

    /**
     * 處理一般聊天 (Process Normal Chat - Non-Streaming)
     * <p>
     * 功能：
     * 處理非串流的問答請求，執行完整的 RAG 流程後一次性回傳結果。這通常用於測試或不支援 SSE 的客戶端。
     * <p>
     * 流程：
     * 1. 意圖分類：同 Streaming 流程，判斷是否相關。
     * 2. 護欄處理：若不相關/需升級，直接回傳預設回應。
     * 3. 檢索與生成：執行 RAG 檢索，並等待 LLM 生成完整回答後才回傳。
     * 4. 效能記錄：紀錄各階段耗時。
     *
     * @param question  使用者問題
     * @param sessionId Session ID
     * @param topN      檢索數量
     * @return ChatResult 包含完整回答與來源
     */
    @Override
    public ChatResult processChat(String question, String sessionId, int topN) {
        final long t0 = System.nanoTime();
        boolean newSession = (sessionId == null || sessionId.isEmpty());
        String sid = newSession ? UUID.randomUUID().toString() : sessionId;

        // 意圖分類
        IntentResult intentResult = guardrailService.classifyIntent(question);
        final long tIntentDone = System.nanoTime();

        ChatSession session = sessionService.getOrCreateSession(sid);

        // 允許「疑似追問」在 UNCLEAR/UNRELATED 時仍走 RAG（由 A/B 檢索品質決定是否套用上下文）
        if (intentResult.isUnrelated() || intentResult.isUnclear()) {
            List<Faq> quickHits = faqService.searchLuceneOnly(question, 3);
            boolean overrideGuardrail = shouldOverrideGuardrail(question, quickHits);
            if (!overrideGuardrail) {
                session.incrementUnrelatedCount();

                String message = guardrailService.shouldEscalate(session)
                        ? guardrailService.getContactInfo()
                        : intentResult.message();

                long totalMs = (System.nanoTime() - t0) / 1_000_000;
                logger.info("PERF(nonstream) sid={} intentMs={} totalMs={} topN={} guardrail=true",
                        sid, (tIntentDone - t0) / 1_000_000, totalMs, topN);
                return new ChatResult(sid, newSession, intentResult.intent(), message, List.of(), null);
            }
        }

        session.resetUnrelatedCount();

        RagSearchResult result = faqService.searchRag(question, sid, topN);

        long totalMs = (System.nanoTime() - t0) / 1_000_000;
        logger.info("PERF(nonstream) sid={} intentMs={} totalMs={} topN={} sources={}",
                sid, (tIntentDone - t0) / 1_000_000, totalMs, topN,
                result.getSources() != null ? result.getSources().size() : 0);

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

        String response = guardrailService.shouldEscalate(session)
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

        String response = guardrailService.shouldEscalate(session)
                ? guardrailService.getContactInfo()
                : (intentResult.message() != null ? intentResult.message() : "請問您想了解的是關於交易、發票還是額度方面的問題呢？");

        sendSseEvent(emitter, "chunk", Map.of("type", "chunk", "content", response));
        sendSseEvent(emitter, "sources", Map.of("type", "sources", "sources", List.of(), "count", 0));
        sendSseEvent(emitter, "done", Map.of("type", "done"));
        emitter.complete();
    }

    private void handleLlmResponse(SseEmitter emitter, ChatSession session,
            String responseText, AtomicBoolean isCompleted) throws Exception {
        if (responseText.contains("無法回答") || responseText.contains("不在我的知識範圍")
                || responseText.contains("不在知識範圍") || responseText.contains("不在本系統服務範圍")) {
            session.incrementUnrelatedCount();
            logger.info("LLM 回應無法回答，護欄計數器: {}", session.getConsecutiveUnrelatedCount());

            if (guardrailService.shouldEscalate(session) && !isCompleted.get()) {
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

    /**
     * 判斷是否覆蓋護欄 (Override Guardrail Check)
     * <p>
     * 功能：
     * 即使 LLM 判定意圖為不相關，若使用者輸入與知識庫中的 FAQ 高度重疊（關鍵字匹配或 Jaccard 相似度高），
     * 則強制視為相關問題，避免 LLM 誤判導致拒絕回答。
     * <p>
     * 流程：
     * 1. 正規化字串 (去標點、轉小寫)。
     * 2. 檢查包含關係 (Contains)。
     * 3. 計算 Jaccard Similarity (Bigrams)，若 > 0.18 則覆蓋。
     * 4. 計算 Query Bigram Coverage，若 > 0.6 則覆蓋。
     */
    private static boolean shouldOverrideGuardrail(String query, List<Faq> quickHits) {
        if (query == null || query.trim().isEmpty() || quickHits == null || quickHits.isEmpty()) {
            return false;
        }
        String q = normalizeForOverlap(query);
        if (q.isEmpty()) {
            return false;
        }

        Faq top = quickHits.get(0);
        String doc = normalizeForOverlap((top.getQuestion() == null ? "" : top.getQuestion()) + " "
                + (top.getAnswer() == null ? "" : top.getAnswer()));
        if (doc.isEmpty()) {
            return false;
        }

        // 對短 query：只要字面命中就可視為相關（避免 Jaccard 因 doc 太長而被稀釋）
        if (doc.contains(q) || q.contains(doc)) {
            return true;
        }

        double jac = jaccardCharBigrams(q, doc);
        if (jac >= LUCENE_OVERRIDE_MIN_JACCARD) {
            return true;
        }

        // 針對關鍵詞/短句：以 query bigram 覆蓋率判斷
        double coverage = queryBigramCoverage(q, doc);
        return coverage >= LUCENE_OVERRIDE_MIN_QUERY_BIGRAM_COVERAGE;
    }

    private static String normalizeForOverlap(String s) {
        if (s == null) {
            return "";
        }
        String t = s.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (Character.getType(c) == Character.OTHER_PUNCTUATION
                    || Character.getType(c) == Character.DASH_PUNCTUATION
                    || Character.getType(c) == Character.START_PUNCTUATION
                    || Character.getType(c) == Character.END_PUNCTUATION
                    || Character.getType(c) == Character.CONNECTOR_PUNCTUATION
                    || Character.getType(c) == Character.INITIAL_QUOTE_PUNCTUATION
                    || Character.getType(c) == Character.FINAL_QUOTE_PUNCTUATION) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static double jaccardCharBigrams(String a, String b) {
        if (a.length() < 2 || b.length() < 2) {
            return 0.0;
        }

        Set<String> sa = new HashSet<>();
        for (int i = 0; i + 1 < a.length(); i++) {
            sa.add(a.substring(i, i + 2));
        }

        Set<String> sb = new HashSet<>();
        for (int i = 0; i + 1 < b.length(); i++) {
            sb.add(b.substring(i, i + 2));
        }

        if (sa.isEmpty() || sb.isEmpty()) {
            return 0.0;
        }

        int intersect = 0;
        if (sa.size() <= sb.size()) {
            for (String x : sa) {
                if (sb.contains(x)) {
                    intersect++;
                }
            }
        } else {
            for (String x : sb) {
                if (sa.contains(x)) {
                    intersect++;
                }
            }
        }

        int union = sa.size() + sb.size() - intersect;
        return union <= 0 ? 0.0 : (double) intersect / (double) union;
    }

    private static double queryBigramCoverage(String queryNorm, String docNorm) {
        if (queryNorm == null || docNorm == null || queryNorm.length() < 2 || docNorm.length() < 2) {
            return 0.0;
        }

        Set<String> q = new HashSet<>();
        for (int i = 0; i + 1 < queryNorm.length(); i++) {
            q.add(queryNorm.substring(i, i + 2));
        }
        if (q.isEmpty()) {
            return 0.0;
        }

        Set<String> d = new HashSet<>();
        for (int i = 0; i + 1 < docNorm.length(); i++) {
            d.add(docNorm.substring(i, i + 2));
        }
        if (d.isEmpty()) {
            return 0.0;
        }

        int hit = 0;
        for (String bg : q) {
            if (d.contains(bg)) {
                hit++;
            }
        }
        return (double) hit / (double) q.size();
    }
}
