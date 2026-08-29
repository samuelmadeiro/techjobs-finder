import { useEffect, useState } from "react";
import { X } from "lucide-react";
import type { JobFilters, TechnologyOption } from "../api/types";
import { EMPTY_FILTERS } from "../api/types";
import { useDialog } from "../hooks/useDialog";

interface Props {
  open: boolean;
  filters: JobFilters;
  languages: TechnologyOption[];
  technologies: TechnologyOption[];
  onApply: (filters: JobFilters) => void;
  onClose: () => void;
}

/** Os valores são exatamente os que o backend aceita; os rótulos são só apresentação. */
const LEVELS = [
  { value: "INTERNSHIP", label: "Estágio" },
  { value: "TRAINEE", label: "Trainee" },
  { value: "JUNIOR", label: "Júnior" },
  { value: "MID", label: "Pleno" },
  { value: "SENIOR", label: "Sênior" },
];

const WORK_MODELS = [
  { value: "REMOTE", label: "Remoto" },
  { value: "HYBRID", label: "Híbrido" },
  { value: "ONSITE", label: "Presencial" },
];

const SORTS: { value: JobFilters["sort"]; label: string }[] = [
  { value: "relevance", label: "Mais relevantes" },
  { value: "date", label: "Mais recentes" },
  { value: "company", label: "Empresa A-Z" },
];

const selectClass =
  "w-full rounded-control border border-slate-200 bg-white px-3 py-2.5 text-sm text-slate-800 transition focus:border-brand-400 focus:outline-none focus:ring-2 focus:ring-brand-500/20";

/**
 * Painel de filtros avançados.
 *
 * <p>Edita uma cópia local e só devolve ao aplicar: mexer no filtro real a cada clique
 * dispararia busca a cada ajuste, e o usuário costuma mudar três coisas antes de decidir.
 */
