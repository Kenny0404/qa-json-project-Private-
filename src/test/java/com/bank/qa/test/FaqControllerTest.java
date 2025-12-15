package com.bank.qa.test;

import com.bank.qa.userapi.controller.FaqController;
import com.bank.qa.service.ChatService;
import com.bank.qa.service.FaqService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FaqController.class)
public class FaqControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FaqService faqService;

    @MockBean
    private ChatService chatService;

    @Test
    public void statsShouldReturnExpectedFields() throws Exception {
        when(faqService.getFaqCount()).thenReturn(10);
        when(faqService.isLlmAvailable()).thenReturn(true);
        when(faqService.getActiveSessionCount()).thenReturn(3);

        mockMvc.perform(get("/api/faq/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalFaq").value(10))
                .andExpect(jsonPath("$.mode").value("RAG"))
                .andExpect(jsonPath("$.ollamaAvailable").value(true))
                .andExpect(jsonPath("$.activeSessions").value(3));
    }

    @Test
    public void statusShouldReturnExpectedFields() throws Exception {
        when(faqService.getFaqCount()).thenReturn(10);
        when(faqService.getVocabSize()).thenReturn(999);
        when(faqService.getActiveSessionCount()).thenReturn(3);
        when(faqService.isLlmAvailable()).thenReturn(false);

        mockMvc.perform(get("/api/faq/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.faqCount").value(10))
                .andExpect(jsonPath("$.vocabSize").value(999))
                .andExpect(jsonPath("$.activeSessions").value(3))
                .andExpect(jsonPath("$.llmAvailable").value(false))
                .andExpect(jsonPath("$.mode").value("RAG"));
    }

    @Test
    public void deleteSessionShouldClearSession() throws Exception {
        mockMvc.perform(delete("/api/faq/session/{sessionId}", "s-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Session 已刪除"));

        verify(faqService, times(1)).clearSession("s-1");
    }
}
