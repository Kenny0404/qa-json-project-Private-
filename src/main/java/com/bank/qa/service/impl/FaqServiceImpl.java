package com.bank.qa.service.impl;

import com.bank.qa.model.ChatSession;
import com.bank.qa.model.Faq;
import com.bank.qa.model.MultiQueryResult;
import com.bank.qa.model.RagSearchResult;
import com.bank.qa.repository.FaqRepository;
import com.bank.qa.service.FaqService;
import com.bank.qa.service.OllamaLlmService;
import com.bank.qa.service.RuntimeConfigService;
import com.bank.qa.service.SessionService;
import com.bank.qa.util.LuceneFaqIndex;
import com.bank.qa.util.VectorUtils;
import com.bank.qa.util.VectorUtils.BM25;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * FAQ 服務實作 (FAQ Service Implementation)
 * <p>
 * 功能：
 * 負責 FAQ 資料的索引管理與檢索核心邏輯，實現了混合檢索 (Hybrid Retrieval) 策略。
 * <p>
 * 核心機制：
 * 1. **索引 (Indexing)**：啟動時將 FAQ 資料載入記憶體，並建立 BM25 與 Lucene In-Memory Index。
 * 2. **檢索 (Retrieval)**：
 * - 支援 Multi-Query (多查詢) 擴展。
 * - 使用 Lucene 進行初步檢索。
 * - 使用 Reciprocal Rank Fusion (RRF) 演算法融合多個查詢的結果。
 * - 實作候選重排序 (Reranking) 機制，根據字串相似度 (Jaccard) 給予額外加分。
 * 3. **生成 (Generation)**：將檢索到的最佳 FAQ 作為 Context，交由 LLM 生成最終回答。
 */
@Service
public class FaqServiceImpl implements FaqService {

    private static final Logger logger = LoggerFactory.getLogger(FaqServiceImpl.class);

    private volatile List<Faq> faqList;
    private volatile BM25 bm25;
    private volatile List<String> faqDocuments;
    private volatile LuceneFaqIndex luceneIndex;

    private static final int RERANK_CANDIDATE_MULTIPLIER = 10;
    private static final int RERANK_MAX_CANDIDATES = 50;
    private static final double RERANK_EXACTISH_JACCARD_THRESHOLD = 0.35;
    private static final double RERANK_EXACTISH_BONUS = 0.10;
    private static final double RERANK_SOFT_BONUS_SCALE = 0.01;

    @Autowired
    private OllamaLlmService llmService;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private FaqRepository faqRepository;

    @Autowired
    private RuntimeConfigService runtimeConfigService;

    @PostConstruct
    public void init() {
        reindexFromRepository();
    }

    public void reindexFromRepository() {
        List<Faq> list = faqRepository.list();
        if (list == null) {
            list = List.of();
        }

        // Build new indexes first, then swap references (avoid partially-initialized
        // state)
        List<String> docs = buildDocuments(list);
        BM25 newBm25 = new BM25(1.5, 0.75);
        newBm25.fit(docs);

        LuceneFaqIndex newLucene = new LuceneFaqIndex();
        newLucene.build(list);

        this.faqList = new ArrayList<>(list);
        this.faqDocuments = docs;
        this.bm25 = newBm25;
        this.luceneIndex = newLucene;

        logger.info("載入 {} 條 FAQ 資料", this.faqList.size());
        logger.info("FAQ 服務初始化完成（RAG 模式），平均文檔長度: {}",
                String.format("%.1f", this.bm25.getAvgDocLength()));
    }

    private List<String> cleanQueries(List<String> queries) {
        if (queries == null || queries.isEmpty()) {
            return List.of();
        }
        return queries.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    private static List<String> buildDocuments(List<Faq> list) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        return list.stream()
                .map(f -> (f.getCategory() == null ? "" : f.getCategory())
                        + " "
                        + (f.getModule() == null ? "" : f.getModule())
                        + " "
                        + (f.getSource() == null ? "" : f.getSource())
                        + " "
                        + (f.getQuestion() == null ? "" : f.getQuestion())
                        + " "
                        + (f.getAnswer() == null ? "" : f.getAnswer()))
                .collect(Collectors.toList());
    }

