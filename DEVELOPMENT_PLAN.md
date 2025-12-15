# 開發流程計劃（Development Plan）

本文件依據先前盤點的「明顯可優化/需修正點（P0/P1/P2）」提出一份可執行的開發計劃，包含里程碑、PR 切分、驗收標準、測試策略、風險與回滾。

- 參考規範：`DEVELOPMENT_GUIDE.md`
- 目標：在不大改架構的前提下，先修正 P0/P1 的功能/可靠度問題，再逐步提升可維護性、資安與效能。

## 文件關係與遵循方式（Plan vs Guide）

- `DEVELOPMENT_GUIDE.md`：定義「怎麼開發」
  - 程式放置（package/folder）、分層責任、DI/logging、SSE 契約、RAG/LLM 邊界、安全與測試最低要求。
- `DEVELOPMENT_PLAN.md`（本文件）：定義「先做什麼」
  - 里程碑排序、PR 切分策略、每階段驗收/測試、風險與回滾。

若兩者內容有衝突：以 `DEVELOPMENT_GUIDE.md` 的工程規範為準；本文件負責描述優先順序與交付策略。

---

## 0. 背景與問題摘要

### 0.1 P0（功能錯誤/契約不一致）

- 前端 `index.html` 呼叫的 API 與後端不一致
  - 前端：`GET /api/faq/stats`、`DELETE /api/faq/session/{sessionId}`
  - 後端：`GET /api/faq/status`、`POST /api/faq/session/clear?sessionId=`
  - 回傳欄位命名亦不一致（例如 `totalFaq/mode/ollamaAvailable` vs `faqCount/mode/llmAvailable`）

- Streaming 路徑可能重複呼叫 LLM（成本/延遲上升）
  - `ChatServiceImpl` 會做 `expandQuery`，而 `FaqServiceImpl.searchRag` 內部也會再做一次 `expandQuery`，甚至可能再做非 streaming 的 `generateAnswer`。

### 0.2 P1（可靠度/資源/資安風險）

- 使用者按「停止」只關閉前端 EventSource，後端仍可能繼續把 Ollama streaming 讀完（資源浪費）。
- `Executors.newCachedThreadPool()` 無上限 thread 風險。
- 前端用 `marked.parse()` 將 LLM 輸出塞進 `innerHTML`，缺少 sanitize，有 XSS 風險。
- 護欄計數器前端/後端各做一套，容易不一致（前端甚至會在 `done` 覆寫答案）。

### 0.3 P2（可維護性/一致性/擴充）

- `ChatSession.messages` 使用 `ArrayList`，同一 session 多請求併發有資料競態風險。
- Smart Context 的資料來源不一致（`lastContext` vs `getRecentContext()`）。

### 0.4 上下文功能狀態（待完成）

- 目前已暫時移除「多輪上下文/Smart Context」相關功能（含前端 toggle、後端 `contextEnabled` 與 `effectiveQuery` 事件），系統回到「單輪查詢」。
- 原因：上下文策略不穩定且容易造成查詢混淆，先以穩定性優先。
- 後續若要恢復，需重新設計與驗收：
  - 明確定義上下文來源與邊界（不污染檢索 query）
  - 提供可量化的套用/不套用決策依據與回歸測試
  - UI 清楚標示上下文是否啟用與原因
- 檢索效能可再優化（BM25 term freq cache / 或導入 Lucene）。
- Repo 衛生與規範強制不足（缺 `.gitignore`、缺 formatter/lint/CI gate）。
- 測試覆蓋不足（缺 SSE/Controller/流程測試）。

---

## 1. 交付策略與 Definition of Done（DoD）

### 1.1 交付策略

- 小步快跑：以「小 PR、可回滾、可驗收」為原則。
- 向下相容：P0 的 API 修正，優先採「同時支援舊/新端點」一段時間，避免前端或外部客戶端瞬間壞掉。
- 減少重構面積：先把重覆呼叫/契約不一致修掉，再做結構性重整（例如拆 retrieval/generation）。

