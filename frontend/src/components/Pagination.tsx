import { ChevronLeft, ChevronRight } from "lucide-react";

interface Props {
  page: number;
  totalPages: number;
  last: boolean;
  onChange: (page: number) => void;
}

/** Janela de páginas numeradas ao redor da atual, com reticências nas pontas. */
function pageWindow(current: number, total: number): (number | "gap")[] {
  if (total <= 7) {
    return Array.from({ length: total }, (_, index) => index);
  }

  const items: (number | "gap")[] = [0];
  const from = Math.max(1, current - 1);
  const to = Math.min(total - 2, current + 1);

  if (from > 1) items.push("gap");
  for (let page = from; page <= to; page++) items.push(page);
  if (to < total - 2) items.push("gap");

  items.push(total - 1);
  return items;
}

export function Pagination({ page, totalPages, last, onChange }: Props) {
  if (totalPages <= 1) return null;

  const items = pageWindow(page, totalPages);

  return (
    <nav className="mt-6 flex items-center justify-between gap-3" aria-label="Paginação">
      <button
        type="button"
        onClick={() => onChange(page - 1)}
        disabled={page === 0}
        className="inline-flex items-center gap-1.5 rounded-control border border-slate-200 px-3 py-2 text-sm font-medium text-slate-700 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-40 sm:px-4"
      >
        <ChevronLeft className="size-4" aria-hidden="true" />
        <span className="hidden sm:inline">Anterior</span>
        <span className="sr-only sm:hidden">Página anterior</span>
      </button>

      {/* Desktop: páginas numeradas. Mobile: só a posição, que cabe e basta. */}
      <ul className="hidden items-center gap-1 sm:flex">
        {items.map((item, index) =>
          item === "gap" ? (
            <li key={`gap-${index}`} className="px-1 text-sm text-slate-500" aria-hidden="true">
              …
            </li>
          ) : (
            <li key={item}>
              <button
                type="button"
                onClick={() => onChange(item)}
                aria-current={item === page ? "page" : undefined}
                aria-label={`Página ${item + 1}`}
                className={`min-w-9 rounded-control px-3 py-2 text-sm font-medium transition ${
                  item === page
                    ? "bg-brand-600 text-white"
                    : "text-slate-600 hover:bg-slate-100 hover:text-slate-900"
                }`}
              >
                {item + 1}
              </button>
            </li>
          ),
        )}
      </ul>

      <span className="text-sm text-slate-500 sm:hidden" aria-live="polite">
        Página {page + 1} de {totalPages}
      </span>

      <button
        type="button"
        onClick={() => onChange(page + 1)}
        disabled={last}
        className="inline-flex items-center gap-1.5 rounded-control border border-slate-200 px-3 py-2 text-sm font-medium text-slate-700 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-40 sm:px-4"
      >
        <span className="hidden sm:inline">Próxima</span>
        <span className="sr-only sm:hidden">Próxima página</span>
        <ChevronRight className="size-4" aria-hidden="true" />
      </button>
    </nav>
  );
}
