package com.bank.qa.test;

import com.bank.qa.model.Faq;
import com.bank.qa.service.FaqService;
import com.bank.qa.service.impl.FaqServiceImpl;
import com.bank.qa.util.VectorUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FAQ 服務測試類別
 * 測試 BM25 搜尋功能
 * 
 * 注意：Session 相關測試已移至 Integration Test，因需要 Spring DI
 */
public class FaqServiceTest {

    private FaqServiceImpl faqService;

    @BeforeEach
    public void setUp() {
        faqService = new FaqServiceImpl();
        faqService.init();
    }

    // ========== BM25 搜尋測試 ==========

    @Test
    @DisplayName("測試 BM25 搜尋 - 標準型交易")
    public void testBm25SearchStandardTransaction() {
        List<Faq> results = faqService.searchWithContext("標準型交易前資料建檔流程", null, 5);

        assertNotNull(results, "搜尋結果不應為 null");
        assertFalse(results.isEmpty(), "搜尋結果不應為空");
        assertTrue(results.get(0).getQuestion().contains("標準型"),
                "第一個結果應包含標準型相關問題");
        assertTrue(results.get(0).getScore() > 0, "結果應有相似度分數");
    }

    @Test
    @DisplayName("測試 BM25 搜尋 - 模糊查詢")
    public void testBm25SearchFuzzy() {
        List<Faq> results = faqService.searchWithContext("怎麼還款", null, 5);

        assertNotNull(results, "搜尋結果不應為 null");
        boolean hasRepaymentResult = results.stream()
                .anyMatch(f -> f.getQuestion().contains("還款"));
        assertTrue(hasRepaymentResult, "應找到還款相關結果");
    }

    @Test
    @DisplayName("測試 Top-N 限制 - 返回 3 條")
    public void testTopN_3() {
        List<Faq> results = faqService.searchWithContext("交易", null, 3);

        assertNotNull(results, "搜尋結果不應為 null");
        assertTrue(results.size() <= 3, "結果數量不應超過 3");
    }

    @Test
    @DisplayName("測試 Top-N 排序 - 分數遞減")
    public void testTopNSorting() {
        List<Faq> results = faqService.searchWithContext("發票", null, 5);

        assertNotNull(results, "搜尋結果不應為 null");
        if (results.size() > 1) {
            for (int i = 0; i < results.size() - 1; i++) {
                assertTrue(results.get(i).getScore() >= results.get(i + 1).getScore(),
                        "結果應按分數遞減排序");
            }
        }
    }

    // ========== BM25 工具測試 ==========

    @Test
    @DisplayName("測試 BM25 評分")
    public void testBM25Scoring() {
        VectorUtils.BM25 bm25 = new VectorUtils.BM25(1.5, 0.75);
        bm25.fit(java.util.Arrays.asList(
                "標準型交易前資料建檔流程",
                "維持率基本資料建檔流程",
                "買方還款操作步驟"));

        double[] scores = bm25.score("標準型交易");
        assertNotNull(scores, "BM25 分數不應為 null");
        assertEquals(3, scores.length, "應有 3 個分數");
        assertTrue(scores[0] > scores[1], "第一個文檔分數應最高");
    }

    @Test
    @DisplayName("測試中文分詞")
    public void testTokenize() {
        List<String> tokens = VectorUtils.tokenize("標準型交易");

        assertNotNull(tokens, "分詞結果不應為 null");
        assertFalse(tokens.isEmpty(), "分詞結果不應為空");
        assertTrue(tokens.contains("標準"), "應包含雙字詞");
    }

    // ========== 邊界條件測試 ==========

    @Test
    @DisplayName("測試空查詢")
    public void testEmptyQuery() {
        List<Faq> results = faqService.searchWithContext("", null, 5);

        assertNotNull(results, "搜尋結果不應為 null");
        assertTrue(results.isEmpty(), "空查詢應回傳空列表");
    }

    @Test
    @DisplayName("測試 null 查詢")
    public void testNullQuery() {
        List<Faq> results = faqService.searchWithContext(null, null, 5);

        assertNotNull(results, "搜尋結果不應為 null");
        assertTrue(results.isEmpty(), "null 查詢應回傳空列表");
    }

    // ========== 其他功能測試 ==========

    @Test
    @DisplayName("測試取得所有 FAQ")
    public void testGetAllFaq() {
        List<Faq> allFaq = faqService.getAllFaq();

        assertNotNull(allFaq, "FAQ 列表不應為 null");
        assertFalse(allFaq.isEmpty(), "FAQ 列表不應為空");
    }

    @Test
    @DisplayName("測試 FAQ 數量")
    public void testGetFaqCount() {
        int count = faqService.getFaqCount();
        assertTrue(count > 0, "FAQ 數量應大於 0");
    }
}
