package com.bank.qa.service.impl;

import com.bank.qa.model.MultiQueryResult;
import com.bank.qa.service.OllamaLlmService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Ollama LLM 服務實作 (Ollama LLM Service Implementation)
 * <p>
 * 功能：
 * 封裝對 Ollama API (如 Ministral-8b) 的所有 HTTP 呼叫，提供同步 (Sync) 與串流 (Streaming)
 * 兩種互動模式，
 * 並包含查詢擴展 (Multi-Query Expansion) 與 RAG 答案生成的 Prompt 建構邏輯。
 */
@Service
public class OllamaLlmServiceImpl implements OllamaLlmService {

    private static final Logger logger = LoggerFactory.getLogger(OllamaLlmServiceImpl.class);

    @Value("${ollama.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${ollama.model:ministral:8b}")
    private String model;

    @Value("${ollama.timeout:60000}")
    private int timeout;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 同步呼叫 (Synchronous Call)
     * <p>
     * 功能：
     * 向 Ollama 發送單次請求並等待完整回應回傳 (stream=false)。
     * <p>
     * 流程：
     * 1. 建立 HTTP POST 連線至 `/api/generate`。
     * 2. 建構 JSON Body (包含 model, prompt, options)。
     * 3. 發送請求並從 InputStream 讀取回應。
     * 4. 解析回傳 JSON 中的 `response` 欄位並組合。
     *
     * @param prompt      提示詞
     * @param temperature 溫度參數 (0-1)
     * @return 完整回應字串
     */
    @Override
    public String call(String prompt, double temperature) {
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("model", model);
            request.put("prompt", prompt);
            request.put("stream", false);

            Map<String, Object> options = new HashMap<>();
            options.put("temperature", temperature);
            request.put("options", options);

            HttpURLConnection conn = createConnection("/api/generate");
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(objectMapper.writeValueAsBytes(request));
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    JsonNode node = objectMapper.readTree(line);
                    if (node.has("response")) {
                        sb.append(node.get("response").asText());
                    }
                }
                return sb.toString();
            }
        } catch (Exception e) {
            logger.error("Ollama API 呼叫失敗: {}", e.getMessage());
            throw new RuntimeException("LLM 呼叫失敗", e);
        }
    }

    @Override
    public void callStreaming(String prompt, double temperature, Consumer<String> onChunk) {
        callStreaming(prompt, temperature, onChunk, () -> false);
    }

    /**
     * 串流呼叫 (Streaming Call)
     * <p>
     * 功能：
     * 向 Ollama 發送請求並以串流方式接收回應 (stream=true)。支援中途取消。
     * <p>
     * 流程：
     * 1. 建立 HTTP POST 連線，設定 `stream: true`。
     * 2. 逐行讀取 Response Stream。
     * 3. 每一行解析 JSON，提取 `response` 內容。
     * 4. 透過 `onChunk` Callback 即時將片段回傳給上層調用者。
     * 5. 檢查 `cancelled` 狀態，若為 true 則斷開連線並停止讀取。
     *
     * @param prompt      提示詞
     * @param temperature 溫度參數
     * @param onChunk     接收片段的回呼函式
     * @param cancelled   檢查是否取消的函式 (可為 null)
     */
    @Override
    public void callStreaming(String prompt, double temperature, Consumer<String> onChunk, BooleanSupplier cancelled) {
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("model", model);
            request.put("prompt", prompt);
            request.put("stream", true);

            Map<String, Object> options = new HashMap<>();
            options.put("temperature", temperature);
            request.put("options", options);

            HttpURLConnection conn = createConnection("/api/generate");
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(objectMapper.writeValueAsBytes(request));
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancelled != null && cancelled.getAsBoolean()) {
                        break;
                    }

                    JsonNode node = objectMapper.readTree(line);
                    if (node.has("response")) {
                        String chunk = node.get("response").asText();
                        if (!chunk.isEmpty()) {
                            onChunk.accept(chunk);
                        }
                    }
                    if (node.has("done") && node.get("done").asBoolean()) {
                        break;
                    }
                }
            } finally {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    conn.disconnect();
                }
            }
            logger.debug("Ollama Streaming 完成");
        } catch (Exception e) {
            logger.error("Ollama Streaming 呼叫失敗: {}", e.getMessage());
            throw new RuntimeException("LLM Streaming 失敗", e);
        }
    }

    /**
     * 查詢擴展 (Query Expansion)
     * <p>
     * 功能：
     * 利用 LLM 將單一使用者查詢改寫為三個版本，以提升檢索覆蓋率。
     * <p>
     * 流程：
     * 1. 建立 Prompt，要求 LLM 生成：原始版、關鍵字版、口語化版。
     * 2. 強制 LLM 輸出 JSON 格式。
     * 3. 解析 JSON 並封裝為 `MultiQueryResult`。
     * 4. 若解析失敗，則三個版本皆回退為原始查詢。
     *
     * @param query 原始查詢
     * @return MultiQueryResult 擴展結果
     */
    @Override
    public MultiQueryResult expandQuery(String query) {
        String prompt = String.format("""
                將以下銀行業務查詢改寫為三個不同版本，用於提高搜索效果：
                1. 原始查詢（保持原樣）
                2. 關鍵字版本（提取核心關鍵字）
                3. 口語化版本（更自然的問法）

                查詢：%s

                只返回 JSON 格式：
                {"original": "原始查詢", "keyword": "關鍵字版本", "colloquial": "口語化版本"}
                """, query);

        try {
            String response = call(prompt, 0.3);
            int start = response.indexOf('{');
            int end = response.lastIndexOf('}') + 1;
            if (start >= 0 && end > start) {
                String json = response.substring(start, end);
                JsonNode node = objectMapper.readTree(json);
                return new MultiQueryResult(
                        node.has("original") ? node.get("original").asText() : query,
                        node.has("keyword") ? node.get("keyword").asText() : query,
                        node.has("colloquial") ? node.get("colloquial").asText() : query);
            }
        } catch (Exception e) {
            logger.warn("Multi-Query 擴展失敗，使用原始查詢: {}", e.getMessage());
        }
        return new MultiQueryResult(query, query, query);
    }

    @Override
    public String generateAnswer(String question, List<String> contexts) {
        String contextText = String.join("\n\n---\n\n", contexts);
        String prompt = buildRagPrompt(question, contextText);
        return call(prompt, 0.3);
    }

    @Override
    public void generateAnswerStreaming(String question, List<String> contexts, Consumer<String> onChunk) {
        String contextText = String.join("\n\n---\n\n", contexts);
        String prompt = buildRagPrompt(question, contextText);
        callStreaming(prompt, 0.3, onChunk);
    }

    /**
     * 串流生成 RAG 回答 (Generate Answer Streaming via RAG)
     * <p>
     * 功能：
     * 結合檢索到的 Context 與使用者問題，建構 RAG Prompt 並進行串流生成。
     */
    @Override
    public void generateAnswerStreaming(String question, List<String> contexts, Consumer<String> onChunk,
            BooleanSupplier cancelled) {
        String contextText = String.join("\n\n---\n\n", contexts);
        String prompt = buildRagPrompt(question, contextText);
        callStreaming(prompt, 0.3, onChunk, cancelled);
    }

    /**
     * 建構 RAG 提示詞
     */
    private String buildRagPrompt(String question, String contextText) {
        return String.format("""
                你是銀行 Factoring（應收帳款融資）業務的專業客服助理。
                請根據以下參考資料回答用戶問題。

                重要規則：
                1. 只使用參考資料中的資訊回答
                2. 如果參考資料無法回答問題，明確告知用戶「抱歉，此問題不在我的知識範圍內，無法回答。」
                3. 使用繁體中文回答
                4. 回答要清晰、有條理

                用戶問題：%s

                參考資料：
                %s

                請回答：
                """, question, contextText);
    }

    @Override
    public boolean isAvailable() {
        try {
            HttpURLConnection conn = createConnection("/api/tags");
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private HttpURLConnection createConnection(String path) throws Exception {
        URI uri = URI.create(baseUrl + path);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setConnectTimeout(timeout);
        conn.setReadTimeout(timeout);
        return conn;
    }
}
