package com.bank.qa.adminapi.controller;

import com.bank.qa.model.Faq;
import com.bank.qa.service.AdminLogService;
import com.bank.qa.service.FaqAdminService;
import com.bank.qa.service.RuntimeConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "*")
public class AdminController {

    @Autowired
    private FaqAdminService faqAdminService;

    @Autowired
    private RuntimeConfigService runtimeConfigService;

    @Autowired
    private AdminLogService adminLogService;

    @GetMapping("/faq")
    public Map<String, Object> listFaq() {
        List<Faq> list = faqAdminService.listFaq();
        Map<String, Object> out = new HashMap<>();
        out.put("success", true);
        out.put("data", list);
        out.put("count", list.size());
        out.put("persistenceEnabled", faqAdminService.isPersistenceEnabled());
        out.put("persistenceFile", faqAdminService.getPersistenceFile());
        return out;
    }

    @GetMapping("/faq/{id}")
    public Map<String, Object> getFaq(@PathVariable int id) {
        Faq faq = faqAdminService.getFaq(id);
        Map<String, Object> out = new HashMap<>();
        out.put("success", faq != null);
        out.put("data", faq);
        return out;
    }

    @PostMapping("/faq")
    public Map<String, Object> createFaq(@RequestBody Faq faq) {
        Faq created = faqAdminService.createFaq(faq);
        Map<String, Object> out = new HashMap<>();
        out.put("success", true);
        out.put("data", created);
        return out;
    }

    @PutMapping("/faq/{id}")
    public Map<String, Object> updateFaq(@PathVariable int id, @RequestBody Faq faq) {
        Faq updated = faqAdminService.updateFaq(id, faq);
        Map<String, Object> out = new HashMap<>();
        out.put("success", updated != null);
        out.put("data", updated);
        if (updated == null) {
            out.put("message", "FAQ not found");
        }
        return out;
    }

    @DeleteMapping("/faq/{id}")
    public Map<String, Object> deleteFaq(@PathVariable int id) {
        boolean ok = faqAdminService.deleteFaq(id);
        Map<String, Object> out = new HashMap<>();
        out.put("success", ok);
        out.put("deleted", ok);
        if (!ok) {
            out.put("message", "FAQ not found");
        }
        return out;
    }

    @PostMapping("/reindex")
    public Map<String, Object> reindex() {
        faqAdminService.reindex();
        Map<String, Object> out = new HashMap<>();
        out.put("success", true);
        out.put("message", "reindexed");
        return out;
    }

    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> out = new HashMap<>();
        out.put("success", true);
        out.put("data", runtimeConfigService.snapshot());
        return out;
    }

    @PutMapping("/config")
    public Map<String, Object> updateConfig(@RequestBody Map<String, Object> body) {
        Map<String, Object> rag = body.get("rag") instanceof Map ? (Map<String, Object>) body.get("rag") : null;
        Map<String, Object> guardrail = body.get("guardrail") instanceof Map ? (Map<String, Object>) body.get("guardrail") : null;

        if (rag != null) {
            runtimeConfigService.updateRag(
                    asInt(rag.get("defaultTopN")),
                    asInt(rag.get("retrievalTopK")),
                    asInt(rag.get("rrfK")));
        }

        if (guardrail != null) {
            runtimeConfigService.updateGuardrail(
                    asInt(guardrail.get("escalateAfter")),
                    asString(guardrail.get("contactName")),
                    asString(guardrail.get("contactPhone")),
                    asString(guardrail.get("contactEmail")));
        }

        adminLogService.info("config.update", "Runtime config updated", Map.of());

        Map<String, Object> out = new HashMap<>();
        out.put("success", true);
        out.put("data", runtimeConfigService.snapshot());
        return out;
    }

    @GetMapping("/logs")
    public Map<String, Object> queryLogs(
            @RequestParam(required = false) Long sinceMs,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String actionContains,
            @RequestParam(required = false) String level) {

        List<AdminLogService.Entry> entries = adminLogService.query(sinceMs, limit, actionContains, level);
        Map<String, Object> out = new HashMap<>();
        out.put("success", true);
        out.put("data", entries);
        out.put("count", entries.size());
        return out;
    }

    private static Integer asInt(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
