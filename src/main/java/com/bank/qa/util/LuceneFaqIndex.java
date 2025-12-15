package com.bank.qa.util;

import com.bank.qa.model.Faq;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.ngram.NGramTokenizer;
import org.apache.lucene.analysis.pattern.PatternReplaceCharFilter;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class LuceneFaqIndex {

    public record Hit(int faqIndex, float score) {
    }

    private static final String FIELD_FAQ_INDEX = "faqIndex";
    private static final String FIELD_Q = "q";
    private static final String FIELD_A = "a";
    private static final String FIELD_QA = "qa";
    private static final String FIELD_CAT = "cat";
    private static final String FIELD_MOD = "mod";
    private static final String FIELD_SRC = "src";

    private static final Pattern CLEAN_PATTERN = Pattern.compile("[\\s\\p{Punct}]+");

    private final Analyzer analyzer;
    private Directory directory;
    private IndexSearcher searcher;

    public LuceneFaqIndex() {
        this.analyzer = new Analyzer() {
            @Override
            protected Reader initReader(String fieldName, Reader reader) {
                return new PatternReplaceCharFilter(CLEAN_PATTERN, "", reader);
            }

            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer tokenizer = new NGramTokenizer(1, 3);
                TokenStream stream = new LowerCaseFilter(tokenizer);
                return new TokenStreamComponents(tokenizer, stream);
            }
        };
    }

    public void build(List<Faq> faqs) {
        try {
            this.directory = new ByteBuffersDirectory();
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

            try (IndexWriter writer = new IndexWriter(directory, config)) {
                for (int i = 0; i < faqs.size(); i++) {
                    Faq faq = faqs.get(i);
                    Document doc = new Document();
                    doc.add(new StoredField(FIELD_FAQ_INDEX, i));
                    doc.add(new IntPoint(FIELD_FAQ_INDEX, i));
                    doc.add(new TextField(FIELD_Q, safe(faq.getQuestion()), Field.Store.NO));
                    doc.add(new TextField(FIELD_A, safe(faq.getAnswer()), Field.Store.NO));
                    doc.add(new TextField(FIELD_QA, safe(faq.getQuestion()) + " " + safe(faq.getAnswer()), Field.Store.NO));
                    doc.add(new TextField(FIELD_CAT, safe(faq.getCategory()), Field.Store.NO));
                    doc.add(new TextField(FIELD_MOD, safe(faq.getModule()), Field.Store.NO));
                    doc.add(new TextField(FIELD_SRC, safe(faq.getSource()), Field.Store.NO));
                    writer.addDocument(doc);
                }
                writer.commit();
            }

            DirectoryReader reader = DirectoryReader.open(directory);
            this.searcher = new IndexSearcher(reader);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build Lucene index", e);
        }
    }

    public int getDocCount() {
        if (searcher == null) {
            return 0;
        }
        return searcher.getIndexReader().numDocs();
    }

    public List<Hit> search(String queryText, int topK) {
        if (searcher == null || queryText == null || queryText.trim().isEmpty() || topK <= 0) {
            return List.of();
        }

        String qText = queryText.trim();
        boolean shortQuery = qText.length() <= 10;

        Map<String, Float> boosts = new HashMap<>();
        boosts.put(FIELD_Q, 2.5f);
        boosts.put(FIELD_QA, 1.0f);
        boosts.put(FIELD_A, 0.6f);

        String[] fields;
        if (shortQuery) {
            boosts.put(FIELD_CAT, 1.2f);
            boosts.put(FIELD_MOD, 0.9f);
            boosts.put(FIELD_SRC, 0.2f);
            fields = new String[]{FIELD_Q, FIELD_QA, FIELD_A, FIELD_CAT, FIELD_MOD, FIELD_SRC};
        } else {
            fields = new String[]{FIELD_Q, FIELD_QA, FIELD_A};
        }

        MultiFieldQueryParser parser = new MultiFieldQueryParser(fields, analyzer, boosts);

        Query query;
        try {
            query = parser.parse(MultiFieldQueryParser.escape(qText));
        } catch (ParseException e) {
            return List.of();
        }

        try {
            TopDocs topDocs = searcher.search(query, topK);
            List<Hit> hits = new ArrayList<>(topDocs.scoreDocs.length);
            for (ScoreDoc sd : topDocs.scoreDocs) {
                Document doc = searcher.doc(sd.doc);
                int faqIndex = doc.getField(FIELD_FAQ_INDEX).numericValue().intValue();
                hits.add(new Hit(faqIndex, sd.score));
            }
            return hits;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
