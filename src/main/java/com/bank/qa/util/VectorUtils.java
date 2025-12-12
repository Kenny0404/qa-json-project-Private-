package com.bank.qa.util;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 向量工具類
 * 提供 BM25 檢索和 RRF 融合功能
 */
public class VectorUtils {

    /**
     * 簡易分詞（N-gram）
     * 單字 + 雙字詞 + 三字詞
     */
    public static List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<String> tokens = new ArrayList<>();
        String cleaned = text.toLowerCase().replaceAll("[\\s\\p{Punct}]+", "");
        
        // 單字
        for (char c : cleaned.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                tokens.add(String.valueOf(c));
            }
        }
        
        // 雙字詞（Bigram）
        for (int i = 0; i < cleaned.length() - 1; i++) {
            char c1 = cleaned.charAt(i);
            char c2 = cleaned.charAt(i + 1);
            if (Character.isLetterOrDigit(c1) && Character.isLetterOrDigit(c2)) {
                tokens.add(String.valueOf(c1) + String.valueOf(c2));
            }
        }
        
        // 三字詞（Trigram）
        for (int i = 0; i < cleaned.length() - 2; i++) {
            String trigram = cleaned.substring(i, i + 3);
            if (trigram.chars().allMatch(Character::isLetterOrDigit)) {
                tokens.add(trigram);
            }
        }
        
        return tokens;
    }

    /**
     * RRF (Reciprocal Rank Fusion) 融合
     * 將多個排序列表融合為單一排序
     * 
     * @param rankings 多個排序列表，每個列表包含文檔ID按相關性排序
     * @param k RRF 常數，通常為 60
     * @return 融合後的文檔ID列表，按 RRF 分數排序
     */
    public static List<Integer> rrfFusion(List<List<Integer>> rankings, int k) {
        Map<Integer, Double> rrfScores = new HashMap<>();
        
        for (List<Integer> ranking : rankings) {
            for (int rank = 0; rank < ranking.size(); rank++) {
                int docId = ranking.get(rank);
                double score = 1.0 / (k + rank + 1);  // rank 從 0 開始，所以 +1
                rrfScores.merge(docId, score, Double::sum);
            }
        }
        
        // 按 RRF 分數降序排序
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * RRF 融合（帶分數返回）
     */
    public static Map<Integer, Double> rrfFusionWithScores(List<List<Integer>> rankings, int k) {
        Map<Integer, Double> rrfScores = new HashMap<>();
        
        for (List<Integer> ranking : rankings) {
            for (int rank = 0; rank < ranking.size(); rank++) {
                int docId = ranking.get(rank);
                double score = 1.0 / (k + rank + 1);
                rrfScores.merge(docId, score, Double::sum);
            }
        }
        
        return rrfScores;
    }

    /**
     * BM25 檢索器
     */
    public static class BM25 {
        private List<String> documents;
        private List<List<String>> tokenizedDocs;
        private Map<String, Double> idf;
        private double avgDocLength;
        
        private double k1 = 1.5;
        private double b = 0.75;

        public BM25() {
            this.documents = new ArrayList<>();
            this.tokenizedDocs = new ArrayList<>();
            this.idf = new HashMap<>();
        }

        public BM25(double k1, double b) {
            this();
            this.k1 = k1;
            this.b = b;
        }

        public void fit(List<String> docs) {
            this.documents = new ArrayList<>(docs);
            this.tokenizedDocs = new ArrayList<>();
            
            Map<String, Integer> docFreq = new HashMap<>();
            double totalLength = 0;
            
            for (String doc : docs) {
                List<String> tokens = tokenize(doc);
                tokenizedDocs.add(tokens);
                totalLength += tokens.size();
                
                Set<String> uniqueTokens = new HashSet<>(tokens);
                for (String token : uniqueTokens) {
                    docFreq.merge(token, 1, Integer::sum);
                }
            }
            
            this.avgDocLength = docs.isEmpty() ? 0 : totalLength / docs.size();
            
            int N = docs.size();
            for (Map.Entry<String, Integer> entry : docFreq.entrySet()) {
                double df = entry.getValue();
                double idfValue = Math.log((N - df + 0.5) / (df + 0.5) + 1.0);
                idf.put(entry.getKey(), idfValue);
            }
        }

        public double[] score(String query) {
            List<String> queryTokens = tokenize(query);
            double[] scores = new double[documents.size()];
            
            for (int i = 0; i < tokenizedDocs.size(); i++) {
                scores[i] = scoreDocument(queryTokens, tokenizedDocs.get(i));
            }
            
            return scores;
        }

        public double scoreDocument(List<String> queryTokens, List<String> docTokens) {
            double score = 0.0;
            int docLength = docTokens.size();
            
            Map<String, Integer> docTermFreq = new HashMap<>();
            for (String token : docTokens) {
                docTermFreq.merge(token, 1, Integer::sum);
            }
            
            for (String queryTerm : queryTokens) {
                if (!idf.containsKey(queryTerm)) continue;
                
                double termIdf = idf.get(queryTerm);
                int tf = docTermFreq.getOrDefault(queryTerm, 0);
                
                if (avgDocLength > 0) {
                    double numerator = tf * (k1 + 1);
                    double denominator = tf + k1 * (1 - b + b * docLength / avgDocLength);
                    score += termIdf * (numerator / denominator);
                }
            }
            
            return score;
        }

        /**
         * 取得排序後的文檔索引列表
         */
        public List<Integer> getRankedDocIds(String query, int topK) {
            double[] scores = score(query);
            
            // 建立 (index, score) 對並排序
            List<int[]> indexedScores = new ArrayList<>();
            for (int i = 0; i < scores.length; i++) {
                if (scores[i] > 0) {
                    indexedScores.add(new int[]{i, (int)(scores[i] * 10000)});
                }
            }
            
            indexedScores.sort((a, b) -> b[1] - a[1]);
            
            return indexedScores.stream()
                    .limit(topK)
                    .map(arr -> arr[0])
                    .collect(Collectors.toList());
        }

        public double[] normalizedScore(String query) {
            double[] scores = score(query);
            double maxScore = Arrays.stream(scores).max().orElse(1.0);
            if (maxScore > 0) {
                for (int i = 0; i < scores.length; i++) {
                    scores[i] /= maxScore;
                }
            }
            return scores;
        }

        public int getDocCount() {
            return documents.size();
        }

        public double getAvgDocLength() {
            return avgDocLength;
        }
    }
}
