import { useCallback, useEffect, useState } from "react";
import { ApiError, api } from "../api/client";
import type { Resume } from "../api/types";

/**
 * Currículo do usuário atual.
 *
 * A identidade agora vem do cookie de sessão, que o JavaScript não enxerga — por isso o
 * hook não tenta adivinhar se há sessão antes de perguntar: ele pergunta ao servidor, e
 * trata 401 (sem sessão ou sessão vencida) e 404 (sessão válida, nenhum currículo) como o
 * mesmo resultado para a tela: nada a mostrar. Nenhum dos dois é erro a exibir.
 */
export function useResume() {
  const [resume, setResume] = useState<Resume | null>(null);
  const [loading, setLoading] = useState(true);

  const reload = useCallback(async (signal?: AbortSignal) => {
    setLoading(true);
    try {
      setResume(await api.currentResume(signal));
    } catch (cause) {
      if (signal?.aborted) return;
      const expected =
        cause instanceof ApiError && (cause.status === 401 || cause.status === 404);
      if (!expected) {
        // Falha real de rede ou do servidor: a tela segue sem currículo, mas o erro fica
        // registrado para quem estiver depurando.
        console.warn("Falha ao carregar o currículo", cause);
      }
      setResume(null);
    } finally {
      if (!signal?.aborted) setLoading(false);
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    void reload(controller.signal);
    return () => controller.abort();
  }, [reload]);

  /** Chamado ao excluir o currículo. A sessão continua: a conta não deixa de existir. */
  const clear = useCallback(() => {
    setResume(null);
  }, []);

  return { resume, loading, setResume, clear, reload };
}
