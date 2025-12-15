package com.bank.qa.userapi.controller;

import com.bank.qa.service.FaqService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 頁面控制器
 * 處理 HTML 頁面路由
 */
@Controller
public class PageController {

    @Autowired
    private FaqService faqService;

    /**
     * 首頁
     */
    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("faqCount", faqService.getFaqCount());
        return "index";
    }
}
