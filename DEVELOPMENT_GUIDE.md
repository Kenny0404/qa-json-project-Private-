# 開發規範（Development Guide）

本文件定義本專案的程式放置規範、分層責任、程式碼撰寫與註解原則。

- 適用範圍：`src/main/java/com/bank/qa/**` 與 `src/test/java/**`
- 技術基線：Spring Boot 3.2.x、Java 17、Thymeleaf（僅用於頁面輸出）、Ollama（LLM）、BM25 + RRF（檢索/融合）、SSE（串流回應）

---

## 1. 專案目標與基本原則

### 1.1 目標

- 對封閉領域 FAQ 提供檢索（BM25 + RRF）與生成式回答（RAG + LLM）。
- 提供 SSE 串流，讓前端能即時顯示 LLM 回答。
- 具備護欄：意圖分類（RELATED/UNRELATED/UNCLEAR）、連續不相關問題升級聯繫資訊。

### 1.2 原則（必守）

- 單一責任：每個類別/方法只做一件事。
- 分層清楚：HTTP 層不處理商業邏輯；商業邏輯不綁定框架細節。
- 依賴方向固定：高層依賴抽象（介面），低層實作可替換。
- 不重複呼叫 LLM：同一個 request 內，Multi-Query/生成等呼叫需可追蹤且避免重覆。

---

## 2. 套件/資料夾放置規範

> 目前專案已存在 `controller/`, `service/`, `service/impl/`, `model/`, `util/`。
> 未來若需要擴充，可新增 `dto/`, `client/`, `rag/`, `config/`, `exception/`。

### 2.1 根目錄

- `README.md`：產品/架構/快速啟動
- `DEVELOPMENT_GUIDE.md`：開發規範（本文件）

### 2.2 `src/main/java/com/bank/qa` 下的標準分層

- `QaApplication.java`
  - Spring Boot 啟動點

- `controller/`
  - 放置所有 Web/REST Controller（路由、輸入驗證、呼叫服務、回傳 DTO）
  - 例：`FaqController`, `PageController`

- `service/`
  - 放置服務介面（use case / 流程介面）
  - 例：`ChatService`, `FaqService`, `GuardrailService`, `SessionService`, `OllamaLlmService`

- `service/impl/`
  - 放置服務實作（實際流程編排）
  - 例：`ChatServiceImpl`, `FaqServiceImpl`, `GuardrailServiceImpl`, `SessionServiceImpl`, `OllamaLlmServiceImpl`

- `model/`
  - 放置領域模型（domain model）與核心資料結構
  - 原則：不依賴 Spring Web 型別（例如 `HttpServletRequest`, `SseEmitter`）
  - 例：`Faq`, `ChatSession`, `IntentResult`, `MultiQueryResult`, `RagSearchResult`

- `util/`
  - 放置與 domain 無關或可重用的純工具
  - 原則：不要讓 `util` 變成所有東西的垃圾桶；若工具強耦合 RAG，建議改放 `rag/`（見 2.3）
  - 例：`JsonLoader`（資料載入工具）

### 2.3 建議新增的套件（可選，但建議逐步落地）

- `dto/`
  - API request/response 專用資料結構
  - 原則：Controller 對外只使用 DTO，避免直接暴露 `model/`（domain）

- `client/ollama/`
  - 外部服務整合（HTTP 呼叫、串流讀取、timeout、重試）
  - 原則：只管「怎麼打 API」，不管「提示詞內容」

- `rag/`
  - RAG 組件分離（檢索、融合、prompt 組裝）
  - 建議子套件：
    - `rag/retriever/`：BM25 index/search
    - `rag/fusion/`：RRF
    - `rag/prompt/`：PromptBuilder/Template

- `config/`
  - 統一管理設定（建議使用 `@ConfigurationProperties`）

- `exception/`
  - 自訂例外與統一例外處理（`@RestControllerAdvice`）

---

## 3. 分層責任與依賴方向

### 3.1 依賴方向（必守）