### 1.2 Definition of Done（每個 PR 都要符合）

- 功能：對應需求的功能可手動驗證。
- 測試：新增/更新的測試通過（至少 unit；流程類變更要有 integration）。
- 文件：若 API/事件契約有變更，需更新 `README.md` 或補充本文件。
- 回滾：PR 說明需包含回滾方式（可快速 revert 或 feature flag）。

---

## 2. 里程碑總覽（建議順序）

> 時程僅作參考；可依團隊人力調整。

- M1：P0 API 契約對齊（前後端一致）
- M2：消除 Streaming 重覆呼叫 LLM（RAG 邊界清楚化）
- M3：Streaming 可取消 + bounded thread pool（資源控制）
- M4：資安與護欄一致性（XSS + escalation 單一事實來源）
- M5：Session thread-safety + Smart Context 一致性
- M6：工程化落地（.gitignore、formatter、CI、測試補齊）
- M7（選配）：檢索效能/品質升級（BM25 cache / Lucene）

---

## 3. 詳細里程碑計劃（含 PR 切分）

### M1：API 契約對齊（P0）

#### 目標

- 前端與後端端點/欄位一致，避免「前端載入統計失敗」與「session 清理無效」。

#### 主要改動範圍

- `src/main/resources/templates/index.html`
- `src/main/java/com/bank/qa/controller/FaqController.java`

#### PR 切分建議

- PR1-1（相容層）：後端補齊前端正在呼叫的端點
  - 新增 `GET /api/faq/stats`（回傳 `totalFaq`, `mode`, `ollamaAvailable` 等前端需要的欄位）
  - 新增 `DELETE /api/faq/session/{sessionId}`（或同義端點）
  - 保留既有 `/status` 與 `/session/clear` 不移除（至少 1~2 個版本再 deprecate）

- PR1-2（前端對齊）：前端統一改呼叫「正式端點」
  - 決定以 `/status` 或 `/stats` 為 canonical（建議 `/status` 或改名為 `/stats` 但文件要統一）
  - 統一 session 清理行為

#### 驗收標準

- 開啟 `/`：右側統計區能正常顯示 FAQ 數量與模式，不出現 console error。
- 按「新建 Session」：能確實清除後端 session（server log 可觀察到清除行為）。

#### 測試策略

- Integration Test（建議新增）：
  - `GET /api/faq/stats` 回傳 JSON schema（必要欄位存在）
  - `DELETE /api/faq/session/{id}` 回傳 200 且 session count 下降

#### 風險與回滾

- 風險：若外部客戶端依賴舊端點，直接改會破壞。
- 緩解：先做 PR1-1（相容層），再做 PR1-2（前端轉向）。
- 回滾：revert PR1-2 仍可使用相容端點；或 revert PR1-1 退回原行為。

---

### M2：消除 Streaming 重覆呼叫 LLM（P0/P1）

#### 目標

- 每個請求（request）在 Streaming 路徑中：
  - Multi-Query 擴展最多一次
  - 生成答案（LLM）只走一次（streaming）

#### 主要改動範圍

- `ChatServiceImpl`
- `FaqService` / `FaqServiceImpl`
- `OllamaLlmService` / `OllamaLlmServiceImpl`

#### PR 切分建議

- PR2-1（可觀測性）：先加上「LLM 呼叫次數」的 logging 或計數
  - 每個 request log：expandQuery 次數、generate 次數
  - 目的：讓後續重構有量化驗證

- PR2-2（責任拆分）：把 Retrieval 與 Generation 拆開
  - 將 `FaqServiceImpl` 中「檢索」與「生成」拆成兩條路徑
  - Streaming path 僅做：guardrail -> expandQuery（一次）-> retrieve sources -> generate streaming

- PR2-3（測試）：加 integration test 驗證不重覆呼叫
  - 使用 `@MockBean OllamaLlmService` 驗證 `expandQuery()`/`generateAnswerStreaming()` 的呼叫次數

