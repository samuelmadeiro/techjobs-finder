import { useCallback, useEffect, useRef, useState } from "react";
import { RefreshCw, Sparkles } from "lucide-react";
import { FilterChips } from "../components/FilterChips";
import { FilterPanel } from "../components/FilterPanel";
import { JobDetailsDrawer } from "../components/JobDetailsDrawer";
import { JobList } from "../components/JobList";
import { ResumeUploader } from "../components/ResumeUploader";
import { SearchBar } from "../components/SearchBar";
import { SearchHero } from "../components/SearchHero";
import { EmptyState, ErrorState, LoadingState } from "../components/States";
import { useJobSearch } from "../hooks/useJobSearch";
import type { CountryOption, JobFilters, Resume, SourceInfo, TechnologyOption } from "../api/types";
import { EMPTY_FILTERS, SIZE_OPTIONS } from "../api/types";
import { buildQuery } from "../api/client";

interface Props {
  resume: Resume | null;
  languages: TechnologyOption[];
  technologies: TechnologyOption[];
  sources: SourceInfo[];
  countries: CountryOption[];
  onResumeUploaded: (resume: Resume) => void;
  onResumeRemoved: () => void;
  onViewProfile: () => void;
}

const SORT_LABEL: Record<JobFilters["sort"], string> = {
  relevance: "Mais relevantes",
  date: "Mais recentes",
  company: "Empresa A-Z",
};

/**
 * Filtros lidos da URL.
 *
 * <p>A URL é o estado da busca: recarregar, compartilhar o link e usar voltar/avançar
 * precisam reproduzir a mesma tela. Valor inválido não derruba nada — cai no padrão —,
 * porque a barra de endereços é editável por qualquer um.
 */
function filtersFromUrl(search: string): { filters: JobFilters; page: number } {
  const params = new URLSearchParams(search);
  const size = Number(params.get("size"));
  const page = Number(params.get("page"));
  return {
    filters: {
      language: params.get("language") ?? "",
      technology: params.get("technology") ?? "",
      level: params.get("level") ?? "",
      workModel: params.get("workModel") ?? "",
      country: (params.get("country") ?? "").toUpperCase(),
      location: params.get("location") ?? "",
      keyword: params.get("keyword") ?? "",
      sort: (["relevance", "date", "company"] as const).includes(
        params.get("sort") as JobFilters["sort"],
      )
        ? (params.get("sort") as JobFilters["sort"])
        : "relevance",
      size: (SIZE_OPTIONS as readonly number[]).includes(size) ? size : EMPTY_FILTERS.size,
    },
    page: Number.isFinite(page) && page > 0 ? page : 0,
  };
}

