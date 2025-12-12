package com.bank.qa.model;

import java.util.List;

/**
 * 意圖分類結果
 */
public record IntentResult(
        String intent,
        String message,
        List<String> suggestKeywords) {

    public boolean isRelated() {
        return "RELATED".equalsIgnoreCase(intent);
    }

    public boolean isUnrelated() {
        return "UNRELATED".equalsIgnoreCase(intent);
    }

    public boolean isUnclear() {
        return "UNCLEAR".equalsIgnoreCase(intent);
    }
}