#### 驗收標準

- Server log 可看到：同一次 streaming request 不會出現兩次 `expandQuery`。
- 端到端行為不變：sources 仍會回傳、chunk 仍會串流、done 仍會送出。

#### 測試策略

- Unit Test：
  - retrieval 結果排序不變（既有 `FaqServiceTest` 可維持）
- Integration Test：
  - mock LLM：驗證方法呼叫次數
  - SSE 基本事件順序（至少能收到 `sources`、`chunk`、`done`）

#### 風險與回滾

- 風險：拆分 service 介面會影響呼叫端。
- 緩解：PR2-2 盡量保持向下相容（先新增方法，不立刻刪舊方法）。
- 回滾：revert PR2-2 恢復原本行為；或保留舊方法當 fallback。

---

### M3：Streaming 可取消 + bounded thread pool（P1）

#### 目標

- 使用者按停止/斷線後：
  - 後端能停止背景工作與 LLM streaming（不再繼續讀完全部 token）
- thread pool 有上限與可配置，避免高併發時爆 thread。

#### 主要改動範圍

- `ChatServiceImpl`（SseEmitter 生命週期）
- `OllamaLlmServiceImpl`（streaming client 可取消）
- 新增 `config`（thread pool / timeout 參數化，若採用）

#### PR 切分建議

- PR3-1（bounded executor）：替換 `newCachedThreadPool()`
  - 以可配置的固定大小 thread pool / `ThreadPoolTaskExecutor` 取代
  - 加入 queue 限制與拒絕策略（避免無限制堆積）

- PR3-2（取消機制）：讓 Ollama streaming 可被取消
  - 設計「取消訊號」：例如 `AtomicBoolean cancelled` 或回傳可 `close()` 的 handle
  - 在 `SseEmitter.onCompletion/onTimeout/onError` 觸發取消

- PR3-3（測試/手動驗證腳本）：
  - 手動：前端按停止後，後端 log 應顯示取消，且不再持續輸出 chunk
  - 自動：可用 WireMock/MockWebServer 模擬長串流並測試取消（選配，但建議）

#### 驗收標準

- 在生成中按「停止」：
  - SSE 立即停止
  - 後端不再繼續讀取 Ollama response（可由 log/metrics 觀察）
- 在壓力測試（簡單並發）下：thread 數不會無限成長。

#### 風險與回滾

- 風險：改動 streaming client 容易引入新 bug。
- 緩解：先導入 bounded executor（PR3-1），再做取消（PR3-2）。
- 回滾：revert PR3-2 恢復原 streaming；取消功能可用 feature flag 關閉。

---

### M4：資安與護欄一致性（P1）

#### 目標

- 前端渲染 LLM 回答時，避免 XSS。
- 護欄升級（連續不相關）以後端為單一事實來源（Single Source of Truth）。

#### 主要改動範圍

- `index.html`
- `GuardrailServiceImpl` / `ChatServiceImpl`

#### PR 切分建議

- PR4-1（前端 sanitize）：
  - 在前端加入 sanitize（例如 DOMPurify）
  - 將 `marked.parse()` 的結果 sanitize 後再塞入 DOM

- PR4-2（護欄一致性）：
  - 移除前端自行計數/覆寫答案的邏輯
  - 後端明確用 SSE event 傳遞 escalation 結果（例如 `event: contact` 或在 `intent` 中帶旗標）

#### 驗收標準

- 嘗試輸入誘導 LLM 產出 HTML/JS：前端不執行腳本。
- 連續多次不相關問題：升級聯繫資訊的行為一致（不依賴前端計數）。

#### 風險與回滾

- 風險：sanitize 可能改變部分 Markdown 顯示。
- 緩解：只 sanitize 不可信 HTML（保留必要 tags），並人工檢查常見格式。
- 回滾：revert PR4-1（不建議，但可行）；PR4-2 可用前端 fallback（短期）。

---

### M5：Session thread-safety + Smart Context 一致性（P2）

