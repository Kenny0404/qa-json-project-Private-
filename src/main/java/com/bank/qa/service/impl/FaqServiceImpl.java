package com.bank.qa.service.impl;

import com.bank.qa.model.ChatSession;
import com.bank.qa.model.Faq;
import com.bank.qa.model.MultiQueryResult;
import com.bank.qa.model.RagSearchResult;
import com.bank.qa.service.FaqService;
import com.bank.qa.service.OllamaLlmService;
import com.bank.qa.service.SessionService;
import com.bank.qa.util.JsonLoader;
import com.bank.qa.util.VectorUtils;
import com.bank.qa.util.VectorUtils.BM25;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * FAQ 服務實作
 * 支援 Multi-Query + RRF 融合 + RAG 答案生成
 */
@Service
public class FaqServiceImpl implements FaqService {

    private static final Logger logger = LoggerFactory.getLogger(FaqServiceImpl.class);

    private List<Faq> faqList;
    private BM25 bm25;
    private List<String> faqDocuments;

    @Autowired
    private OllamaLlmService llmService;

    @Autowired
    private SessionService sessionService;

    // 從 application.properties 讀取配置
    @Value("${rag.default-top-n:5}")
    private int defaultTopN;

    @Value("${rag.retrieval-top-k:10}")
    private int retrievalTopK;

    @Value("${rag.rrf-k:60}")
    private int rrfK;

    @PostConstruct
    public void init() {
        faqList = JsonLoader.loadFaq("faq.json");
        logger.info("載入 {} 條 FAQ 資料", faqList.size());

        initBM25();

        logger.info("FAQ 服務初始化完成（RAG 模式），平均文檔長度: {}",
                String.format("%.1f", bm25.getAvgDocLength()));
    }

    private void initBM25() {
        faqDocuments = faqList.stream()
                .map(f -> f.getQuestion() + " " + f.getAnswer())
                .collect(Collectors.toList());

        bm25 = new BM25(1.5, 0.75);
        bm25.fit(faqDocuments);
    }

    @Override
    public RagSearchResult searchRag(String query, String sessionId, int topN) {
        cleanExpiredSessions();

        // 取得或建立 session
        ChatSession session = sessionId != null ? sessionService.getOrCreateSession(sessionId) : null;

        // 取得上下文
        String context = session != null ? session.getRecentContext(2) : "";

        // 記錄使用者訊息
        if (session != null) {
            session.addUserMessage(query);
        }

        // 1. Multi-Query Expansion
        MultiQueryResult multiQuery = llmService.expandQuery(query);
        logger.info("Multi-Query Expansion: original='{}', keyword='{}', colloquial='{}'",
                multiQuery.original(), multiQuery.keyword(), multiQuery.colloquial());

        // 2. 對每個查詢進行 BM25 檢索
        List<List<Integer>> rankings = new ArrayList<>();
        for (String q : multiQuery.toList()) {
            String searchQuery = context.isEmpty() ? q : q + " " + context;
            List<Integer> ranked = bm25.getRankedDocIds(searchQuery, retrievalTopK);
            rankings.add(ranked);
            logger.debug("Query '{}' 檢索到 {} 個結果", q, ranked.size());
        }

        // 3. RRF 融合
        Map<Integer, Double> rrfScores = VectorUtils.rrfFusionWithScores(rankings, rrfK);

        // 4. 取 Top-N 結果
        List<Faq> topFaqs = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                .limit(topN)
                .map(entry -> {
                    Faq faq = faqList.get(entry.getKey()).copy();
                    faq.setScore(entry.getValue());
                    return faq;
                })
                .collect(Collectors.toList());

        // 5. RAG 答案生成
        String generatedAnswer = null;
        if (!topFaqs.isEmpty() && llmService.isAvailable()) {
            List<String> contexts = topFaqs.stream()
                    .limit(3) // 取前 3 筆作為 context
                    .map(f -> String.format("【FAQ #%d】\n問：%s\n答：%s",
                            f.getId(), f.getQuestion(), f.getAnswer()))
                    .collect(Collectors.toList());

            generatedAnswer = llmService.generateAnswer(query, contexts);
        }

        // 記錄系統回應
        if (session != null && !topFaqs.isEmpty()) {
            String summary = topFaqs.stream()
                    .limit(3)
                    .map(f -> "Q" + f.getId())
                    .collect(Collectors.joining(", "));
            session.addSystemMessage("Found: " + summary);
            session.setLastContext(query);
        }

        logger.info("RAG 查詢 '{}' 找到 {} 筆結果，生成答案: {}",
                query, topFaqs.size(), generatedAnswer != null ? "是" : "否");

        return new RagSearchResult(generatedAnswer, topFaqs, multiQuery);
    }

    @Override
    public List<Faq> searchWithContext(String query, String context, int topN) {
        if (query == null || query.trim().isEmpty()) {
            return new ArrayList<>();
        }

        String queryText = context != null && !context.isEmpty() ? query + " " + context : query;

        double[] scores = bm25.normalizedScore(queryText);

        List<Faq> results = new ArrayList<>();
        for (int i = 0; i < faqList.size(); i++) {
            if (scores[i] > 0.01) {
                Faq faq = faqList.get(i).copy();
                faq.setScore(scores[i]);
                results.add(faq);
            }
        }

        results.sort(Comparator.comparingDouble(Faq::getScore).reversed());
        if (results.size() > topN) {
            results = results.subList(0, topN);
        }

        return results;
    }

    @Override
    public ChatSession getSession(String sessionId) {
        return sessionService.getSession(sessionId);
    }

    @Override
    public ChatSession getOrCreateSession(String sessionId) {
        return sessionService.getOrCreateSession(sessionId);
    }

    @Override
    public void clearSession(String sessionId) {
        sessionService.clearSession(sessionId);
    }

    /**
     * 清理過期 Session
     */
    private void cleanExpiredSessions() {
        sessionService.cleanExpiredSessions();
    }

    @Override
    public List<Faq> getAllFaq() {
        return faqList.stream()
                .map(Faq::copy)
                .collect(Collectors.toList());
    }

    @Override
    public int getFaqCount() {
        return faqList.size();
    }

    @Override
    public int getVocabSize() {
        return bm25.getDocCount();
    }

    @Override
    public int getActiveSessionCount() {
        return sessionService.getActiveSessionCount();
    }

    @Override
    public boolean isLlmAvailable() {
        return llmService.isAvailable();
    }
}