    /**
     * RAG 搜尋 - 主入口 (Main RAG Search)
     * <p>
     * 功能：
     * 執行完整的 RAG 檢索流程，包含查詢擴展、檢索、融合與生成。
     * <p>
     * 流程：
     * 1. 取得或建立 Session (用於狀態追蹤)。
     * 2. **查詢擴展**：呼叫 LLM 將使用者問題擴展為多個面向 (原始、關鍵字、口語)。
     * 3. **檢索與融合**：針對每個擴展查詢進行檢索並融合結果 (RRF)。
     * 4. **重排序**：對融合後的候選結果進行重排序 (Exacish Jaccard Bonus)。
     * 5. **生成回答**：若有檢索結果，將前 3 名 FAQ 作為 Prompt 傳入 LLM 生成回答。
     *
     * @param query     使用者原始問題
     * @param sessionId Session ID
     * @param topN      回傳結果數量
     * @return RagSearchResult (包含生成回答、參考來源、擴展查詢)
     */
    @Override
    public RagSearchResult searchRag(String query, String sessionId, int topN) {
        final long t0 = System.nanoTime();
        cleanExpiredSessions();

        // 取得或建立 session（僅用於護欄計數與存活管理；不記錄對話上下文）
        ChatSession session = sessionId != null ? sessionService.getOrCreateSession(sessionId) : null;

        // 1. Multi-Query Expansion
        final long tExpand0 = System.nanoTime();
        MultiQueryResult multiQuery = llmService.expandQuery(query);
        final long tExpand1 = System.nanoTime();
        logger.info("Multi-Query Expansion: original='{}', keyword='{}', colloquial='{}'",
                multiQuery.original(), multiQuery.keyword(), multiQuery.colloquial());

        // 2. 對每個查詢進行 BM25 檢索
        List<String> cleanedQueries = cleanQueries(multiQuery.toList());

        int retrievalTopK = runtimeConfigService.getRagRetrievalTopK();
        int rrfK = runtimeConfigService.getRagRrfK();

        final long tLucene0 = System.nanoTime();
        List<List<Integer>> rankings = new ArrayList<>();
        LuceneFaqIndex index = luceneIndex;
        for (String q : cleanedQueries) {
            List<LuceneFaqIndex.Hit> hits = index != null ? index.search(q, retrievalTopK) : List.of();
            List<Integer> ranked = hits.stream().map(LuceneFaqIndex.Hit::faqIndex).collect(Collectors.toList());
            rankings.add(ranked);
            logger.debug("Query '{}' 檢索到 {} 個結果", q, ranked.size());
        }
        final long tLucene1 = System.nanoTime();

        // 3. RRF 融合
        final long tRrf0 = System.nanoTime();
        Map<Integer, Double> rrfScores = VectorUtils.rrfFusionWithScores(rankings, rrfK);
        final long tRrf1 = System.nanoTime();

        // 4. 取 Top-N 結果（含 exact-ish rerank）
        final long tTopN0 = System.nanoTime();
        List<Map.Entry<Integer, Double>> candidates = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                .limit(Math.min(RERANK_MAX_CANDIDATES, Math.max(topN * RERANK_CANDIDATE_MULTIPLIER, topN)))
                .collect(Collectors.toList());

        String normQuery = normalizeForRerank(query);

        List<Faq> localFaqList = faqList != null ? faqList : List.of();
        List<Faq> topFaqs = candidates.stream()
                .map(entry -> {
                    int idx = entry.getKey();
                    if (idx < 0 || idx >= localFaqList.size()) {
                        return null;
                    }
                    Faq faq = localFaqList.get(idx).copy();
                    double base = entry.getValue();

                    double bonus = 0.0;
                    if (!normQuery.isEmpty()) {
                        String normQ = normalizeForRerank(faq.getQuestion());
                        if (!normQ.isEmpty()) {
                            double jac = jaccardCharBigrams(normQuery, normQ);
                            boolean exactish = jac >= RERANK_EXACTISH_JACCARD_THRESHOLD
                                    || normQ.contains(normQuery)
                                    || normQuery.contains(normQ);
                            bonus = exactish ? RERANK_EXACTISH_BONUS : (jac * RERANK_SOFT_BONUS_SCALE);
                        }
                    }

                    faq.setScore(base + bonus);
                    return faq;
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(Faq::getScore).reversed())
                .limit(topN)
                .collect(Collectors.toList());
        final long tTopN1 = System.nanoTime();

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

        logger.info("RAG 查詢 '{}' 找到 {} 筆結果，生成答案: {}",
                query, topFaqs.size(), generatedAnswer != null ? "是" : "否");

        long totalMs = (System.nanoTime() - t0) / 1_000_000;
        long expandMs = (tExpand1 - tExpand0) / 1_000_000;
        long luceneMs = (tLucene1 - tLucene0) / 1_000_000;
        long rrfMs = (tRrf1 - tRrf0) / 1_000_000;
        long topNMs = (tTopN1 - tTopN0) / 1_000_000;
        logger.info(
                "PERF(retrieval) api=searchRag totalMs={} expandMs={} luceneMs={} rrfMs={} topNMs={} qCount={} topN={}",
                totalMs, expandMs, luceneMs, rrfMs, topNMs, cleanedQueries.size(), topN);

        return new RagSearchResult(generatedAnswer, topFaqs, multiQuery);
    }

    /**
     * RAG 搜尋 - 指定 Multi-Query (RAG Search with Pre-computed Multi-Query)
     * <p>
     * 功能：
     * 針對已經完成查詢擴展的請求進行 RAG 檢索。此方法通常用於 Streaming 模式中，
     * 因為 Streaming 模式會先將 Multi-Query 結果回傳給前端，再進行檢索。
     * <p>
     * 注意：
     * 此方法**不包含** LLM 生成步驟，僅負責檢索與排序。生成步驟由 Streaming 流程獨立處理。
     * <p>
     * 流程：
     * 1. 清理查詢字串。
     * 2. **多路檢索**：使用 Lucene 對每個擴展查詢進行並行檢索。
     * 3. **RRF 融合**：將多路檢索的排名結果透過 RRF 公式融合。
     * 4. **重排序**：篩選前 N 個候選者，計算字串相似度加分，產出最終排名。
     *
     * @param query      使用者原始問題 (用於重排序比較)
     * @param multiQuery 已擴展的查詢物件
     * @param sessionId  Session ID
     * @param topN       回傳結果數量
     * @return RagSearchResult (生成回答為 null，僅包含來源與擴展查詢)
     */
    @Override
    public RagSearchResult searchRagWithMultiQuery(String query, MultiQueryResult multiQuery, String sessionId,
            int topN) {
        final long t0 = System.nanoTime();
        cleanExpiredSessions();

        ChatSession session = sessionId != null ? sessionService.getOrCreateSession(sessionId) : null;

        List<String> cleanedQueries = cleanQueries(multiQuery.toList());

        int retrievalTopK = runtimeConfigService.getRagRetrievalTopK();
        int rrfK = runtimeConfigService.getRagRrfK();

        final long tLucene0 = System.nanoTime();
        List<List<Integer>> rankings = new ArrayList<>();
        LuceneFaqIndex index = luceneIndex;
        for (String q : cleanedQueries) {
            List<LuceneFaqIndex.Hit> hits = index != null ? index.search(q, retrievalTopK) : List.of();
            List<Integer> ranked = hits.stream().map(LuceneFaqIndex.Hit::faqIndex).collect(Collectors.toList());
            rankings.add(ranked);
            logger.debug("Query '{}' 檢索到 {} 個結果", q, ranked.size());
        }
        final long tLucene1 = System.nanoTime();

        final long tRrf0 = System.nanoTime();
        Map<Integer, Double> rrfScores = VectorUtils.rrfFusionWithScores(rankings, rrfK);
        final long tRrf1 = System.nanoTime();

        final long tTopN0 = System.nanoTime();
        List<Map.Entry<Integer, Double>> candidates = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                .limit(Math.min(RERANK_MAX_CANDIDATES, Math.max(topN * RERANK_CANDIDATE_MULTIPLIER, topN)))
                .collect(Collectors.toList());

        String normQuery = normalizeForRerank(query);

        List<Faq> localFaqList = faqList != null ? faqList : List.of();
        List<Faq> topFaqs = candidates.stream()
                .map(entry -> {
                    int idx = entry.getKey();
                    if (idx < 0 || idx >= localFaqList.size()) {
                        return null;
                    }
                    Faq faq = localFaqList.get(idx).copy();
                    double base = entry.getValue();

                    double bonus = 0.0;
                    if (!normQuery.isEmpty()) {
                        String normQ = normalizeForRerank(faq.getQuestion());
                        if (!normQ.isEmpty()) {
                            double jac = jaccardCharBigrams(normQuery, normQ);
                            boolean exactish = jac >= RERANK_EXACTISH_JACCARD_THRESHOLD
                                    || normQ.contains(normQuery)
                                    || normQuery.contains(normQ);
                            bonus = exactish ? RERANK_EXACTISH_BONUS : (jac * RERANK_SOFT_BONUS_SCALE);
                        }
                    }

                    faq.setScore(base + bonus);
                    return faq;
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(Faq::getScore).reversed())
                .limit(topN)
                .collect(Collectors.toList());
        final long tTopN1 = System.nanoTime();

        logger.info("RAG 查詢 '{}' 找到 {} 筆結果（使用既有 Multi-Query；未生成答案）",
                query, topFaqs.size());

        long totalMs = (System.nanoTime() - t0) / 1_000_000;
        long luceneMs = (tLucene1 - tLucene0) / 1_000_000;
        long rrfMs = (tRrf1 - tRrf0) / 1_000_000;
        long topNMs = (tTopN1 - tTopN0) / 1_000_000;
        logger.info(
                "PERF(retrieval) api=searchRagWithMultiQuery totalMs={} luceneMs={} rrfMs={} topNMs={} qCount={} topN={}",
                totalMs, luceneMs, rrfMs, topNMs, cleanedQueries.size(), topN);

        return new RagSearchResult(null, topFaqs, multiQuery);
    }

    @Override
    public List<Faq> searchLuceneOnly(String query, int topN) {
        if (query == null || query.trim().isEmpty() || topN <= 0) {
            return List.of();
        }

        LuceneFaqIndex index = luceneIndex;
        List<LuceneFaqIndex.Hit> hits = index != null ? index.search(query, Math.max(topN, 1)) : List.of();
        if (hits.isEmpty()) {
            return List.of();
        }

        List<Faq> out = new ArrayList<>(Math.min(topN, hits.size()));
        List<Faq> localFaqList = faqList != null ? faqList : List.of();
        for (int i = 0; i < hits.size() && out.size() < topN; i++) {
            LuceneFaqIndex.Hit h = hits.get(i);
            if (h.faqIndex() < 0 || h.faqIndex() >= localFaqList.size()) {
                continue;
            }
            Faq faq = localFaqList.get(h.faqIndex()).copy();
            faq.setScore(h.score());
            out.add(faq);
        }
        return out;
    }

    @Override
    public List<Faq> searchWithContext(String query, int topN) {
        if (query == null || query.trim().isEmpty()) {
            return new ArrayList<>();
        }

        BM25 localBm25 = bm25;
        if (localBm25 == null) {
            return new ArrayList<>();
        }

        double[] scores = localBm25.normalizedScore(query);

        List<Faq> results = new ArrayList<>();
        List<Faq> localFaqList = faqList != null ? faqList : List.of();
        for (int i = 0; i < localFaqList.size(); i++) {
            if (scores[i] > 0.01) {
                Faq faq = localFaqList.get(i).copy();
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
        List<Faq> localFaqList = faqList != null ? faqList : List.of();
        return localFaqList.stream().map(Faq::copy).collect(Collectors.toList());
    }

    @Override
    public int getFaqCount() {
        return faqList != null ? faqList.size() : 0;
    }

    @Override
    public int getVocabSize() {
        BM25 localBm25 = bm25;
        return localBm25 != null ? localBm25.getDocCount() : 0;
    }

    @Override
    public int getActiveSessionCount() {
        return sessionService.getActiveSessionCount();
    }

    @Override
    public boolean isLlmAvailable() {
        return llmService.isAvailable();
    }

    private static String normalizeForRerank(String s) {
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
            int type = Character.getType(c);
            if (type == Character.OTHER_PUNCTUATION
                    || type == Character.DASH_PUNCTUATION
                    || type == Character.START_PUNCTUATION
                    || type == Character.END_PUNCTUATION
                    || type == Character.CONNECTOR_PUNCTUATION
                    || type == Character.INITIAL_QUOTE_PUNCTUATION
                    || type == Character.FINAL_QUOTE_PUNCTUATION) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static double jaccardCharBigrams(String a, String b) {
        if (a == null || b == null || a.length() < 2 || b.length() < 2) {
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
}
