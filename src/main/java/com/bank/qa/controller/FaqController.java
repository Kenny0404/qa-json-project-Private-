package com.bank.qa.controller;

import com.bank.qa.model.Faq;
import com.bank.qa.model.RagSearchResult;
import com.bank.qa.service.ChatService;
import com.bank.qa.service.FaqService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

/**
 * FAQ 控制器
 * 提供 REST API 端點
 * 
 * 注意：業務邏輯已移至 ChatService
 */
@RestController
@RequestMapping("/api/faq")
@CrossOrigin(origins = "*")
public class FaqController {

    private static final Logger logger = LoggerFactory.getLogger(FaqController.class);

    @Autowired
    private FaqService faqService;

    @Autowired
    private ChatService chatService;

    /**
     * RAG 搜尋 - Streaming 版本
     */
    @GetMapping(value = "/search/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter searchStream(
            @RequestParam String question,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false, defaultValue = "5") int topN) {

        logger.info("收到 Streaming 搜尋請求: question={}, sessionId={}, topN={}",
                question, sessionId, topN);

        SseEmitter emitter = new SseEmitter(180000L); // 3 分鐘 timeout
        chatService.processStreamingChat(question, sessionId, topN, emitter);
        return emitter;
    }

    /**
     * RAG 搜尋 - 非 Streaming 版本
     */
    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam String question,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false, defaultValue = "5") int topN) {

        logger.info("收到 RAG 搜尋請求: question={}, sessionId={}, topN={}",
                question, sessionId, topN);

        ChatService.ChatResult result = chatService.processChat(question, sessionId, topN);

        Map<String, Object> response = new HashMap<>();
        response.put("sessionId", result.sessionId());
        response.put("newSession", result.newSession());
        response.put("query", question);
        response.put("intent", result.intent());
        response.put("answer", result.answer());
        response.put("sources", result.sources());
        response.put("multiQuery", result.multiQuery());
        response.put("count", result.sources().size());
        response.put("success", true);

        return response;
    }

    /**
     * 取得所有 FAQ
     */
    @GetMapping("/list")
    public Map<String, Object> listFaq() {
        Map<String, Object> response = new HashMap<>();
        List<Faq> faqList = faqService.getAllFaq();
        response.put("success", true);
        response.put("data", faqList);
        response.put("count", faqList.size());
        return response;
    }

    /**
     * 清除 Session
     */
    @PostMapping("/session/clear")
    public Map<String, Object> clearSession(@RequestParam String sessionId) {
        logger.info("清除 Session 請求: {}", sessionId);
        faqService.clearSession(sessionId);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Session 已清除");
        return response;
    }

    /**
     * 取得系統狀態
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("faqCount", faqService.getFaqCount());
        status.put("vocabSize", faqService.getVocabSize());
        status.put("activeSessions", faqService.getActiveSessionCount());
        status.put("llmAvailable", faqService.isLlmAvailable());
        status.put("mode", "RAG");
        return status;
    }
}
