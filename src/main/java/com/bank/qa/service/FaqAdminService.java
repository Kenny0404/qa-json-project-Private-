package com.bank.qa.service;

import com.bank.qa.model.Faq;

import java.util.List;

public interface FaqAdminService {
    List<Faq> listFaq();

    Faq getFaq(int id);

    Faq createFaq(Faq faq);

    Faq updateFaq(int id, Faq faq);

    boolean deleteFaq(int id);

    void reindex();

    boolean isPersistenceEnabled();

    String getPersistenceFile();
}
