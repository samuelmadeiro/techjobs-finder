import { useEffect, useState } from "react";
import { api } from "../api/client";
import type { CountryOption, SourceInfo, TechnologyOption } from "../api/types";

interface Catalog {
  languages: TechnologyOption[];
  technologies: TechnologyOption[];
  sources: SourceInfo[];
  countries: CountryOption[];
}

/**
 * Catálogos que alimentam os filtros e os indicadores do topo.
 *
 * Vêm do banco, então as opções refletem o que existe de verdade na base. Falha aqui
 * não bloqueia nada: os selects ficam com "Todas" e a busca continua funcionando.
 */
export function useCatalog(): Catalog {
  const [catalog, setCatalog] = useState<Catalog>({
    languages: [],
    technologies: [],
    sources: [],
    countries: [],
  });

  useEffect(() => {
    const controller = new AbortController();
    Promise.all([
      api.languages(controller.signal).catch(() => []),
      api.technologies(controller.signal).catch(() => []),
      api.sources(controller.signal).catch(() => []),
      api.countries(controller.signal).catch(() => []),
    ]).then(([languages, technologies, sources, countries]) => {
      if (controller.signal.aborted) return;
      setCatalog({
        languages,
        technologies: technologies.filter((item) => item.kind !== "LANGUAGE"),
        sources,
        countries,
      });
    });
    return () => controller.abort();
  }, []);

  return catalog;
}
