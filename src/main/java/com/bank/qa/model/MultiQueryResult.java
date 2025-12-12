package com.bank.qa.model;

import java.util.List;

/**
 * Multi-Query 擴展結果
 */
public record MultiQueryResult(
        String original,
        String keyword,
        String colloquial) {

    public List<String> toList() {
        return List.of(original, keyword, colloquial);
    }
}
