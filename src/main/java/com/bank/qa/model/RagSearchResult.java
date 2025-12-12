package com.bank.qa.model;

import java.util.List;

/**
 * RAG 搜尋結果
 */
public class RagSearchResult {
    private final String answer;
    private final List<Faq> sources;
    private final MultiQueryResult multiQuery;

    public RagSearchResult(String answer, List<Faq> sources, MultiQueryResult multiQuery) {
        this.answer = answer;
        this.sources = sources;
        this.multiQuery = multiQuery;
    }

    public String getAnswer() {
        return answer;
    }

    public List<Faq> getSources() {
        return sources;
    }

    public MultiQueryResult getMultiQuery() {
        return multiQuery;
    }
}