- `controller` -> `service`（只依賴介面/用例）
- `service.impl` -> `service` +（必要時）`model`/`util`/`client`/`rag`
- `model` 不應依賴 `controller/service`（避免循環依賴）

### 3.2 各層責任清單

#### 3.2.1 Controller（HTTP 層）

- 負責：
  - 路由與輸入/參數驗證（例如空字串、topN 範圍）
  - 呼叫 service
  - 將結果轉成 DTO 回傳
- 禁止：
  - 撰寫檢索/融合/LLM/prompt 組裝細節
  - 直接管理 thread pool、直接呼叫外部 HTTP

#### 3.2.2 Service（用例/流程層）

- 負責：
  - 業務流程編排（guardrail -> retrieval -> generation）
  - 交易/流程規則（例如「連續 3 次不相關才升級」）
- 建議：
  - 介面描述清楚輸入/輸出（使用 DTO 或 domain object）
  - Streaming 服務要保證完整事件生命週期（見第 6 章 SSE 契約）

#### 3.2.3 Client（外部整合層）

- 負責：
  - 呼叫 Ollama 的 HTTP 連線建立、timeout 設定、streaming 讀取
- 禁止：
  - 內嵌商業規則或 prompt 模板（應交給 rag/prompt 或 service）

#### 3.2.4 Model/Domain（領域模型）

- 負責：
  - 表達資料本體（FAQ/Session/IntentResult 等）
  - 可包含少量與狀態一致性相關的方法（例如計數器增減、最後活躍時間更新）
- 禁止：
  - 放 HTTP/SSE/JSON parsing 的框架細節

---

## 4. 程式碼撰寫規範

### 4.1 命名規範

- package：全小寫（例：`com.bank.qa.service.impl`）
- class：PascalCase（例：`GuardrailServiceImpl`）
- method/field：camelCase（例：`processStreamingChat`）
- 常數：`UPPER_SNAKE_CASE`

### 4.2 依賴注入

- 新增程式碼建議採用「建構子注入（constructor injection）」
- 避免 field injection（`@Autowired` 直接標在欄位）造成測試困難與不可變性不足

### 4.3 Logging

- 使用 SLF4J（`LoggerFactory.getLogger(...)`）
- 建議：
  - `info`：重要狀態轉換（模式切換、session 建立/清理）
  - `warn`：可恢復問題（LLM 暫時不可用、解析失敗採 fallback）
  - `error`：不可恢復或影響功能的錯誤（外部呼叫失敗、資料載入失敗）
- 避免：
  - 在 log 中輸出敏感資料（電話、email、使用者輸入全文，除非必要）

### 4.4 Exception 與錯誤回傳

- 對外 API 建議統一錯誤格式（例如 `{"message": "..."}`）
- Streaming（SSE）發生錯誤時：
  - 送出 `event: error`（帶 message）
  - 保證最後仍會關閉 emitter（避免掛線）

---

## 5. 註解/Javadoc 規範

### 5.1 何時要寫

- `public` 類別/方法：需有簡短 Javadoc，描述「用途 + 輸入/輸出 + 重要副作用」
- 複雜邏輯：寫區塊註解標示「階段」或「為何這樣做（why）」
- 重要不變量/約束：必寫（例如「同一個 session 同時只能有一個 streaming」）

### 5.2 寫什麼（重點是 why）

- 註解優先寫：
  - trade-off（為什麼選 BM25 + RRF）
  - 參數理由（為什麼 `rrfK=60`）
  - 邊界處理（為什麼空字串回空列表）

### 5.3 什麼不寫

- 不寫與程式碼重複的 what（例如 `i++` 不需要註解）
- 不留過期註解：程式改了註解要一起改

---

## 6. SSE（Server-Sent Events）事件契約（Streaming 規範）

### 6.1 事件順序（建議）

