package com.bank.qa.repository;

import com.bank.qa.model.Faq;

import java.util.List;

public interface FaqRepository {
    List<Faq> list();

    Faq get(int id);

    Faq create(Faq faq);

    Faq update(int id, Faq faq);

    boolean delete(int id);

    boolean isPersistenceEnabled();

    String getPersistenceFile();
}
