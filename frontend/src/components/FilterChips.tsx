import { X } from "lucide-react";
import type {
  CountryOption,
  ExperienceLevel,
  JobFilters,
  TechnologyOption,
  WorkModel,
} from "../api/types";
import { LEVEL_LABEL, WORK_MODEL_LABEL } from "../lib/labels";

interface Props {
  filters: JobFilters;
  languages: TechnologyOption[];
  technologies: TechnologyOption[];
  countries: CountryOption[];
  onRemove: (key: keyof JobFilters) => void;
  onClearAll: () => void;
}

/**
 * Filtros ativos, cada um removível.
 *
 * <p>A ordenação não vira chip: ela sempre tem um valor e aparece no seletor ao lado da
 * contagem de resultados. Chip de algo que nunca sai vira ruído.
 */
export function FilterChips({
  filters,
  languages,
  technologies,
  countries,
  onRemove,
  onClearAll,
}: Props) {
  const nameOf = (options: TechnologyOption[], slug: string) =>
    options.find((option) => option.slug === slug)?.name ?? slug;

  const chips: { key: keyof JobFilters; label: string }[] = [];

  if (filters.language) {
    chips.push({ key: "language", label: nameOf(languages, filters.language) });
  }
  if (filters.technology) {
    chips.push({ key: "technology", label: nameOf(technologies, filters.technology) });
  }
  if (filters.level) {
    chips.push({ key: "level", label: LEVEL_LABEL[filters.level as ExperienceLevel] ?? filters.level });
  }
  if (filters.workModel) {
    chips.push({
      key: "workModel",
      label: WORK_MODEL_LABEL[filters.workModel as WorkModel] ?? filters.workModel,
    });
  }
  if (filters.country) {
    // O chip mostra bandeira e nome; o filtro continua sendo o código.
    const country = countries.find((option) => option.code === filters.country);
    chips.push({
      key: "country",
      label: country ? `${country.flag} ${country.name}` : filters.country,
    });
  }
  if (filters.keyword.trim()) {
    chips.push({ key: "keyword", label: `"${filters.keyword.trim()}"` });
  }
  if (filters.location.trim()) {
    chips.push({ key: "location", label: filters.location.trim() });
  }

  if (chips.length === 0) return null;

  return (
    <div className="flex flex-wrap items-center gap-2" aria-label="Filtros aplicados">
      {chips.map((chip) => (
        <span
          key={chip.key}
          className="inline-flex items-center gap-1.5 rounded-full border border-brand-200 bg-brand-50 py-1 pr-1 pl-3 text-sm font-medium text-brand-800"
        >
          {chip.label}
          <button
            type="button"
            onClick={() => onRemove(chip.key)}
            aria-label={`Remover filtro ${chip.label}`}
            className="rounded-full p-0.5 text-brand-500 transition hover:bg-brand-200/70 hover:text-brand-800"
          >
            <X className="size-3.5" aria-hidden="true" />
          </button>
        </span>
      ))}

      {chips.length > 1 && (
        <button
          type="button"
          onClick={onClearAll}
          className="rounded-control px-2 py-1 text-sm font-medium text-slate-500 transition hover:text-slate-800"
        >
          Limpar tudo
        </button>
      )}
    </div>
  );
}