1. `session`：回傳/建立 sessionId
2. `intent`：意圖分類結果（RELATED/UNRELATED/UNCLEAR）
3. （若 RELATED）`multiquery`：Multi-Query 擴展結果
4. `sources`：檢索到的 FAQ 來源（可先送，讓前端先顯示來源）
5. （可選）`thinking`：提示前端顯示「正在生成」
6. `chunk`：LLM 回答的片段（多次）
7. `done`：結束

### 6.2 Payload 規範（JSON）

- `session`
  - `{ "sessionId": "...", "newSession": true|false }`
- `intent`
  - `{ "intent": "RELATED|UNRELATED|UNCLEAR", "message": "...", "suggestKeywords": ["...", "..."] }`
- `multiquery`
  - `{ "original": "...", "keyword": "...", "colloquial": "..." }`
- `sources`
  - `{ "sources": [ { "id": 1, "question": "...", "answer": "...", "score": 0.123 } ] }`
- `chunk`
  - `{ "content": "..." }`
- `error`
  - `{ "message": "..." }`

### 6.3 結束/取消

- 後端必須能處理：
  - 前端中途關閉連線（使用者按停止、或網路中斷）
  - timeout
- 建議實作：
  - `SseEmitter` 的 `onCompletion/onTimeout/onError` 中停止後端背景工作
  - LLM streaming 要可取消，避免 client 斷線後仍把整段回應讀完

---

## 7. RAG/LLM 呼叫邊界規範（避免混亂與重覆）

### 7.1 建議的 RAG 職責拆分

- Retrieval（檢索）：只做 BM25/RRF，絕不呼叫 LLM
- Generation（生成）：只根據 contexts 組 prompt 並呼叫 LLM
- Guardrail（護欄）：意圖分類/升級邏輯，不與檢索混寫

### 7.2 同一個 request 內避免重覆呼叫

- Multi-Query：每個 request 至多呼叫一次（除非有明確理由）
- Streaming 與 non-streaming 不要同時生成兩次答案

### 7.3 Prompt 管理

- Prompt 建議集中管理（`rag/prompt`），避免散落在多個 service
- Prompt 中需明確規則：
  - 只使用參考資料回答
  - 無資料時回覆固定拒答句

---

## 8. 設定管理

- 所有可調參數應放 `application.properties` 或環境變數
- 建議將以下設定整理為 `@ConfigurationProperties`：
  - `ollama.*`
  - `rag.*`
  - `session.*`
  - `guardrail.*`

---

## 9. 安全與前端輸出規範

- 前端以 Markdown 顯示 LLM 回答時，需防 XSS：
  - 允許 Markdown，但必須做 HTML sanitize（建議使用 DOMPurify 等）
- 使用者輸入為不可信內容：
  - 後端需做基本長度限制與空字串處理
  - 不在 log 中輸出完整敏感內容

---

## 10. 測試規範（最低要求）

- Unit Test：
  - tokenization/BM25/RRF 的正確性
  - 空字串/null 邊界
- Integration Test（建議）：
  - Controller + Service 流程（mock LLM）
  - SSE 事件順序與 `done/error` 保證

---

## 11. 效能量測（Performance Profiling）

本章提供「可重現、可比較」的效能量測方法，用來定位回應速度過慢的瓶頸（LLM / 檢索 / 串流送出）。

### 11.1 指標定義

- **TTFB（Time To First Byte / First Chunk）**
  - Streaming 模式下，從請求開始到前端收到第一個 `chunk` 的時間。
  - 使用者體感最直接的「開始打字要等多久」。

- **Total（Total Time）**
  - Streaming 模式下，從請求開始到 `done` 的時間。
  - 非串流模式下，從 request 開始到 `processChat()` 回傳的時間。

### 11.2 內建 PERF log（線上觀測）

專案已在後端加入分段耗時 log（可直接從 console / log 檔觀測）：

- **`ChatServiceImpl`**
  - `PERF(stream)`：
    - `intentMs`：意圖分類耗時
    - `multiQueryMs`：Multi-Query expansion 耗時
    - `retrievalMs`：檢索（searchRagWithMultiQuery）耗時
    - `firstChunkMs`：第一個 chunk 出現時間（TTFB）
    - `totalMs`：整體串流完成時間
  - `PERF(nonstream)`：
    - `intentMs`：意圖分類耗時
    - `totalMs`：整體回應時間

