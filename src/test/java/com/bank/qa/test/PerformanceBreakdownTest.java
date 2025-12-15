package com.bank.qa.test;

import com.bank.qa.model.MultiQueryResult;
import com.bank.qa.model.IntentResult;
import com.bank.qa.service.ChatService;
import com.bank.qa.service.GuardrailService;
import com.bank.qa.service.OllamaLlmService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@SpringBootTest
public class PerformanceBreakdownTest {

    @Autowired
    private ChatService chatService;

    @MockBean
    private OllamaLlmService llmService;

    @MockBean
    private GuardrailService guardrailService;

    @Test
    public void shouldMeasureStreamingAndNonStreamingBreakdown() throws Exception {
        // Deterministic, small delays to make breakdown stable.
        // Real retrieval is still executed (lucene + rrf + topN), so retrievalMs will vary by machine.
        when(llmService.isAvailable()).thenReturn(true);

        when(guardrailService.classifyIntent(any())).thenAnswer(inv -> {
            sleepMs(20);
            return new IntentResult("RELATED", null, List.of());
        });

        when(llmService.expandQuery(any())).thenAnswer(inv -> {
            sleepMs(30);
            String q = (String) inv.getArgument(0);
            return new MultiQueryResult(q, q, q);
        });

        // Avoid any accidental generic call usage in this test.
        when(llmService.call(any(), anyDouble())).thenReturn("{}");

        // Non-streaming generateAnswer: deterministic delay.
        when(llmService.generateAnswer(any(), any(List.class))).thenAnswer(inv -> {
            sleepMs(60);
            return "OK";
        });

        // Streaming generateAnswerStreaming: emit a few chunks with deterministic delay.
        doAnswer(inv -> {
            sleepMs(60);
            @SuppressWarnings("unchecked")
            var onChunk = (java.util.function.Consumer<String>) inv.getArgument(2);
            onChunk.accept("A");
            sleepMs(15);
            onChunk.accept("B");
            sleepMs(15);
            onChunk.accept("C");
            return null;
        }).when(llmService).generateAnswerStreaming(any(), any(List.class), any());

        // ========== Non-streaming ==========
        long ns0 = System.nanoTime();
        var nonStream = chatService.processChat("測試查詢：維持率", null, 5);
        long nsTotalMs = (System.nanoTime() - ns0) / 1_000_000;
        System.out.println("\n=== PERF TEST (non-stream) ===");
        System.out.println("totalMs=" + nsTotalMs + " intent=" + nonStream.intent() + " answerLen=" + (nonStream.answer() == null ? 0 : nonStream.answer().length()));

        // ========== Streaming ==========
        CapturingSseEmitter emitter = new CapturingSseEmitter();
        chatService.processStreamingChat("測試查詢：維持率", null, 5, emitter);

        boolean done = emitter.awaitDone(30, TimeUnit.SECONDS);
        if (!done) {
            throw new AssertionError("SSE did not finish within timeout");
        }

        System.out.println("\n=== PERF TEST (stream) ===");
        System.out.println("intentMs=" + emitter.intentMs());
        System.out.println("multiQueryMs=" + emitter.multiQueryMs());
        System.out.println("sourcesMs=" + emitter.sourcesMs());
        System.out.println("thinkingMs=" + emitter.thinkingMs());
        System.out.println("firstChunkMs=" + emitter.firstChunkMs());
        System.out.println("doneMs=" + emitter.doneMs());
        System.out.println("totalMs=" + emitter.totalMs());
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static class CapturingSseEmitter extends SseEmitter {
        private final long startNs = System.nanoTime();
        private final CountDownLatch done = new CountDownLatch(1);

        private final AtomicLong sendCount = new AtomicLong(0);
        private final AtomicLong tIntent = new AtomicLong(0);
        private final AtomicLong tMultiQuery = new AtomicLong(0);
        private final AtomicLong tSources = new AtomicLong(0);
        private final AtomicLong tThinking = new AtomicLong(0);
        private final AtomicLong tFirstChunk = new AtomicLong(0);
        private final AtomicLong tDone = new AtomicLong(0);

        CapturingSseEmitter() {
            super(180000L);
            onCompletion(done::countDown);
            onTimeout(done::countDown);
            onError(e -> done.countDown());
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            long now = System.nanoTime();
            long n = sendCount.incrementAndGet();

            // We intentionally do not parse SseEventBuilder internals.
            // In this codebase, the typical streaming send order is:
            // 1=session, 2=intent, 3=multiquery, 4=sources, 5=thinking, 6+=chunk..., last=done
            if (n == 2) {
                tIntent.compareAndSet(0, now);
            } else if (n == 3) {
                tMultiQuery.compareAndSet(0, now);
            } else if (n == 4) {
                tSources.compareAndSet(0, now);
            } else if (n == 5) {
                tThinking.compareAndSet(0, now);
            } else if (n >= 6) {
                tFirstChunk.compareAndSet(0, now);
            }

            super.send(builder);
        }

        @Override
        public void complete() {
            tDone.compareAndSet(0, System.nanoTime());
            done.countDown();
            super.complete();
        }

        @Override
        public void completeWithError(Throwable ex) {
            tDone.compareAndSet(0, System.nanoTime());
            done.countDown();
            super.completeWithError(ex);
        }

        boolean awaitDone(long timeout, TimeUnit unit) throws InterruptedException {
            return done.await(timeout, unit);
        }

        long msFromStart(long tNs) {
            if (tNs == 0) {
                return -1;
            }
            return (tNs - startNs) / 1_000_000;
        }

        long intentMs() { return msFromStart(tIntent.get()); }

        long multiQueryMs() { return msFromStart(tMultiQuery.get()); }

        long sourcesMs() { return msFromStart(tSources.get()); }

        long thinkingMs() { return msFromStart(tThinking.get()); }

        long firstChunkMs() { return msFromStart(tFirstChunk.get()); }

        long doneMs() { return msFromStart(tDone.get()); }

        long totalMs() { return doneMs(); }
    }
}
