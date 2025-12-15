package com.bank.qa.service;

import java.util.Map;

public interface RuntimeConfigService {
    int getRagDefaultTopN();

    int getRagRetrievalTopK();

    int getRagRrfK();

    int getGuardrailEscalateAfter();

    String getGuardrailContactName();

    String getGuardrailContactPhone();

    String getGuardrailContactEmail();

    void updateRag(Integer defaultTopN, Integer retrievalTopK, Integer rrfK);

    void updateGuardrail(Integer escalateAfter, String contactName, String contactPhone, String contactEmail);

    Map<String, Object> snapshot();
}
