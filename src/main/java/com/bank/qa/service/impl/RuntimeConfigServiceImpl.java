package com.bank.qa.service.impl;

import com.bank.qa.service.RuntimeConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 執行時配置服務實作 (Runtime Configuration Service Implementation)
 * <p>
 * 功能：
 * 允許在不重啟伺服器的情況下，動態調整 RAG 參數與護欄設定。
 * <p>
 * 機制：
 * 1. 使用 `AtomicReference` 儲存可變的覆蓋值 (Override)。
 * 2. 讀取時，優先回傳 Override 值，若無則回傳 `application.properties` 中的預設值。
 */
@Service
public class RuntimeConfigServiceImpl implements RuntimeConfigService {

    @Value("${rag.default-top-n:5}")
    private int ragDefaultTopNDefault;

    @Value("${rag.retrieval-top-k:10}")
    private int ragRetrievalTopKDefault;

    @Value("${rag.rrf-k:60}")
    private int ragRrfKDefault;

    @Value("${guardrail.escalate-after:3}")
    private int guardrailEscalateAfterDefault;

    @Value("${guardrail.contact-name:李小姐}")
    private String guardrailContactNameDefault;

    @Value("${guardrail.contact-phone:(02)2883-4228 #6633}")
    private String guardrailContactPhoneDefault;

    @Value("${guardrail.contact-email:lara.li@vteamsystem.com.tw}")
    private String guardrailContactEmailDefault;

    private final AtomicReference<Integer> ragDefaultTopNOverride = new AtomicReference<>();
    private final AtomicReference<Integer> ragRetrievalTopKOverride = new AtomicReference<>();
    private final AtomicReference<Integer> ragRrfKOverride = new AtomicReference<>();

    private final AtomicReference<Integer> guardrailEscalateAfterOverride = new AtomicReference<>();
    private final AtomicReference<String> guardrailContactNameOverride = new AtomicReference<>();
    private final AtomicReference<String> guardrailContactPhoneOverride = new AtomicReference<>();
    private final AtomicReference<String> guardrailContactEmailOverride = new AtomicReference<>();

    @Override
    public int getRagDefaultTopN() {
        Integer v = ragDefaultTopNOverride.get();
        return v != null ? v : ragDefaultTopNDefault;
    }

    @Override
    public int getRagRetrievalTopK() {
        Integer v = ragRetrievalTopKOverride.get();
        return v != null ? v : ragRetrievalTopKDefault;
    }

    @Override
    public int getRagRrfK() {
        Integer v = ragRrfKOverride.get();
        return v != null ? v : ragRrfKDefault;
    }

    @Override
    public int getGuardrailEscalateAfter() {
        Integer v = guardrailEscalateAfterOverride.get();
        return v != null ? v : guardrailEscalateAfterDefault;
    }

    @Override
    public String getGuardrailContactName() {
        String v = guardrailContactNameOverride.get();
        return v != null ? v : guardrailContactNameDefault;
    }

    @Override
    public String getGuardrailContactPhone() {
        String v = guardrailContactPhoneOverride.get();
        return v != null ? v : guardrailContactPhoneDefault;
    }

    @Override
    public String getGuardrailContactEmail() {
        String v = guardrailContactEmailOverride.get();
        return v != null ? v : guardrailContactEmailDefault;
    }

    /**
     * 更新 RAG 配置 (Update RAG Configuration)
     * <p>
     * 功能：
     * 動態修改 RAG 檢索流程的關鍵參數。
     * <p>
     * 參數說明：
     * - defaultTopN: 最終回傳給 LLM 參考的 FAQ 數量。
     * - retrievalTopK: 初步檢索時從 Lucene 取回的數量。
     * - rrfK: RRF 演算法中的常數 K，影響排名融合的平滑度。
     */
    @Override
    public void updateRag(Integer defaultTopN, Integer retrievalTopK, Integer rrfK) {
        if (defaultTopN != null) {
            ragDefaultTopNOverride.set(defaultTopN);
        }
        if (retrievalTopK != null) {
            ragRetrievalTopKOverride.set(retrievalTopK);
        }
        if (rrfK != null) {
            ragRrfKOverride.set(rrfK);
        }
    }

    /**
     * 更新護欄配置 (Update Guardrail Configuration)
     * <p>
     * 功能：
     * 動態修改護欄的升級閾值與聯絡窗口資訊。
     */
    @Override
    public void updateGuardrail(Integer escalateAfter, String contactName, String contactPhone, String contactEmail) {
        if (escalateAfter != null) {
            guardrailEscalateAfterOverride.set(escalateAfter);
        }
        if (contactName != null) {
            guardrailContactNameOverride.set(contactName);
        }
        if (contactPhone != null) {
            guardrailContactPhoneOverride.set(contactPhone);
        }
        if (contactEmail != null) {
            guardrailContactEmailOverride.set(contactEmail);
        }
    }

    /**
     * 取得設定快照 (Get Configuration Snapshot)
     * <p>
     * 功能：
     * 匯總當前生效的設定值（包含預設值與覆蓋值），用於前端後台顯示。
     *
     * @return Map 包含 rag 與 guardrail 兩大類的當前設定。
     */
    @Override
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new HashMap<>();
        Map<String, Object> rag = new HashMap<>();
        rag.put("defaultTopN", getRagDefaultTopN());
        rag.put("retrievalTopK", getRagRetrievalTopK());
        rag.put("rrfK", getRagRrfK());
        out.put("rag", rag);

        Map<String, Object> guardrail = new HashMap<>();
        guardrail.put("escalateAfter", getGuardrailEscalateAfter());
        guardrail.put("contactName", getGuardrailContactName());
        guardrail.put("contactPhone", getGuardrailContactPhone());
        guardrail.put("contactEmail", getGuardrailContactEmail());
        out.put("guardrail", guardrail);

        return out;
    }
}
