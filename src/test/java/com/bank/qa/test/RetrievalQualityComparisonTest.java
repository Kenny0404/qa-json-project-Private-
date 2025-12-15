package com.bank.qa.test;

import com.bank.qa.model.Faq;
import com.bank.qa.util.JsonLoader;
import com.bank.qa.util.LuceneFaqIndex;
import com.bank.qa.util.VectorUtils;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class RetrievalQualityComparisonTest {

    private record Metrics(double hit1, double hit3, double hit5, double hit10, double mrr, double avgRankFound) {
    }

    @Test
    public void compareBm25VsLucene_qualityReport() {
        List<Faq> faqs = JsonLoader.loadFaq("faq.json");
        assertTrue(faqs.size() > 0);

        List<String> docs = new ArrayList<>(faqs.size());
        for (Faq f : faqs) {
            docs.add((f.getQuestion() == null ? "" : f.getQuestion()) + " " + (f.getAnswer() == null ? "" : f.getAnswer()));
        }

        VectorUtils.BM25 bm25 = new VectorUtils.BM25(1.5, 0.75);
        bm25.fit(docs);

        LuceneFaqIndex lucene = new LuceneFaqIndex();
        lucene.build(faqs);

        int sampleSize = Math.min(120, faqs.size());
        List<Integer> sampleIdx = deterministicSampleIndexes(faqs.size(), sampleSize);

        int topK = 10;

        Metrics bm25Exact = evaluateExactQuestion(sampleIdx, faqs, topK, bm25, lucene, true);
        Metrics luceneExact = evaluateExactQuestion(sampleIdx, faqs, topK, bm25, lucene, false);

        Metrics bm25Short = evaluateShortQuestion(sampleIdx, faqs, topK, bm25, lucene, true);
        Metrics luceneShort = evaluateShortQuestion(sampleIdx, faqs, topK, bm25, lucene, false);

        printReport("EXACT", bm25Exact, luceneExact);
        printReport("SHORT", bm25Short, luceneShort);

        assertTrue(bm25Exact.hit10 >= 0.98, "BM25 exact hit@10 too low");
        assertTrue(luceneExact.hit10 >= 0.98, "Lucene exact hit@10 too low");

        assertTrue(bm25Short.hit10 >= 0.60, "BM25 short hit@10 too low");
        assertTrue(luceneShort.hit10 >= 0.60, "Lucene short hit@10 too low");

        assertTrue(luceneShort.hit10 + 0.10 >= bm25Short.hit10, "Lucene short hit@10 regressed too much vs BM25");
        assertTrue(luceneShort.mrr + 0.10 >= bm25Short.mrr, "Lucene short MRR regressed too much vs BM25");
    }

    private static Metrics evaluateExactQuestion(
            List<Integer> sampleIdx,
            List<Faq> faqs,
            int topK,
            VectorUtils.BM25 bm25,
            LuceneFaqIndex lucene,
            boolean useBm25) {
        List<String> queries = new ArrayList<>(sampleIdx.size());
        List<Integer> targets = new ArrayList<>(sampleIdx.size());
        for (int idx : sampleIdx) {
            String q = faqs.get(idx).getQuestion();
            if (q == null || q.trim().isEmpty()) {
                continue;
            }
            queries.add(q);
            targets.add(idx);
        }
        return evaluate(queries, targets, topK, bm25, lucene, useBm25);
    }

    private static Metrics evaluateShortQuestion(
            List<Integer> sampleIdx,
            List<Faq> faqs,
            int topK,
            VectorUtils.BM25 bm25,
            LuceneFaqIndex lucene,
            boolean useBm25) {
        List<String> queries = new ArrayList<>(sampleIdx.size());
        List<Integer> targets = new ArrayList<>(sampleIdx.size());
        for (int idx : sampleIdx) {
            String q = faqs.get(idx).getQuestion();
            if (q == null) {
                continue;
            }
            String trimmed = q.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int len = trimmed.length();
            int n = Math.min(8, len);
            String shortQ = trimmed.substring(0, n);
            queries.add(shortQ);
            targets.add(idx);
        }
        return evaluate(queries, targets, topK, bm25, lucene, useBm25);
    }

    private static Metrics evaluate(
            List<String> queries,
            List<Integer> targets,
            int topK,
            VectorUtils.BM25 bm25,
            LuceneFaqIndex lucene,
            boolean useBm25) {
        int total = queries.size();
        int hit1 = 0;
        int hit3 = 0;
        int hit5 = 0;
        int hit10 = 0;
        double mrrSum = 0.0;
        double rankFoundSum = 0.0;
        int foundCount = 0;

        for (int i = 0; i < total; i++) {
            String q = queries.get(i);
            int target = targets.get(i);

            List<Integer> ranked;
            if (useBm25) {
                ranked = bm25.getRankedDocIds(q, topK);
            } else {
                ranked = lucene.search(q, topK).stream().map(LuceneFaqIndex.Hit::faqIndex).toList();
            }

            int rank = indexOf(ranked, target);
            if (rank == 1) {
                hit1++;
            }
            if (rank > 0 && rank <= 3) {
                hit3++;
            }
            if (rank > 0 && rank <= 5) {
                hit5++;
            }
            if (rank > 0 && rank <= 10) {
                hit10++;
            }
            if (rank > 0) {
                mrrSum += 1.0 / rank;
                rankFoundSum += rank;
                foundCount++;
            }
        }

        double denom = total == 0 ? 1.0 : total;
        double avgRank = foundCount == 0 ? 0.0 : rankFoundSum / foundCount;
        return new Metrics(
                hit1 / denom,
                hit3 / denom,
                hit5 / denom,
                hit10 / denom,
                mrrSum / denom,
                avgRank);
    }

    private static int indexOf(List<Integer> ranked, int target) {
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i) == target) {
                return i + 1;
            }
        }
        return -1;
    }

    private static List<Integer> deterministicSampleIndexes(int size, int sampleSize) {
        int step = 17;
        Set<Integer> picked = new HashSet<>();
        List<Integer> result = new ArrayList<>(sampleSize);
        int x = 0;
        while (result.size() < sampleSize) {
            x = (x + step) % size;
            if (picked.add(x)) {
                result.add(x);
            }
        }
        return result;
    }

    private static void printReport(String label, Metrics bm25, Metrics lucene) {
        System.out.println("=== Retrieval Quality Comparison: " + label + " ===");
        System.out.println("BM25   hit@1=" + bm25.hit1 + " hit@3=" + bm25.hit3 + " hit@5=" + bm25.hit5 + " hit@10=" + bm25.hit10 + " mrr=" + bm25.mrr + " avgRankFound=" + bm25.avgRankFound);
        System.out.println("Lucene hit@1=" + lucene.hit1 + " hit@3=" + lucene.hit3 + " hit@5=" + lucene.hit5 + " hit@10=" + lucene.hit10 + " mrr=" + lucene.mrr + " avgRankFound=" + lucene.avgRankFound);
        System.out.println("Diff   hit@1=" + (lucene.hit1 - bm25.hit1) + " hit@3=" + (lucene.hit3 - bm25.hit3) + " hit@5=" + (lucene.hit5 - bm25.hit5) + " hit@10=" + (lucene.hit10 - bm25.hit10) + " mrr=" + (lucene.mrr - bm25.mrr));
    }
}
