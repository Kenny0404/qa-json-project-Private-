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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class RetrievalDatasetComparisonClassifiedV5Test {

    private record QueryCase(String label, String query, Set<Integer> targetIds) {
    }

    private record Metrics(int total, double hit1, double hit3, double hit5, double hit10, double mrr, double avgRankFound) {
    }

    private record RetrievalPack(List<Faq> faqs, VectorUtils.BM25 bm25, LuceneFaqIndex lucene) {
    }

    @Test
    public void compareDataset_faqJson_vs_classifiedV5Flattened() throws Exception {
        List<Faq> baseFaqs = JsonLoader.loadFaq("faq.json");
        assertTrue(baseFaqs.size() > 0);

        List<Faq> classifiedFaqs = loadClassifiedV5Flattened("faq_classified_v5.json");
        assertTrue(classifiedFaqs.size() > 0);

        RetrievalPack base = buildPack(baseFaqs);
        RetrievalPack classified = buildPack(classifiedFaqs);

        Set<Integer> baseIds = new HashSet<>();
        for (Faq f : baseFaqs) {
            baseIds.add(f.getId());
        }
        Set<Integer> classifiedIds = new HashSet<>();
        for (Faq f : classifiedFaqs) {
            classifiedIds.add(f.getId());
        }
        Set<Integer> commonIds = new HashSet<>(baseIds);
        commonIds.retainAll(classifiedIds);

        List<QueryCase> cases = new ArrayList<>();
        cases.addAll(buildFromDuplicateGroups());
        cases.addAll(buildFromFaqSamples(baseFaqs));

        // IMPORTANT: 兩個資料集的內容不一定 1:1 相同（classified 可能缺漏某些 id）。
        // 若不先取交集，會導致兩邊總樣本數不同，且「看起來退步」其實只是 target 不存在。
        cases = restrictToCommonIds(cases, commonIds);

        int topK = 10;

        Map<String, Metrics> baseBm25 = evaluateByLabel(cases, topK, base, true, false);
        Map<String, Metrics> baseLucene = evaluateByLabel(cases, topK, base, false, false);

        // NOTE: classified 資料集的 docs 會經過 cleanText()；為了公平比較，query 也要用同樣的清洗規則
        // 否則 EXACT/SHORT 等 query 會因為符號/路徑格式被改寫而「看起來嚴重退步」，但那其實是 query/doc 不一致。
        Map<String, Metrics> clsBm25 = evaluateByLabel(cases, topK, classified, true, true);
        Map<String, Metrics> clsLucene = evaluateByLabel(cases, topK, classified, false, true);

        printDatasetReport("BASE(faq.json)", baseBm25, baseLucene);
        printDatasetReport("CLASSIFIED_V5(flattened+clean)", clsBm25, clsLucene);

        printDiffReport("DATASET_DIFF", baseLucene.get("__ALL__"), clsLucene.get("__ALL__"));

        Metrics baseAll = baseLucene.get("__ALL__");
        Metrics clsAll = clsLucene.get("__ALL__");

        assertTrue(baseAll != null && baseAll.total > 0);
        assertTrue(clsAll != null && clsAll.total > 0);
    }

    private static List<QueryCase> restrictToCommonIds(List<QueryCase> cases, Set<Integer> commonIds) {
        if (cases == null || cases.isEmpty() || commonIds == null || commonIds.isEmpty()) {
            return List.of();
        }
        List<QueryCase> out = new ArrayList<>(cases.size());
        for (QueryCase c : cases) {
            if (c == null) {
                continue;
            }
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
        int sampleSize = Math.min(120, faqs.size());
        List<Integer> sampleIdx = deterministicSampleIndexes(faqs.size(), sampleSize);

        List<QueryCase> cases = new ArrayList<>();

        for (int idx : sampleIdx) {
            Faq f = faqs.get(idx);
            int id = f.getId();

            String q = safe(f.getQuestion()).trim();
            String a = safe(f.getAnswer()).trim();
            if (q.isEmpty()) {
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

    private static List<QueryCase> buildFromDuplicateGroups() throws Exception {
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

                    Set<Integer> targetIds = new HashSet<>();
                    addIdsToTarget(targetIds, groupNode.path("建議保留"));
                    addIdsToTarget(targetIds, groupNode.path("建議刪除"));

                    if (targetIds.isEmpty()) {
                        return;
                    }

                    addQuestionsAsQueries(cases, targetIds, groupNode.path("建議保留"));
                    addQuestionsAsQueries(cases, targetIds, groupNode.path("建議刪除"));
                });
            });
        }

        return cases;
    }

    private static void addIdsToTarget(Set<Integer> targetIds, JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return;
        }
        for (JsonNode item : arr) {
            int id = item.path("id").asInt(-1);
            if (id > 0) {
                targetIds.add(id);
            }
        }
    }

    private static void addQuestionsAsQueries(List<QueryCase> cases, Set<Integer> targetIds, JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return;
        }
        for (JsonNode item : arr) {
            String q = item.path("question").asText("").trim();
            if (q.isEmpty()) {
                continue;
            }
            cases.add(new QueryCase("DUP_GROUP", q, targetIds));

            String colloquial = colloquialize(q);
            if (!colloquial.equals(q)) {
                cases.add(new QueryCase("DUP_GROUP_COLLOQUIAL", colloquial, targetIds));
            }

            String dropped = dropOneChar(q);
            if (!dropped.equals(q) && !dropped.isEmpty()) {
                cases.add(new QueryCase("DUP_GROUP_DROP", dropped, targetIds));
            }
        }
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

    private static void printDiffReport(String label, Metrics base, Metrics classified) {
        if (base == null || classified == null || base.total == 0 || classified.total == 0) {
            return;
        }
        System.out.println("\n=== " + label + " (Lucene only, classified - base) ===");
        System.out.println("Diff hit@1=" + (classified.hit1 - base.hit1)
                + " hit@3=" + (classified.hit3 - base.hit3)
                + " hit@5=" + (classified.hit5 - base.hit5)
                + " hit@10=" + (classified.hit10 - base.hit10)
                + " mrr=" + (classified.mrr - base.mrr));
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

    private static List<Faq> loadClassifiedV5Flattened(String resourceName) throws Exception {
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
        flatten(root, path, out, resourceName);
        return out;
    }

    private static void flatten(JsonNode node, List<String> path, List<Faq> out, String source) {
        if (node == null || node.isNull()) {
            return;
        }

        if (node.isArray()) {
            for (JsonNode item : node) {
                if (!item.isObject()) {
                    continue;
                }

                int id = item.path("id").asInt(-1);
                String question = cleanText(item.path("question").asText(""));
                String answer = cleanText(item.path("answer").asText(""));

                Faq f = new Faq();
                f.setId(id);
                f.setQuestion(question);
                f.setAnswer(answer);

                String category = path.isEmpty() ? "" : path.get(0);
                String module = path.size() <= 1 ? "" : String.join(" > ", path.subList(1, path.size()));

                if (!category.isEmpty()) {
                    f.setCategory(category);
                }
                if (!module.isEmpty()) {
                    f.setModule(module);
                }
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
                flatten(e.getValue(), path, out, source);
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
