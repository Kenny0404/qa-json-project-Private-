package com.bank.qa.service;

import com.bank.qa.model.ChatSession;
import com.bank.qa.model.Faq;
import com.bank.qa.model.MultiQueryResult;
import com.bank.qa.model.RagSearchResult;

import java.util.List;

/**
 * FAQ 服務介面
 * 支援 Multi-Query + RRF 融合 + RAG 答案生成
 */
public interface FaqService {

    /**
     * RAG 搜尋 - 使用 Multi-Query + RRF 融合 + LLM 生成
     *
     * @param query     查詢文字
     * @param sessionId Session ID（可選）
     * @param topN      返回筆數
     * @return RAG 搜尋結果
     */
    RagSearchResult searchRag(String query, String sessionId, int topN);

    RagSearchResult searchRagWithMultiQuery(String query, MultiQueryResult multiQuery, String sessionId, int topN);

    /**
     * Lucene-only 快速檢索（不做 multi-query、不做 RRF、不呼叫 LLM）
     * 用於護欄判定為 UNRELATED/UNCLEAR 時的保底檢索。
     */
    List<Faq> searchLuceneOnly(String query, int topN);

    /**
     * BM25 搜尋（內部使用）
     *
     * @param query   查詢文字
     * @param topN    返回筆數
     * @return FAQ 結果列表
     */
    List<Faq> searchWithContext(String query, int topN);

    /**
     * 取得 Session
     */
    ChatSession getSession(String sessionId);

    /**
     * 取得或建立 Session
     */
    ChatSession getOrCreateSession(String sessionId);

    /**
     * 清除指定 Session
     */
    void clearSession(String sessionId);

    /**
     * 取得所有 FAQ
     */
    List<Faq> getAllFaq();

    /**
     * 取得 FAQ 總數
     */
    int getFaqCount();

    /**
     * 取得 BM25 詞彙表大小
     */
    int getVocabSize();

    /**
     * 取得活動 Session 數量
     */
    int getActiveSessionCount();

    /**
     * 檢查 LLM 是否可用
     */
    boolean isLlmAvailable();
}
