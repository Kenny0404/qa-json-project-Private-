package com.bank.qa.service.impl;

import com.bank.qa.service.AdminLogService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 管理員日誌服務實作 (Admin Log Service Implementation)
 * <p>
 * 功能：
 * 提供一個輕量級的記憶體內（In-Memory）日誌記錄系統，專門用於記錄後台操作日誌（如配置變更、登入等）。
 * <p>
 * 機制：
 * 1. 使用 `ConcurrentLinkedDeque` 儲存日誌項目，確保 Thread-Safe。
 * 2. 實作 Rolling Buffer 機制，當日誌數量超過 `maxEntries` 時自動移除最舊的項目。
 */
@Service
public class AdminLogServiceImpl implements AdminLogService {

    @Value("${admin.log.max:1000}")
    private int maxEntries;

    private final Deque<Entry> entries = new ConcurrentLinkedDeque<>();

    @Override
    public void info(String action, String message, Map<String, Object> data) {
        append(new Entry(System.currentTimeMillis(), "INFO", action, message, data));
    }

    @Override
    public void warn(String action, String message, Map<String, Object> data) {
        append(new Entry(System.currentTimeMillis(), "WARN", action, message, data));
    }

    /**
     * 追加日誌 (Append Entry)
     * <p>
     * 功能：
     * 將新日誌加入佇列尾端，並檢查是否超過容量上限。
     * <p>
     * 流程：
     * 1. 使用 `addLast` 加入新項目。
     * 2. 檢查 `size()` 是否超過 `maxEntries`。
     * 3. 若超過，使用 `pollFirst` 移除最舊的日誌。
     */
    private void append(Entry entry) {
        entries.addLast(entry);
        while (entries.size() > Math.max(1, maxEntries)) {
            entries.pollFirst();
        }
    }

    /**
     * 查詢日誌 (Query Logs)
     * <p>
     * 功能：
     * 提供多條件篩選的日誌查詢功能。
     * <p>
     * 流程：
     * 1. 遍歷記憶體中的日誌佇列。
     * 2. 應用篩選條件：
     * - sinceMs: 時間戳記是否在指定時間之後。
     * - actionContains: 動作名稱是否包含指定關鍵字 (不分大小寫)。
     * - level: 日誌等級 (INFO/WARN 等) 是否匹配。
     * 3. 收集符合條件的項目。
     * 4. 根據 `limit` 參數截取最新的 N 筆回傳。
     *
     * @return 符合條件的日誌列表
     */
    @Override
    public List<Entry> query(Long sinceMs, Integer limit, String actionContains, String level) {
        long since = sinceMs != null ? sinceMs : 0L;
        int lim = limit != null ? Math.max(1, limit) : 200;

        String actionLike = actionContains != null ? actionContains.toLowerCase(Locale.ROOT) : null;
        String levelLike = level != null ? level.toUpperCase(Locale.ROOT) : null;

        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.timestampMs() < since) {
                continue;
            }
            if (actionLike != null
                    && (e.action() == null || !e.action().toLowerCase(Locale.ROOT).contains(actionLike))) {
                continue;
            }
            if (levelLike != null && (e.level() == null || !e.level().toUpperCase(Locale.ROOT).equals(levelLike))) {
                continue;
            }
            out.add(e);
        }

        int from = Math.max(0, out.size() - lim);
        return out.subList(from, out.size());
    }
}