#### 目標

- 同一個 session 多請求併發時，不會造成資料競態或訊息歷史錯亂。
- Smart Context 的來源與策略一致，避免「清 lastContext 但實際用 messages」的落差。

#### 主要改動範圍

- `ChatSession`
- `SessionServiceImpl`
- `ChatServiceImpl` / `FaqServiceImpl`

#### PR 切分建議

- PR5-1（thread-safety）：
  - 為同一 session 的寫操作加鎖（或改用 thread-safe 結構）
  - 可選：限制同一 session 同時只能有一個 active streaming request（避免兩個串流互相污染）

- PR5-2（context 統一）：
  - 明確定義「上下文」來源：
    - 方案 A：完全使用 `messages`（推薦）
    - 方案 B：使用 `lastContext` 作為額外摘要，但要確保所有路徑一致讀寫

#### 驗收標準

- 併發測試（簡單）：同一 session 同時兩個請求時，不會拋例外、session history 不會壞掉。
- Smart Context 行為可預期：清除/不清除的規則一致。

---

### M6：工程化落地（規範可被強制）（P2）

#### 目標

- Repo 衛生與一致性提升，讓規範不只靠口頭。

#### PR 切分建議

- PR6-1：新增 `.gitignore`（忽略 `target/`、`.DS_Store` 等）
- PR6-2：導入 formatter（Spotless）
- PR6-3：導入 CI（GitHub Actions：`mvn test` + formatter check）
- PR6-4：補齊 integration tests（至少覆蓋：API stats/status、SSE done/error 保證）

#### 驗收標準

- PR 合併前 CI 必過。
- `mvn test` 可在乾淨環境跑過。

---

### M7（選配）：檢索效能/品質升級（P2）

#### 目標

- 提升 BM25 計算效能或可替換性（例如導入 Lucene）。

#### 方案

- 方案 A（低成本）：BM25 fit 時預先建立每篇 doc 的 term frequency cache
- 方案 B（較大改動）：使用 Lucene（BM25 + Analyzer）取代自寫 BM25

#### 驗收標準

- 在固定 FAQ 規模下，檢索延遲下降（需基準測試）。

---

## 4. 測試與驗收總表

### 4.1 必做（每次改動都要）

- `mvn test` 全數通過
- 手動驗證：
  - `/` 頁面正常
  - streaming 回答能顯示
  - stop 按鈕能停止（若已做 M3）

### 4.2 建議新增的測試項目（逐步補齊）

- Controller integration tests：stats/status/session clear
- SSE contract tests：事件順序、done/error 一定送出
- Mock LLM tests：驗證每 request 呼叫次數（避免重覆）

---

## 5. 風險管理與回滾策略（總則）

- 即使尚未上線，本專案仍建議保留「風險與回滾」章節：開發階段的回滾以快速 `revert` / 回復上一個可用 tag（里程碑版本）為主，不需要完整 production runbook，但需要確保每個 PR 都能安全撤回。
- 所有對外契約改動：先相容、後淘汰（deprecate），並更新 README。
- Streaming 改動：
  - 優先加觀測（log/metric）再改行為
  - 改動要能用 feature flag 關閉（若未導入 flag，至少能快速 revert）
- 版本管理：
  - 建議採用 milestone tag（例如 `v1.0`, `v1.1`）方便回溯

---

## 6. 建議的 PR 順序（最小可行路線）

1. PR1-1（後端補齊相容端點）
2. PR1-2（前端改用正式端點）
3. PR2-1（LLM 呼叫可觀測性）
4. PR2-2（消除重覆呼叫：拆 retrieval/generation）
5. PR3-1（bounded executor）
6. PR3-2（streaming 可取消）
7. PR4-1（前端 sanitize）
8. PR4-2（護欄一致性：後端單一事實來源）
9. PR5-1/PR5-2（session thread-safety + context 統一）
10. PR6-*（工程化：.gitignore/formatter/CI/tests）

---
