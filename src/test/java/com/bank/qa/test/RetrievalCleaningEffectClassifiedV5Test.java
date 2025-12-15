package com.bank.qa.test;

import com.bank.qa.model.Faq;
import com.bank.qa.util.LuceneFaqIndex;
import com.bank.qa.util.VectorUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class RetrievalCleaningEffectClassifiedV5Test {

    private record QueryCase(String label, String query, Set<Integer> targetIds) {
    }

    private record Metrics(int total, double hit1, double hit3, double hit5, double hit10, double mrr, double avgRankFound) {
    }

    private record RetrievalPack(List<Faq> faqs, VectorUtils.BM25 bm25, LuceneFaqIndex lucene) {
    }

    @Test
    public void compareRawVsCleaned_onSameClassifiedV5() throws Exception {
        List<Faq> rawFaqs = loadClassifiedV5Flattened("faq_classified_v5.json", false);
        List<Faq> cleanedFaqs = loadClassifiedV5Flattened("faq_classified_v5.json", true);

        assertTrue(rawFaqs.size() > 0);
        assertTrue(cleanedFaqs.size() > 0);

        RetrievalPack raw = buildPack(rawFaqs);
        RetrievalPack cleaned = buildPack(cleanedFaqs);

        Set<Integer> commonIds = new HashSet<>();
        for (Faq f : rawFaqs) {
            if (f.getId() > 0) {
                commonIds.add(f.getId());
            }
        }

        List<QueryCase> cases = new ArrayList<>();
        cases.addAll(buildFromFaqSamples(rawFaqs));
        cases = restrictToCommonIds(cases, commonIds);

        int topK = 10;

        Map<String, Metrics> rawLucene = evaluateByLabel(cases, topK, raw, false, false);
        Map<String, Metrics> cleanedLucene = evaluateByLabel(cases, topK, cleaned, false, true);

        Map<String, Metrics> rawBm25 = evaluateByLabel(cases, topK, raw, true, false);
        Map<String, Metrics> cleanedBm25 = evaluateByLabel(cases, topK, cleaned, true, true);

        printDatasetReport("RAW_CLASSIFIED_V5", rawBm25, rawLucene);
        printDatasetReport("CLEANED_CLASSIFIED_V5", cleanedBm25, cleanedLucene);

        printDiffReport("CLEANING_EFFECT(Lucene)", rawLucene.get("__ALL__"), cleanedLucene.get("__ALL__"));
        printDiffReport("CLEANING_EFFECT(BM25)", rawBm25.get("__ALL__"), cleanedBm25.get("__ALL__"));

        Metrics rawAll = rawLucene.get("__ALL__");
        Metrics cleanedAll = cleanedLucene.get("__ALL__");
        assertTrue(rawAll != null && rawAll.total > 0);
        assertTrue(cleanedAll != null && cleanedAll.total > 0);
    }

    private static RetrievalPack buildPack(List<Faq> faqs) {
        List<String> docs = new ArrayList<>(faqs.size());
        for (Faq f : faqs) {
            docs.add(safe(f.getQuestion()) + " " + safe(f.getAnswer()));
        }

        VectorUtils.BM25 bm25 = new VectorUtils.BM25(1.5, 0.75);
        bm25.fit(docs);

        LuceneFaqIndex lucene = new LuceneFaqIndex();
        lucene.build(faqs);

        return new RetrievalPack(faqs, bm25, lucene);
    }

    private static List<QueryCase> buildFromFaqSamples(List<Faq> faqs) {
        int sampleSize = Math.min(200, faqs.size());
        List<Integer> sampleIdx = deterministicSampleIndexes(faqs.size(), sampleSize);

        List<QueryCase> cases = new ArrayList<>();

        for (int idx : sampleIdx) {
            Faq f = faqs.get(idx);
            int id = f.getId();

            String q = safe(f.getQuestion()).trim();
            String a = safe(f.getAnswer()).trim();
            if (q.isEmpty() || id <= 0) {
                continue;
            }

            Set<Integer> target = Set.of(id);

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

    private static List<QueryCase> restrictToCommonIds(List<QueryCase> cases, Set<Integer> commonIds) {
        if (cases == null || cases.isEmpty() || commonIds == null || commonIds.isEmpty()) {
            return List.of();
        }
        List<QueryCase> out = new ArrayList<>(cases.size());
        for (QueryCase c : cases) {
            Set<Integer> intersect = new HashSet<>();
            for (Integer id : c.targetIds()) {
                if (id != null && commonIds.contains(id)) {
                    intersect.add(id);
                }
            }
            if (!intersect.isEmpty()) {
                out.add(new QueryCase(c.label(), c.query(), intersect));
            }
        }
        return out;
    }

    private static Map<String, Metrics> evaluateByLabel(
            List<QueryCase> cases,
            int topK,
            RetrievalPack pack,
            boolean useBm25,
            boolean cleanQueryForThisDataset) {

        Map<String, List<QueryCase>> byLabel = new HashMap<>();
        byLabel.put("__ALL__", cases);
        for (QueryCase c : cases) {
            byLabel.computeIfAbsent(c.label(), k -> new ArrayList<>()).add(c);
        }

        Map<String, Metrics> out = new HashMap<>();
        for (Map.Entry<String, List<QueryCase>> e : byLabel.entrySet()) {
            out.put(e.getKey(), evaluate(e.getValue(), topK, pack, useBm25, cleanQueryForThisDataset));
        }
        return out;
    }

    private static Metrics evaluate(
            List<QueryCase> cases,
            int topK,
            RetrievalPack pack,
            boolean useBm25,
            boolean cleanQueryForThisDataset) {

        Map<Integer, Set<Integer>> idToIndexes = new HashMap<>();
        for (int i = 0; i < pack.faqs().size(); i++) {
            int id = pack.faqs().get(i).getId();
            if (id <= 0) {
                continue;
            }
            idToIndexes.computeIfAbsent(id, k -> new HashSet<>()).add(i);
        }

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

            if (cleanQueryForThisDataset) {
                q = cleanText(q);
            }

            Set<Integer> targetIdx = toTargetIdx(c.targetIds(), idToIndexes);
            if (targetIdx.isEmpty()) {
                continue;
            }

            total++;

            List<Integer> ranked;
            if (useBm25) {
                ranked = pack.bm25().getRankedDocIds(q, topK);
            } else {
                ranked = pack.lucene().search(q, topK).stream().map(LuceneFaqIndex.Hit::faqIndex).toList();
            }

            int rank = bestRank(ranked, targetIdx);
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

    private static Set<Integer> toTargetIdx(Set<Integer> targetIds, Map<Integer, Set<Integer>> idToIndexes) {
        if (targetIds == null || targetIds.isEmpty()) {
            return Set.of();
        }
        Set<Integer> out = new HashSet<>();
        for (Integer id : targetIds) {
            if (id == null) {
                continue;
            }
            Set<Integer> idxs = idToIndexes.get(id);
            if (idxs != null && !idxs.isEmpty()) {
                out.addAll(idxs);
            }
        }
        return out;
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

    private static void printDatasetReport(String datasetLabel, Map<String, Metrics> bm25ByLabel, Map<String, Metrics> luceneByLabel) {
        System.out.println("\n==============================");
        System.out.println("DATASET: " + datasetLabel);
        System.out.println("==============================");

        Metrics bm25All = bm25ByLabel.get("__ALL__");
        Metrics luceneAll = luceneByLabel.get("__ALL__");
        printReport("OVERALL", bm25All, luceneAll);

        for (String label : bm25ByLabel.keySet()) {
            if ("__ALL__".equals(label)) {
                continue;
            }
            printReport(label, bm25ByLabel.get(label), luceneByLabel.get(label));
        }
    }

    private static void printDiffReport(String label, Metrics a, Metrics b) {
        if (a == null || b == null || a.total == 0 || b.total == 0) {
            return;
        }
        System.out.println("\n=== " + label + " (b - a) ===");
        System.out.println("Diff hit@1=" + (b.hit1 - a.hit1)
                + " hit@3=" + (b.hit3 - a.hit3)
                + " hit@5=" + (b.hit5 - a.hit5)
                + " hit@10=" + (b.hit10 - a.hit10)
                + " mrr=" + (b.mrr - a.mrr));
    }

    private static void printReport(String label, Metrics bm25, Metrics lucene) {
        if (bm25 == null || lucene == null || bm25.total == 0 || lucene.total == 0) {
            return;
        }
        System.out.println("=== " + label + " (n=" + bm25.total + ") ===");
        System.out.println("BM25   hit@1=" + bm25.hit1 + " hit@3=" + bm25.hit3 + " hit@5=" + bm25.hit5 + " hit@10=" + bm25.hit10 + " mrr=" + bm25.mrr + " avgRankFound=" + bm25.avgRankFound);
        System.out.println("Lucene hit@1=" + lucene.hit1 + " hit@3=" + lucene.hit3 + " hit@5=" + lucene.hit5 + " hit@10=" + lucene.hit10 + " mrr=" + lucene.mrr + " avgRankFound=" + lucene.avgRankFound);
        System.out.println("Diff   hit@1=" + (lucene.hit1 - bm25.hit1) + " hit@3=" + (lucene.hit3 - bm25.hit3) + " hit@5=" + (lucene.hit5 - bm25.hit5) + " hit@10=" + (lucene.hit10 - bm25.hit10) + " mrr=" + (lucene.mrr - bm25.mrr));
    }

    private static List<Faq> loadClassifiedV5Flattened(String resourceName, boolean clean) throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        JsonNode root;
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourceName)) {
            if (is == null) {
                return List.of();
            }
            root = mapper.readTree(is);
        }

        List<Faq> out = new ArrayList<>(2048);
        List<String> path = new ArrayList<>();
        flatten(root, path, out, resourceName, clean);
        return out;
    }

    private static void flatten(JsonNode node, List<String> path, List<Faq> out, String source, boolean clean) {
        if (node == null || node.isNull()) {
            return;
        }

        if (node.isArray()) {
            for (JsonNode item : node) {
                if (!item.isObject()) {
                    continue;
                }

                int id = item.path("id").asInt(-1);
                String question = item.path("question").asText("");
                String answer = item.path("answer").asText("");

                if (clean) {
                    question = cleanText(question);
                    answer = cleanText(answer);
                }

                Faq f = new Faq();
                f.setId(id);
                f.setQuestion(question);
                f.setAnswer(answer);
                f.setSource(source);

                out.add(f);
            }
            return;
        }

        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                path.add(e.getKey());
                flatten(e.getValue(), path, out, source, clean);
                path.remove(path.size() - 1);
            }
        }
    }

    private static String cleanText(String s) {
        if (s == null) {
            return "";
        }

        String t = s.trim();

        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1).trim();
        }

        t = t.replace("\\r\\n", "\n");

        t = t.replace("->>", " > ");
        t = t.replace(">>", " > ");
        t = t.replace("->", " > ");

        t = t.replace("\\\\", "\\");
        t = t.replace("\\", " > ");
        t = t.replace("/", " > ");

        t = t.replaceAll("[ \t\f]+", " ");
        t = t.replaceAll(" ?\\n ?", "\n");

        t = t.replaceAll("( ?> ?){2,}", " > ");

        return t.trim();
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
}
