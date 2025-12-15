package com.bank.qa.test;

import com.bank.qa.model.MultiQueryResult;
import com.bank.qa.model.RagSearchResult;
import com.bank.qa.service.OllamaLlmService;
import com.bank.qa.service.SessionService;
import com.bank.qa.service.impl.FaqServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

public class FaqServiceMultiQueryTest {

    @Test
    public void searchRagWithMultiQueryShouldNotCallLlm() {
        FaqServiceImpl faqService = new FaqServiceImpl();

        OllamaLlmService llmService = mock(OllamaLlmService.class);
        SessionService sessionService = mock(SessionService.class);

        ReflectionTestUtils.setField(faqService, "llmService", llmService);
        ReflectionTestUtils.setField(faqService, "sessionService", sessionService);
        ReflectionTestUtils.setField(faqService, "retrievalTopK", 10);
        ReflectionTestUtils.setField(faqService, "rrfK", 60);

        faqService.init();

        MultiQueryResult multiQuery = new MultiQueryResult("發票", "發票", "發票");
        RagSearchResult result = faqService.searchRagWithMultiQuery("發票", multiQuery, null, 5);

        assertNotNull(result);
        assertNull(result.getAnswer());
        assertSame(multiQuery, result.getMultiQuery());
        assertNotNull(result.getSources());
        assertTrue(result.getSources().size() <= 5);

        verifyNoInteractions(llmService);
    }
}
