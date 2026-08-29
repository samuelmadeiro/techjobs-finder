package com.techjobs.finder.service;

import com.techjobs.finder.entity.Technology;
import com.techjobs.finder.repository.TechnologyRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mantém a tabela {@code technology} sincronizada com {@link TechnologyCatalog}.
 * O catálogo vive no código para que adicionar tecnologia não exija nova migration.
 */
@Service
public class TechnologySeedService {

    private static final Logger log = LoggerFactory.getLogger(TechnologySeedService.class);

    private final TechnologyRepository repository;
    private final TechnologyCatalog catalog;

    public TechnologySeedService(TechnologyRepository repository, TechnologyCatalog catalog) {
        this.repository = repository;
        this.catalog = catalog;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        Set<String> existing = repository.findAll().stream()
                .map(Technology::getSlug)
                .collect(java.util.stream.Collectors.toSet());

        var missing = catalog.all().stream()
                .filter(entry -> !existing.contains(entry.slug()))
                .map(entry -> new Technology(entry.slug(), entry.name(), entry.kind()))
                .toList();

        if (!missing.isEmpty()) {
            repository.saveAll(missing);
            log.info("Catálogo de tecnologias atualizado: {} novo(s) registro(s)", missing.size());
        }
    }

    /** Índice slug -> entidade, usado na ingestão para evitar N consultas. */
    @Transactional(readOnly = true)
    public Map<String, Technology> indexBySlug() {
        Map<String, Technology> index = new HashMap<>();
        repository.findAll().forEach(tech -> index.put(tech.getSlug(), tech));
        return index;
    }
}
