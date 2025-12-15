package com.bank.qa.util;

import com.bank.qa.model.Faq;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;

/**
 * JSON 檔案載入工具
 * 從 resources 目錄讀取 JSON 檔案並轉換為 FAQ 物件列表
 */
public class JsonLoader {

    private static final Logger logger = LoggerFactory.getLogger(JsonLoader.class);

    /**
     * 載入指定的 JSON 檔案並解析為 FAQ 列表
     *
     * @param jsonFile JSON 檔案名稱（位於 resources 目錄下）
     * @return FAQ 物件列表，若讀取失敗則回傳空列表
     */
    public static List<Faq> loadFaq(String jsonFile) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try (InputStream is = JsonLoader.class.getResourceAsStream("/" + jsonFile)) {
            if (is == null) {
                logger.error("找不到檔案: {}", jsonFile);
                return Collections.emptyList();
            }
            List<Faq> faqList = mapper.readValue(is, new TypeReference<List<Faq>>() {});
            logger.info("成功載入 {} 條 FAQ 資料", faqList.size());
            return faqList;
        } catch (Exception e) {
            logger.error("載入 FAQ JSON 檔案時發生錯誤: {}", e.getMessage());
            e.printStackTrace();
            return Collections.emptyList();
        }
    }
}
