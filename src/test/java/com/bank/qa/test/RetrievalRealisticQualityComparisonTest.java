package com.bank.qa.test;

import com.bank.qa.model.Faq;
import com.bank.qa.util.JsonLoader;
import com.bank.qa.util.LuceneFaqIndex;
import com.bank.qa.util.VectorUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class RetrievalRealisticQualityComparisonTest {

    private record QueryCase(String label, String query, Set<Integer> targetIdx) {
    }

    private record Metrics(int total, double hit1, double hit3, double hit5, double hit10, double mrr, double avgRankFound) {
    }

    @Test
    public void compareBm25VsLucene_realisticQueries() throws Exception {
        List<Faq> faqs = JsonLoader.loadFaq("faq.json");
        assertTrue(faqs.size() > 0);

        Map<Integer, Integer> idToIndex = new HashMap<>();
        for (int i = 0; i < faqs.size(); i++) {
            idToIndex.put(faqs.get(i).getId(), i);
        }

        List<String> docs = new ArrayList<>(faqs.size());
        for (Faq f : faqs) {
            docs.add(safe(f.getQuestion()) + " " + safe(f.getAnswer()));
        }

        VectorUtils.BM25 bm25 = new VectorUtils.BM25(1.5, 0.75);
        bm25.fit(docs);

        LuceneFaqIndex lucene = new LuceneFaqIndex();
        lucene.build(faqs);

        List<QueryCase> cases = new ArrayList<>();
        cases.addAll(buildFromDuplicateGroups(idToIndex));
        cases.addAll(buildFromFaqSamples(faqs));

        int topK = 10;

        Map<String, Metrics> bm25ByLabel = evaluateByLabel(cases, topK, bm25, lucene, true);
        Map<String, Metrics> luceneByLabel = evaluateByLabel(cases, topK, bm25, lucene, false);

        printReport("OVERALL", bm25ByLabel.get("__ALL__"), luceneByLabel.get("__ALL__"));

        for (String label : bm25ByLabel.keySet()) {
            if ("__ALL__".equals(label)) {
                continue;
            }
            printReport(label, bm25ByLabel.get(label), luceneByLabel.get(label));
        }

        Metrics bm25All = bm25ByLabel.get("__ALL__");
        Metrics luceneAll = luceneByLabel.get("__ALL__");

        assertTrue(bm25All.total > 0);
        assertTrue(luceneAll.total > 0);

        assertTrue(luceneAll.hit10 >= bm25All.hit10 - 0.15, "Lucene overall hit@10 regressed too much");
        assertTrue(luceneAll.mrr >= bm25All.mrr - 0.15, "Lucene overall MRR regressed too much");

        Metrics bm25Dup = bm25ByLabel.get("DUP_GROUP");
        Metrics luceneDup = luceneByLabel.get("DUP_GROUP");
        if (bm25Dup != null && luceneDup != null && bm25Dup.total >= 20) {
            assertTrue(luceneDup.hit10 >= bm25Dup.hit10 - 0.20, "Lucene DUP_GROUP hit@10 regressed too much");
        }
    }

    private static List<QueryCase> buildFromFaqSamples(List<Faq> faqs) {
        int sampleSize = Math.min(120, faqs.size());
        List<Integer> sampleIdx = deterministicSampleIndexes(faqs.size(), sampleSize);

        List<QueryCase> cases = new ArrayList<>();

        for (int idx : sampleIdx) {
            Faq f = faqs.get(idx);
            String q = safe(f.getQuestion()).trim();
            String a = safe(f.getAnswer()).trim();
            if (q.isEmpty()) {
                continue;
            }

            Set<Integer> target = Set.of(idx);

            cases.add(new QueryCase("EXACT", q, target));

            String shortQ = q.substring(0, Math.min(8, q.length()));
            if (!shortQ.isEmpty()) {
                cases.add(new QueryCase("SHORT", shortQ, target));
            }

            String keywords = keywordLikeQuery(q);
            if (!keywords.isEmpty()) {
                cases.add(new QueryCase("KEYWORDS", keywords, target));
            }

            String colloquial = colloquialize(q);
            if (!colloquial.equals(q)) {
                cases.add(new QueryCase("COLLOQUIAL", colloquial, target));
            }

            String dropped = dropOneChar(q);
            if (!dropped.equals(q) && !dropped.isEmpty()) {
                cases.add(new QueryCase("DROP_CHAR", dropped, target));
            }

            String swapped = swapAdjacent(q);
            if (!swapped.equals(q) && !swapped.isEmpty()) {
                cases.add(new QueryCase("SWAP_CHAR", swapped, target));
            }

            String snippet = answerSnippetQuery(a);
            if (!snippet.isEmpty()) {
                cases.add(new QueryCase("ANSWER_SNIPPET", snippet, target));
            }
        }

        return cases;
    }

    private static List<QueryCase> buildFromDuplicateGroups(Map<Integer, Integer> idToIndex) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<QueryCase> cases = new ArrayList<>();

        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream("faq_duplicates_v4.json")) {
            if (is == null) {
                return List.of();
            }
            JsonNode root = mapper.readTree(is);
            JsonNode groups = root.path("重複群組");
            if (groups.isMissingNode() || !groups.isObject()) {
                return List.of();
            }

            groups.fields().forEachRemaining(categoryEntry -> {
                JsonNode categoryNode = categoryEntry.getValue();
                if (!categoryNode.isObject()) {
                    return;
                }
                categoryNode.fields().forEachRemaining(groupEntry -> {
                    JsonNode groupNode = groupEntry.getValue();
                    if (!groupNode.isObject()) {
                        return;
                    }

                    Set<Integer> targetIdx = new HashSet<>();
                    addIdsToTarget(idToIndex, targetIdx, groupNode.path("建議保留"));
                    addIdsToTarget(idToIndex, targetIdx, groupNode.path("建議刪除"));

                    if (targetIdx.isEmpty()) {
                        return;
                    }

                    addQuestionsAsQueries(cases, targetIdx, groupNode.path("建議保留"));
                    addQuestionsAsQueries(cases, targetIdx, groupNode.path("建議刪除"));
                });
            });
        }

        return cases;
    }

    private static void addIdsToTarget(Map<Integer, Integer> idToIndex, Set<Integer> targetIdx, JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return;
        }
        for (JsonNode item : arr) {
            int id = item.path("id").asInt(-1);
            Integer idx = idToIndex.get(id);
            if (idx != null) {
                targetIdx.add(idx);
            }
        }
    }

    private static void addQuestionsAsQueries(List<QueryCase> cases, Set<Integer> targetIdx, JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return;
        }
        for (JsonNode item : arr) {
            String q = item.path("question").asText("").trim();
            if (q.isEmpty()) {
                continue;
            }
            cases.add(new QueryCase("DUP_GROUP", q, targetIdx));

            String colloquial = colloquialize(q);
            if (!colloquial.equals(q)) {
                cases.add(new QueryCase("DUP_GROUP_COLLOQUIAL", colloquial, targetIdx));
            }

            String dropped = dropOneChar(q);
            if (!dropped.equals(q) && !dropped.isEmpty()) {
                cases.add(new QueryCase("DUP_GROUP_DROP", dropped, targetIdx));
            }
        }
    }

    private static Map<String, Metrics> evaluateByLabel(
            List<QueryCase> cases,
            int topK,
            VectorUtils.BM25 bm25,
            LuceneFaqIndex lucene,
            boolean useBm25) {

        Map<String, List<QueryCase>> byLabel = new HashMap<>();
        byLabel.put("__ALL__", cases);
        for (QueryCase c : cases) {
            byLabel.computeIfAbsent(c.label(), k -> new ArrayList<>()).add(c);
        }

        Map<String, Metrics> out = new HashMap<>();
        for (Map.Entry<String, List<QueryCase>> e : byLabel.entrySet()) {
            out.put(e.getKey(), evaluate(e.getValue(), topK, bm25, lucene, useBm25));
        }
        return out;
    }

    private static Metrics evaluate(
            List<QueryCase> cases,
            int topK,
            VectorUtils.BM25 bm25,
            LuceneFaqIndex lucene,
            boolean useBm25) {

        int total = 0;
        int hit1 = 0;
        int hit3 = 0;
        int hit5 = 0;
        int hit10 = 0;
        double mrrSum = 0.0;
        double rankFoundSum = 0.0;
        int foundCount = 0;

        for (QueryCase c : cases) {
            String q = c.query();
            if (q == null || q.trim().isEmpty()) {
                continue;
            }
            total++;

            List<Integer> ranked;
            if (useBm25) {
                ranked = bm25.getRankedDocIds(q, topK);
            } else {
                ranked = lucene.search(q, topK).stream().map(LuceneFaqIndex.Hit::faqIndex).toList();
            }

            int rank = bestRank(ranked, c.targetIdx());
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
                total,
                hit1 / denom,
                hit3 / denom,
                hit5 / denom,
                hit10 / denom,
                mrrSum / denom,
                avgRank);
    }

    private static int bestRank(List<Integer> ranked, Set<Integer> targetIdx) {
        if (ranked == null || ranked.isEmpty() || targetIdx == null || targetIdx.isEmpty()) {
            return -1;
        }
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < ranked.size(); i++) {
            if (targetIdx.contains(ranked.get(i))) {
                best = Math.min(best, i + 1);
            }
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }

    private static String keywordLikeQuery(String q) {
        String cleaned = q.replaceAll("[\\s\\p{Punct}]+", "");
        cleaned = cleaned.replaceAll("[0-9]", "");
        cleaned = cleaned.trim();
        if (cleaned.length() <= 3) {
            return cleaned;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(cleaned, 0, Math.min(4, cleaned.length()));
        if (cleaned.length() > 6) {
            sb.append(" ").append(cleaned, cleaned.length() - 2, cleaned.length());
        }
        return sb.toString().trim();
    }

    private static String colloquialize(String q) {
        String out = q;
        out = out.replace("為何", "為什麼");
        out = out.replace("請問", "想問");
        out = out.replace("要如何", "怎麼");
        out = out.replace("該如何", "怎麼");
        out = out.replace("是否", "是不是");
        out = out.replace("無法", "不能");
        return out;
    }

    private static String dropOneChar(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() <= 4) {
            return t;
        }
        int mid = t.length() / 2;
        return t.substring(0, mid) + t.substring(mid + 1);
    }

    private static String swapAdjacent(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() <= 4) {
            return t;
        }
        int mid = t.length() / 2;
        if (mid + 1 >= t.length()) {
            return t;
        }
        char a = t.charAt(mid);
        char b = t.charAt(mid + 1);
        return t.substring(0, mid) + b + a + t.substring(mid + 2);
    }

    private static String answerSnippetQuery(String answer) {
        if (answer == null) {
            return "";
        }
        String a = answer.trim();
        if (a.isEmpty()) {
            return "";
        }
        String[] seps = new String[]{"。", "！", "?", "？", "!", "\n"};
        for (String sep : seps) {
            int idx = a.indexOf(sep);
            if (idx > 0) {
                a = a.substring(0, idx);
                break;
            }
        }
        a = a.replaceAll("[\\s\\p{Punct}]+", " ").trim();
        if (a.length() > 14) {
            a = a.substring(0, 14);
        }
        return a;
    }

    private static List<Integer> deterministicSampleIndexes(int size, int sampleSize) {
        int step = 31;
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

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static void printReport(String label, Metrics bm25, Metrics lucene) {
        if (bm25 == null || lucene == null || bm25.total == 0 || lucene.total == 0) {
            return;
        }
        System.out.println("=== Retrieval Realistic Comparison: " + label + " (n=" + bm25.total + ") ===");
        System.out.println("BM25   hit@1=" + bm25.hit1 + " hit@3=" + bm25.hit3 + " hit@5=" + bm25.hit5 + " hit@10=" + bm25.hit10 + " mrr=" + bm25.mrr + " avgRankFound=" + bm25.avgRankFound);
        System.out.println("Lucene hit@1=" + lucene.hit1 + " hit@3=" + lucene.hit3 + " hit@5=" + lucene.hit5 + " hit@10=" + lucene.hit10 + " mrr=" + lucene.mrr + " avgRankFound=" + lucene.avgRankFound);
        System.out.println("Diff   hit@1=" + (lucene.hit1 - bm25.hit1) + " hit@3=" + (lucene.hit3 - bm25.hit3) + " hit@5=" + (lucene.hit5 - bm25.hit5) + " hit@10=" + (lucene.hit10 - bm25.hit10) + " mrr=" + (lucene.mrr - bm25.mrr));
    }
}
