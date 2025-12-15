package com.bank.qa.service.impl;

import com.bank.qa.model.Faq;
import com.bank.qa.repository.FaqRepository;
import com.bank.qa.service.AdminLogService;
import com.bank.qa.service.FaqAdminService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * FAQ 管理服務實作 (FAQ Admin Service Implementation)
 * <p>
 * 功能：
 * 專為後台管理端設計，提供 FAQ 的 CRUD (新增/修改/刪除) 操作與手動觸發索引重建功能。
 * 每次變更數據後，會自動觸發 `FaqServiceImpl` 的索引重建，確保前台檢索到最新數據。
 */
@Service
public class FaqAdminServiceImpl implements FaqAdminService {

    @Autowired
    private FaqRepository faqRepository;

    @Autowired
    private FaqServiceImpl faqService;

    @Autowired
    private AdminLogService adminLogService;

    @Override
    public List<Faq> listFaq() {
        return faqRepository.list();
    }

    @Override
    public Faq getFaq(int id) {
        return faqRepository.get(id);
    }

    /**
     * 新增 FAQ (Create FAQ)
     * <p>
     * 功能：
     * 建立一筆新的 FAQ 資料並寫入儲存層。
     * <p>
     * 流程：
     * 1. 呼叫 `FaqRepository.create` 持久化資料。
     * 2. 呼叫 `FaqServiceImpl.reindexFromRepository` 重建記憶體索引 (In-Memory Index)。
     * 3. 呼叫 `AdminLogService` 記錄操作日誌。
     */
    @Override
    public Faq createFaq(Faq faq) {
        Faq created = faqRepository.create(faq);
        faqService.reindexFromRepository();
        adminLogService.info("faq.create", "FAQ created", Map.of("id", created != null ? created.getId() : null));
        return created;
    }

    /**
     * 更新 FAQ (Update FAQ)
     * <p>
     * 功能：
     * 修改現有 FAQ 內容。
     * <p>
     * 流程：
     * 1. 呼叫 Repository 更新資料。
     * 2. 若更新成功，觸發索引重建與記錄成功日誌。
     * 3. 若無此 ID，記錄警告日誌。
     */
    @Override
    public Faq updateFaq(int id, Faq faq) {
        Faq updated = faqRepository.update(id, faq);
        if (updated != null) {
            faqService.reindexFromRepository();
            adminLogService.info("faq.update", "FAQ updated", Map.of("id", id));
        } else {
            adminLogService.warn("faq.update", "FAQ not found", Map.of("id", id));
        }
        return updated;
    }

    /**
     * 刪除 FAQ (Delete FAQ)
     * <p>
     * 功能：
     * 刪除指定 ID 的 FAQ。
     * <p>
     * 流程：
     * 1. 呼叫 Repository 執行刪除。
     * 2. 若刪除成功，觸發索引重建與記錄日誌。
     */
    @Override
    public boolean deleteFaq(int id) {
        boolean ok = faqRepository.delete(id);
        if (ok) {
            faqService.reindexFromRepository();
            adminLogService.info("faq.delete", "FAQ deleted", Map.of("id", id));
        } else {
            adminLogService.warn("faq.delete", "FAQ not found", Map.of("id", id));
        }
        return ok;
    }

    @Override
    public void reindex() {
        faqService.reindexFromRepository();
        adminLogService.info("faq.reindex", "FAQ reindexed", Map.of());
    }

    @Override
    public boolean isPersistenceEnabled() {
        return faqRepository.isPersistenceEnabled();
    }

    @Override
    public String getPersistenceFile() {
        return faqRepository.getPersistenceFile();
    }
}
