package com.bank.qa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 銀行 QA 系統主應用程式
 * 封閉領域 QA 系統，用於銀行內部專案查詢
 */
@SpringBootApplication
public class QaApplication {

    public static void main(String[] args) {
        SpringApplication.run(QaApplication.class, args);
        System.out.println("=================================");
        System.out.println("  銀行 QA 系統已啟動！");
        System.out.println("  請訪問: http://localhost:8080");
        System.out.println("=================================");
    }
}