export function SearchPage({
  resume,
  languages,
  technologies,
  sources,
  countries,
  onResumeUploaded,
  onResumeRemoved,
  onViewProfile,
}: Props) {
  const initial = useRef(filtersFromUrl(window.location.search));
  const [filters, setFilters] = useState<JobFilters>(initial.current.filters);
  const [selectedJobId, setSelectedJobId] = useState<number | null>(null);
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [recommended, setRecommended] = useState(false);

  const hasUsableResume = resume?.parseStatus === "PARSED";
  const { page, loading, error, search } = useJobSearch(hasUsableResume);
  const resultsRef = useRef<HTMLDivElement>(null);

  const runSearch = useCallback(
    (
      next: JobFilters,
      pageIndex: number,
      options: { refresh?: boolean; recommended?: boolean; skipUrl?: boolean } = {},
    ) => {
      setFilters(next);
      if (!options.skipUrl) {
        // Mesma query string da requisição: o link que a pessoa copia da barra de endereços
        // descreve exatamente a busca que ela está vendo.
        const url = `${window.location.pathname}?${buildQuery(next, pageIndex, false)}`;
        if (url !== `${window.location.pathname}${window.location.search}`) {
          window.history.pushState({}, "", url);
        }
      }
      void search(next, pageIndex, {
        refresh: options.refresh ?? false,
        recommended: options.recommended ?? recommended,
      });
    },
    [recommended, search],
  );

  // Carga inicial. `search` muda de identidade quando o currículo entra ou sai, e este
  // efeito não pode rodar de novo nesse caso: jogaria fora os filtros que o usuário acabou
  // de aplicar. A rebusca após enviar/remover o currículo é feita nos handlers.
  //
  // A versão anterior usava uma trava em `useRef` e travava a tela: em desenvolvimento o
  // StrictMode monta, desmonta e remonta: a desmontagem aborta a requisição em andamento,
  // mas a trava sobrevive à remontagem — o efeito desistia, nenhuma busca era refeita e a
  // interface ficava presa em "Buscando oportunidades..." para sempre. A dependência vazia
  // resolve os dois lados: não reage à troca de identidade de `search` e, na remontagem,
  // dispara a busca de novo. O ref guarda a função mais recente sem virar dependência.
  const searchRef = useRef(search);
  searchRef.current = search;
  useEffect(() => {
    void searchRef.current(initial.current.filters, initial.current.page, { recommended: false });
  }, []);

  // Voltar e avançar do navegador refazem a busca daquele ponto do histórico. Sem `skipUrl`
  // aqui a navegação empilharia uma entrada nova a cada volta, e o botão voltar deixaria de
  // funcionar depois do primeiro clique.
  useEffect(() => {
    const onPopState = () => {
      const restored = filtersFromUrl(window.location.search);
      setFilters(restored.filters);
      void searchRef.current(restored.filters, restored.page, { recommended: false });
    };
    window.addEventListener("popstate", onPopState);
    return () => window.removeEventListener("popstate", onPopState);
  }, []);

  const activeFilterCount = (
    ["language", "technology", "level", "workModel", "keyword", "location", "country"] as const
  ).filter((key) => filters[key].trim() !== "").length;

  const total = page?.totalElements ?? 0;
  // O número exibido é o que realmente veio nesta resposta. Dizer "50 vagas encontradas"
  // com 37 na tela é mentira que o usuário confere em dois segundos de rolagem.
  const shown = page?.content.length ?? 0;
  const meta = page?.meta;

  return (
    <>
      <SearchHero sources={sources}>
        <SearchBar
          filters={filters}
          countries={countries}
          onChange={setFilters}
          onSubmit={() => runSearch(filters, 0)}
          onOpenFilters={() => setFiltersOpen(true)}
          activeFilterCount={activeFilterCount}
          loading={loading}
        />
      </SearchHero>

      <div className="mx-auto max-w-6xl px-4 py-6 sm:px-6 sm:py-8">
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-[1fr_20rem]">
          <div className="min-w-0" ref={resultsRef}>
            {activeFilterCount > 0 && (
              <div className="mb-5">
                <FilterChips
                  filters={filters}
                  languages={languages}
                  technologies={technologies}
                  countries={countries}
                  onRemove={(key) => runSearch({ ...filters, [key]: "" }, 0)}
                  onClearAll={() => runSearch({ ...EMPTY_FILTERS, sort: filters.sort, size: filters.size }, 0)}
                />
              </div>
            )}

            <div className="mb-4 flex flex-wrap items-end justify-between gap-3">
              <div>
                <h2 className="text-lg font-semibold tracking-tight text-slate-900">
                  Vagas encontradas
                </h2>
                <p className="mt-0.5 text-sm text-slate-500" aria-live="polite">
                  {loading
                    ? "Buscando oportunidades..."
                    : `${shown} ${shown === 1 ? "vaga encontrada" : "vagas encontradas"}${
                        total > shown ? ` de ${meta?.truncated ? `${total}+` : total}` : ""
                      }`}
                </p>
                {!loading && meta?.refreshing && (
                  <p className="mt-1 flex items-center gap-1.5 text-xs text-slate-500">
                    <RefreshCw className="size-3.5 animate-spin" aria-hidden="true" />
                    Atualizando vagas em segundo plano...
                  </p>
                )}
              </div>

              <div className="flex items-center gap-2">
                <label htmlFor="ordenacao" className="sr-only">
                  Ordenar resultados
                </label>
                <select
                  id="ordenacao"
                  value={filters.sort}
                  onChange={(event) =>
                    runSearch({ ...filters, sort: event.target.value as JobFilters["sort"] }, 0)
                  }
                  className="rounded-control border border-slate-200 bg-white px-3 py-2 text-sm font-medium text-slate-700 transition hover:border-slate-300 focus:border-brand-400 focus:outline-none focus:ring-2 focus:ring-brand-500/20"
                >
                  {(Object.keys(SORT_LABEL) as JobFilters["sort"][]).map((value) => (
                    <option key={value} value={value}>
                      {SORT_LABEL[value]}
                    </option>
                  ))}
                </select>
              </div>
            </div>

            {hasUsableResume && (
              <label className="mb-4 flex cursor-pointer items-start gap-3 rounded-card border border-brand-100 bg-brand-50/50 p-3.5 transition hover:border-brand-200">
                <input
                  type="checkbox"
                  checked={recommended}
                  onChange={(event) => {
                    const next = event.target.checked;
                    setRecommended(next);
                    void search(filters, 0, { recommended: next });
                  }}
                  className="mt-0.5 size-4 rounded border-slate-300 text-brand-600 focus:ring-brand-500"
                />
                <span className="text-sm">
                  <span className="flex items-center gap-1.5 font-medium text-brand-900">
                    <Sparkles className="size-4" aria-hidden="true" />
                    Ordenar por compatibilidade com o meu currículo
                  </span>
                  <span className="mt-0.5 block text-xs text-brand-700/80">
                    As vagas mais aderentes ao seu perfil aparecem primeiro.
                  </span>
                </span>
              </label>
            )}

            {meta?.failures && meta.failures.length > 0 && (
              <p className="mb-4 rounded-control bg-amber-50 p-3 text-xs text-amber-800">
                Algumas fontes não responderam:{" "}
                {meta.failures.map((failure) => failure.source).join(", ")}. Os resultados
                abaixo vêm das demais.
              </p>
            )}

            {loading && <LoadingState />}

            {!loading && error && (
              <ErrorState message={error} onRetry={() => runSearch(filters, 0)} />
            )}

            {!loading && !error && page && page.content.length === 0 && (
              <EmptyState onClear={() => runSearch({ ...EMPTY_FILTERS, sort: filters.sort, size: filters.size }, 0)} />
            )}

            {!loading && !error && page && page.content.length > 0 && (
              <JobList
                page={page}
                onOpen={(job) => setSelectedJobId(job.id)}
                onPageChange={(next) => {
                  runSearch(filters, next);
                  resultsRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
                }}
              />
            )}
          </div>

          <aside className="lg:sticky lg:top-24 lg:self-start">
            <ResumeUploader
              resume={resume}
              onViewProfile={onViewProfile}
              onUploaded={(uploaded) => {
                onResumeUploaded(uploaded);
                const usable = uploaded.parseStatus === "PARSED";
                setRecommended(usable);
                void search(filters, 0, { recommended: usable });
              }}
              onRemoved={() => {
                onResumeRemoved();
                setRecommended(false);
                void search(filters, 0, { recommended: false });
              }}
            />
          </aside>
        </div>
      </div>

      <FilterPanel
        open={filtersOpen}
        filters={filters}
        languages={languages}
        technologies={technologies}
        onClose={() => setFiltersOpen(false)}
        onApply={(next) => {
          setFiltersOpen(false);
          runSearch(next, 0);
        }}
      />

      <JobDetailsDrawer jobId={selectedJobId} onClose={() => setSelectedJobId(null)} />
    </>
  );
}
