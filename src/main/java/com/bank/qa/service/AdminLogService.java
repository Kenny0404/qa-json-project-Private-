package com.bank.qa.service;

import java.util.List;
import java.util.Map;

public interface AdminLogService {
    record Entry(long timestampMs, String level, String action, String message, Map<String, Object> data) {
    }

    void info(String action, String message, Map<String, Object> data);

    void warn(String action, String message, Map<String, Object> data);

    List<Entry> query(Long sinceMs, Integer limit, String actionContains, String level);
}