- **`FaqServiceImpl`**
  - `PERF(retrieval) api=searchRag`：
    - `expandMs`：Multi-Query expansion（僅 non-streaming 的 searchRag 會做）
    - `luceneMs`：Lucene 檢索（包含每個 query 的 search loop）
    - `rrfMs`：RRF 融合
    - `topNMs`：Top-N materialize
    - `totalMs`
  - `PERF(retrieval) api=searchRagWithMultiQuery`：
    - `luceneMs / rrfMs / topNMs / totalMs`

建議先用真實問題跑一次，收集 10-20 筆 `PERF(stream)` 與 `PERF(retrieval)`，即可初步判斷慢點：

- 若 `intentMs` 或 `multiQueryMs` 明顯偏大：通常是 **LLM call** 慢。
- 若 `retrievalMs` 偏大：通常是 **Lucene search / 查詢數量 / topK**。
- 若 `firstChunkMs` 遠大於 `intentMs + multiQueryMs + retrievalMs`：通常是 **LLM 串流啟動或 SSE 發送**卡住。

### 11.3 精準量測測試（可重現、可比較）

為了避免 Ollama/外部服務的波動影響判讀，提供 `PerformanceBreakdownTest`：

- 測試位置：
  - `src/test/java/com/bank/qa/test/PerformanceBreakdownTest.java`
- 核心策略：
  - `@MockBean OllamaLlmService` + `@MockBean GuardrailService`
  - 以「固定 delay」模擬 LLM 耗時，並保留真實檢索（Lucene + RRF），讓檢索段耗時可被觀測
  - Streaming 測試以自訂 `SseEmitter` 捕捉事件時間點：intent / multiquery / sources / thinking / firstChunk / done

#### 11.3.1 如何執行

```bash
mvn -B test -Dtest=PerformanceBreakdownTest
```

#### 11.3.2 如何解讀輸出

測試會印出：

- `PERF TEST (non-stream)`：
  - `totalMs=...`：非串流總耗時

- `PERF TEST (stream)`：
  - `intentMs`：intent event 送出時間
  - `multiQueryMs`：multiquery event 送出時間
  - `sourcesMs`：sources event 送出時間
  - `thinkingMs`：thinking event 送出時間
  - `firstChunkMs`：第一個 chunk 送出時間（TTFB）
  - `doneMs/totalMs`：完成時間

同時會在 log 看到 `PERF(stream)` 與 `PERF(retrieval)`，可交叉驗證。

#### 11.3.3 測試設計注意事項

- 此測試目的為「定位瓶頸」，不是追求絕對值；不同機器的數字會不同，但趨勢與相對大小應一致。
- 若要測真實 Ollama：請另開 Integration 測試（不 mock LLM），並固定 model / prompt / topN/topK，避免變因。

### 11.4 常見慢因與對策（依量測結果選擇）

- **LLM 呼叫太多**（`intentMs`、`multiQueryMs` 偏大）
  - 減少/合併 LLM call
  - 對熱門 query 做快取
  - 或將 intent 分類改為規則/詞典（必要時才走 LLM）

- **TTFB 過大**（`firstChunkMs` 偏大）
  - 提前送 `thinking`（改善體感）
  - 並行化 intent 與 multiquery（降低等待時間）

- **retrievalMs 偏大**
  - 降低 `rag.retrieval-top-k`
  - 降低 multiquery 數量
  - 針對 Lucene search 做快取或優化 query 清理

---

## 11. 變更落地建議（逐步強制化）

> 本文件先定義規範；若要「嚴格」落地，建議後續導入工具。

- 格式化：Spotless（`spotless:apply` / `spotless:check`）
- 靜態檢查：Checkstyle 或 SpotBugs
- CI：每次 PR 執行 `mvn test` + formatter check

---
