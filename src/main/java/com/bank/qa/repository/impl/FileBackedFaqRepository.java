package com.bank.qa.repository.impl;

import com.bank.qa.model.Faq;
import com.bank.qa.repository.FaqRepository;
import com.bank.qa.util.JsonLoader;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Repository
public class FileBackedFaqRepository implements FaqRepository {

    private static final Logger logger = LoggerFactory.getLogger(FileBackedFaqRepository.class);

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private final ObjectMapper objectMapper;

    private volatile List<Faq> faqs = new ArrayList<>();

    @Value("${faq.source-json:faq_from_classified_v5.json}")
    private String sourceJson;

    @Value("${faq.data-file:}")
    private String dataFile;

    public FileBackedFaqRepository() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @PostConstruct
    public void init() {
        List<Faq> loaded;

        if (isPersistenceEnabled()) {
            Path p = Paths.get(dataFile);
            if (Files.exists(p)) {
                loaded = readFromFile(p);
            } else {
                loaded = JsonLoader.loadFaq(sourceJson);
                try {
                    ensureParentDir(p);
                    writeToFile(p, loaded);
                } catch (Exception e) {
                    logger.warn("Failed to initialize faq.data-file={} from classpath source {}: {}", dataFile, sourceJson, e.getMessage());
                }
            }
        } else {
            loaded = JsonLoader.loadFaq(sourceJson);
        }

        lock.writeLock().lock();
        try {
            faqs = new ArrayList<>(loaded);
        } finally {
            lock.writeLock().unlock();
        }

        logger.info("FAQ repository initialized: count={}, persistenceEnabled={}, dataFile={}",
                faqs.size(), isPersistenceEnabled(), isPersistenceEnabled() ? dataFile : "");
    }

    @Override
    public List<Faq> list() {
        lock.readLock().lock();
        try {
            return faqs.stream().map(Faq::copy).toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Faq get(int id) {
        lock.readLock().lock();
        try {
            for (Faq f : faqs) {
                if (f.getId() == id) {
                    return f.copy();
                }
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Faq create(Faq faq) {
        if (faq == null) {
            throw new IllegalArgumentException("faq is null");
        }

        lock.writeLock().lock();
        try {
            int newId = faq.getId();
            if (newId <= 0 || containsId(newId)) {
                newId = nextId();
            }

            Faq created = faq.copy();
            created.setId(newId);
            faqs.add(created);
            persistIfNeeded();
            return created.copy();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Faq update(int id, Faq faq) {
        if (faq == null) {
            throw new IllegalArgumentException("faq is null");
        }

        lock.writeLock().lock();
        try {
            for (int i = 0; i < faqs.size(); i++) {
                if (faqs.get(i).getId() == id) {
                    Faq updated = faq.copy();
                    updated.setId(id);
                    faqs.set(i, updated);
                    persistIfNeeded();
                    return updated.copy();
                }
            }
            return null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean delete(int id) {
        lock.writeLock().lock();
        try {
            boolean removed = faqs.removeIf(f -> f.getId() == id);
            if (removed) {
                persistIfNeeded();
            }
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean isPersistenceEnabled() {
        return dataFile != null && !dataFile.trim().isEmpty();
    }

    @Override
    public String getPersistenceFile() {
        return dataFile;
    }

    private boolean containsId(int id) {
        for (Faq f : faqs) {
            if (f.getId() == id) {
                return true;
            }
        }
        return false;
    }

    private int nextId() {
        return faqs.stream().map(Faq::getId).max(Comparator.naturalOrder()).orElse(0) + 1;
    }

    private void persistIfNeeded() {
        if (!isPersistenceEnabled()) {
            return;
        }
        try {
            Path p = Paths.get(dataFile);
            ensureParentDir(p);
            writeToFile(p, faqs);
        } catch (Exception e) {
            logger.warn("Persist FAQ failed (dataFile={}): {}", dataFile, e.getMessage());
        }
    }

    private List<Faq> readFromFile(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            return objectMapper.readValue(is, new TypeReference<List<Faq>>() {});
        } catch (Exception e) {
            logger.warn("Failed to read FAQ from file {}: {}", path, e.getMessage());
            return List.of();
        }
    }

    private void writeToFile(Path path, List<Faq> list) throws IOException {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), list);
    }

    private static void ensureParentDir(Path p) throws IOException {
        Path parent = p.toAbsolutePath().getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }
}
