package com.bank.qa.service;

import com.bank.qa.model.MultiQueryResult;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Ollama LLM 服務介面
 * 封裝 Ollama LLM API 呼叫
 */
public interface OllamaLlmService {

    /**
     * 呼叫 LLM（非 Streaming）
     *
     * @param prompt      提示詞
     * @param temperature 溫度（0.0-1.0）
     * @return LLM 回應
     */
    String call(String prompt, double temperature);

    /**
     * 呼叫 LLM（Streaming）
     *
     * @param prompt      提示詞
     * @param temperature 溫度（0.0-1.0）
     * @param onChunk     每個 chunk 的回調
     */
    void callStreaming(String prompt, double temperature, Consumer<String> onChunk);

    default void callStreaming(String prompt, double temperature, Consumer<String> onChunk, BooleanSupplier cancelled) {
        callStreaming(prompt, temperature, onChunk);
    }

    /**
     * 擴展查詢為多個變體
     *
     * @param query 原始查詢
     * @return Multi-Query 結果
     */
    MultiQueryResult expandQuery(String query);

    /**
     * 生成 RAG 答案
     *
     * @param question 使用者問題
     * @param contexts 參考資料
     * @return 生成的答案
     */
    String generateAnswer(String question, List<String> contexts);

    /**
     * 生成 RAG 答案（Streaming）
     *
     * @param question 使用者問題
     * @param contexts 參考資料
     * @param onChunk  每個 chunk 的回調
     */
    void generateAnswerStreaming(String question, List<String> contexts, Consumer<String> onChunk);

    default void generateAnswerStreaming(String question, List<String> contexts, Consumer<String> onChunk, BooleanSupplier cancelled) {
        generateAnswerStreaming(question, contexts, onChunk);
    }

    /**
     * 檢查 LLM 服務是否可用
     *
     * @return true 如果可用
     */
    boolean isAvailable();
}