export function FilterPanel({
  open,
  filters,
  languages,
  technologies,
  onApply,
  onClose,
}: Props) {
  const [draft, setDraft] = useState(filters);
  const panelRef = useDialog(open, onClose);

  // Reabrir o painel deve mostrar o estado atual, não o rascunho abandonado antes.
  useEffect(() => {
    if (open) setDraft(filters);
  }, [open, filters]);

  if (!open) return null;

  const update = <K extends keyof JobFilters>(key: K, value: JobFilters[K]) =>
    setDraft((current) => ({ ...current, [key]: value }));

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center sm:items-center">
      <div
        className="absolute inset-0 animate-fade-in bg-slate-900/40"
        onClick={onClose}
        aria-hidden="true"
      />

      <section
        ref={panelRef as React.Ref<HTMLElement>}
        role="dialog"
        aria-modal="true"
        aria-labelledby="titulo-filtros"
        tabIndex={-1}
        className="relative flex max-h-[90vh] w-full animate-slide-in-bottom flex-col rounded-t-card bg-white shadow-overlay focus:outline-none sm:max-w-lg sm:animate-slide-up sm:rounded-card"
      >
        <header className="flex items-center justify-between border-b border-slate-200 px-5 py-4">
          <h2 id="titulo-filtros" className="text-base font-semibold text-slate-900">
            Filtros
          </h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Fechar filtros"
            className="-m-1.5 rounded-control p-1.5 text-slate-500 transition hover:bg-slate-100 hover:text-slate-700"
          >
            <X className="size-5" aria-hidden="true" />
          </button>
        </header>

        <div className="scroll-slim flex-1 overflow-y-auto px-5 py-5">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Field label="Linguagem" htmlFor="filtro-linguagem">
              <select
                id="filtro-linguagem"
                className={selectClass}
                value={draft.language}
                onChange={(event) => update("language", event.target.value)}
              >
                <option value="">Todas</option>
                {languages.map((option) => (
                  <option key={option.slug} value={option.slug}>
                    {option.name} ({option.jobCount})
                  </option>
                ))}
              </select>
            </Field>

            <Field label="Tecnologia" htmlFor="filtro-tecnologia">
              <select
                id="filtro-tecnologia"
                className={selectClass}
                value={draft.technology}
                onChange={(event) => update("technology", event.target.value)}
              >
                <option value="">Todas</option>
                {technologies.map((option) => (
                  <option key={option.slug} value={option.slug}>
                    {option.name} ({option.jobCount})
                  </option>
                ))}
              </select>
            </Field>
          </div>

          <Fieldset legend="Nível" className="mt-5">
            <OptionRow
              options={LEVELS}
              value={draft.level}
              onSelect={(value) => update("level", value)}
            />
          </Fieldset>

          <Fieldset legend="Modalidade" className="mt-5">
            <OptionRow
              options={WORK_MODELS}
              value={draft.workModel}
              onSelect={(value) => update("workModel", value)}
            />
          </Fieldset>

          <div className="mt-5 grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Field label="Palavra-chave" htmlFor="filtro-keyword">
              <input
                id="filtro-keyword"
                type="search"
                className={selectClass}
                placeholder="Ex.: backend, microsserviços"
                value={draft.keyword}
                onChange={(event) => update("keyword", event.target.value)}
              />
            </Field>

            <Field label="Localização" htmlFor="filtro-local">
              <input
                id="filtro-local"
                type="text"
                className={selectClass}
                placeholder="Ex.: João Pessoa"
                value={draft.location}
                onChange={(event) => update("location", event.target.value)}
              />
            </Field>
          </div>

          <Field label="Ordenar por" htmlFor="filtro-ordem" className="mt-5">
            <select
              id="filtro-ordem"
              className={selectClass}
              value={draft.sort}
              onChange={(event) => update("sort", event.target.value as JobFilters["sort"])}
            >
              {SORTS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </Field>
        </div>

        <footer className="flex items-center justify-between gap-3 border-t border-slate-200 px-5 py-4">
          <button
            type="button"
            onClick={() => // Quantidade e ordenação são preferência de exibição, não filtro: limpar os
            // filtros não deve devolver a lista para 20 itens sem o usuário pedir.
            setDraft({ ...EMPTY_FILTERS, sort: draft.sort, size: draft.size })}
            className="rounded-control px-3 py-2 text-sm font-medium text-slate-500 transition hover:text-slate-800"
          >
            Limpar tudo
          </button>
          <button
            type="button"
            onClick={() => onApply(draft)}
            className="rounded-control bg-brand-600 px-6 py-2.5 text-sm font-semibold text-white transition hover:bg-brand-700"
          >
            Aplicar filtros
          </button>
        </footer>
      </section>
    </div>
  );
}

function Field({
  label,
  htmlFor,
  className = "",
  children,
}: {
  label: string;
  htmlFor: string;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <div className={className}>
      <label htmlFor={htmlFor} className="mb-1.5 block text-xs font-medium text-slate-600">
        {label}
      </label>
      {children}
    </div>
  );
}

function Fieldset({
  legend,
  className = "",
  children,
}: {
  legend: string;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <fieldset className={className}>
      <legend className="mb-2 text-xs font-medium text-slate-600">{legend}</legend>
      {children}
    </fieldset>
  );
}

/** Botões em vez de select: são poucas opções e a escolha atual fica visível de relance. */
function OptionRow({
  options,
  value,
  onSelect,
}: {
  options: { value: string; label: string }[];
  value: string;
  onSelect: (value: string) => void;
}) {
  return (
    <div className="flex flex-wrap gap-2">
      <Chip active={value === ""} onClick={() => onSelect("")}>
        Todos
      </Chip>
      {options.map((option) => (
        <Chip
          key={option.value}
          active={value === option.value}
          onClick={() => onSelect(value === option.value ? "" : option.value)}
        >
          {option.label}
        </Chip>
      ))}
    </div>
  );
}

function Chip({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={`rounded-full border px-3.5 py-1.5 text-sm font-medium transition ${
        active
          ? "border-brand-600 bg-brand-600 text-white"
          : "border-slate-200 bg-white text-slate-600 hover:border-slate-300 hover:bg-slate-50"
      }`}
    >
      {children}
    </button>
  );
}
