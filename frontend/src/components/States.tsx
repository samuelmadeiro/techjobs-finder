import { useEffect, useState } from "react";
import { AlertCircle, RefreshCw, SearchX } from "lucide-react";

/**
 * Estados de tela: carregando, vazio e erro.
 *
 * Ficam juntos porque são a mesma decisão de produto — o que mostrar quando não há
 * lista para mostrar — e nenhum deles sozinho justifica um arquivo.
 */

const LOADING_MESSAGES = [
  "Buscando vagas...",
  "Analisando fontes...",
  "Calculando compatibilidade...",
];

/** Esqueleto com a mesma altura do card real, para o layout não pular ao carregar. */
function SkeletonCard() {
  return (
    <div className="rounded-card border border-slate-200 bg-white p-4 shadow-card sm:p-5">
      <div className="animate-pulse">
        <div className="flex items-start gap-4">
          <div className="flex flex-1 items-center gap-3">
            <div className="size-10 shrink-0 rounded-control bg-slate-200" />
            <div className="flex-1 space-y-2">
              <div className="h-3 w-32 rounded bg-slate-200" />
              <div className="h-4 w-3/5 rounded bg-slate-200" />
            </div>
          </div>
          <div className="hidden w-44 space-y-2 border-l border-slate-100 pl-4 sm:block">
            <div className="h-3 w-24 rounded bg-slate-100" />
            <div className="h-1.5 w-full rounded-full bg-slate-100" />
          </div>
        </div>
        <div className="mt-4 space-y-2">
          <div className="h-3 w-full rounded bg-slate-100" />
          <div className="h-3 w-4/5 rounded bg-slate-100" />
        </div>
        <div className="mt-4 flex gap-1.5">
          <div className="h-6 w-16 rounded-md bg-slate-100" />
          <div className="h-6 w-20 rounded-md bg-slate-100" />
          <div className="h-6 w-14 rounded-md bg-slate-100" />
        </div>
        <div className="mt-4 flex gap-2 border-t border-slate-100 pt-3.5">
          <div className="h-9 w-28 rounded-control bg-slate-100" />
          <div className="h-9 w-32 rounded-control bg-slate-100" />
        </div>
      </div>
    </div>
  );
}

export function LoadingState({ count = 4 }: { count?: number }) {
  const [messageIndex, setMessageIndex] = useState(0);

  // As mensagens rodam para deixar claro que a busca tem etapas e não travou.
  useEffect(() => {
    const timer = window.setInterval(
      () => setMessageIndex((current) => (current + 1) % LOADING_MESSAGES.length),
      1600,
    );
    return () => window.clearInterval(timer);
  }, []);

  return (
    <div>
      <p className="mb-4 flex items-center gap-2 text-sm text-slate-500" aria-live="polite">
        <span
          className="size-3.5 animate-spin rounded-full border-2 border-brand-200 border-t-brand-600"
          aria-hidden="true"
        />
        {LOADING_MESSAGES[messageIndex]}
      </p>
      <div className="space-y-3">
        {Array.from({ length: count }, (_, index) => (
          <SkeletonCard key={index} />
        ))}
      </div>
    </div>
  );
}

export function EmptyState({ onClear }: { onClear?: () => void }) {
  return (
    <div className="animate-fade-in rounded-card border border-dashed border-slate-300 bg-white px-6 py-14 text-center">
      <span
        className="mx-auto flex size-12 items-center justify-center rounded-full bg-slate-100 text-slate-500"
        aria-hidden="true"
      >
        <SearchX className="size-6" />
      </span>
      <h3 className="mt-4 text-base font-semibold text-slate-800">
        Não encontramos vagas com esses filtros.
      </h3>
      <ul className="mx-auto mt-3 max-w-sm space-y-1 text-sm text-slate-500">
        <li>Remova alguns filtros.</li>
        <li>Altere a localização.</li>
        <li>Pesquise outra tecnologia.</li>
      </ul>
      {onClear && (
        <button
          type="button"
          onClick={onClear}
          className="mt-6 rounded-control border border-slate-300 px-5 py-2.5 text-sm font-medium text-slate-700 transition hover:bg-slate-50"
        >
          Limpar filtros
        </button>
      )}
    </div>
  );
}

export function ErrorState({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div
      role="alert"
      className="animate-fade-in rounded-card border border-rose-200 bg-rose-50 px-6 py-12 text-center"
    >
      <span
        className="mx-auto flex size-12 items-center justify-center rounded-full bg-rose-100 text-rose-600"
        aria-hidden="true"
      >
        <AlertCircle className="size-6" />
      </span>
      <h3 className="mt-4 text-base font-semibold text-rose-900">
        Não foi possível carregar as vagas.
      </h3>
      <p className="mx-auto mt-1 max-w-md text-sm text-rose-700">{message}</p>
      <button
        type="button"
        onClick={onRetry}
        className="mt-6 inline-flex items-center gap-2 rounded-control bg-rose-600 px-5 py-2.5 text-sm font-medium text-white transition hover:bg-rose-700"
      >
        <RefreshCw className="size-4" aria-hidden="true" />
        Tentar novamente
      </button>
    </div>
  );
}
