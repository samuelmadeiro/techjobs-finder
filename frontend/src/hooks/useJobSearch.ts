import { useCallback, useEffect, useRef, useState } from "react";
import { api, describeError } from "../api/client";
import type { JobFilters, JobSummary, PageResponse } from "../api/types";

interface State {
  page: PageResponse<JobSummary> | null;
  loading: boolean;
  error: string | null;
}

/**
 * Busca de vagas com cancelamento.
 *
 * Cada nova busca aborta a anterior: sem isso, uma resposta lenta pode chegar depois de
 * uma rápida e sobrescrever a tela com resultados de um filtro que o usuário já trocou.
 */
export function useJobSearch(hasResume: boolean) {
  const [state, setState] = useState<State>({ page: null, loading: false, error: null });
  const controllerRef = useRef<AbortController | null>(null);

  const search = useCallback(
    async (
      filters: JobFilters,
      page: number,
      options: { refresh?: boolean; recommended?: boolean } = {},
    ) => {
      controllerRef.current?.abort();
      const controller = new AbortController();
      controllerRef.current = controller;

      setState((current) => ({ ...current, loading: true, error: null }));
      try {
        // A quantidade é escolhida pelo usuário e viaja dentro dos filtros: ela faz parte
        // da identidade da busca (entra na chave do cache de páginas do backend), então não
        // pode ser uma constante escondida aqui.
        const result = await api.searchJobs(filters, page, {
          refresh: options.refresh ?? false,
          // Só pede recomendação quando há currículo: sem ele o backend responde 400.
          recommended: (options.recommended ?? false) && hasResume,
          signal: controller.signal,
        });
        if (controller.signal.aborted) return;
        setState({ page: result, loading: false, error: null });
      } catch (cause) {
        if (controller.signal.aborted) return;
        setState({
          page: null,
          loading: false,
          error: describeError(cause, "Falha ao buscar vagas."),
        });
      }
    },
    [hasResume],
  );

  useEffect(() => () => controllerRef.current?.abort(), []);

  return { ...state, search };
}
