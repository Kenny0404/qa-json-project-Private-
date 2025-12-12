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
import java.util.function.Consumer;

/**
 * Ollama LLM 服務實作
 * 封裝對 Ollama API 的所有呼叫
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
            }
            logger.debug("Ollama Streaming 完成");
        } catch (Exception e) {
            logger.error("Ollama Streaming 呼叫失敗: {}", e.getMessage());
            throw new RuntimeException("LLM Streaming 失敗", e);
        }
    }

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
    public boolean isQueryRelated(String currentQuery, String previousQuery) {
        if (previousQuery == null || previousQuery.isEmpty()) {
            return false;
        }

        String prompt = String.format("""
                判斷以下兩個問題是否屬於同一個主題或有上下文關係：

                前一個問題：%s
                當前問題：%s

                判斷標準：
                - 如果當前問題是前一個問題的追問、補充、延續，回答 YES
                - 如果當前問題是完全不同的主題，回答 NO

                只回答 YES 或 NO，不要有其他文字。
                """, previousQuery, currentQuery);

        try {
            String response = call(prompt, 0.1);
            String answer = response.trim().toUpperCase();
            boolean related = answer.contains("YES");
            logger.info("問題相關性判斷: '{}' vs '{}' = {}", previousQuery, currentQuery, related);
            return related;
        } catch (Exception e) {
            logger.warn("問題相關性判斷失敗: {}", e.getMessage());
            return true;
        }
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
