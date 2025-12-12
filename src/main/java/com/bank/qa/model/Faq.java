package com.bank.qa.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * FAQ 資料模型
 * 包含問題與答案，支援語意檢索評分
 */
public class Faq {
    
    private int id;
    private String question;
    private String answer;
    
    // 可選欄位，未來擴充用
    private String category;
    private String module;
    private String source;
    
    // 語意檢索評分（不序列化到 JSON 檔案）
    private double score;
    
    // 向量緩存（不序列化）
    @JsonIgnore
    private double[] vector;

    // 預設建構子
    public Faq() {}

    // 完整建構子
    public Faq(int id, String question, String answer) {
        this.id = id;
        this.question = question;
        this.answer = answer;
    }

    // 複製建構子（用於返回結果時不修改原始資料）
    public Faq copy() {
        Faq copy = new Faq(this.id, this.question, this.answer);
        copy.setCategory(this.category);
        copy.setModule(this.module);
        copy.setSource(this.source);
        copy.setScore(this.score);
        return copy;
    }

    // Getter & Setter
    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    @JsonIgnore
    public double[] getVector() {
        return vector;
    }

    public void setVector(double[] vector) {
        this.vector = vector;
    }

    @Override
    public String toString() {
        return "Faq{" +
                "id=" + id +
                ", question='" + question + '\'' +
                ", score=" + String.format("%.4f", score) +
                '}';
    }
}
