import { Globe, ListOrdered, MapPin, Search, SlidersHorizontal } from "lucide-react";
import type { CountryOption, JobFilters } from "../api/types";
import { SIZE_OPTIONS } from "../api/types";

interface Props {
  filters: JobFilters;
  countries: CountryOption[];
  onChange: (filters: JobFilters) => void;
  onSubmit: () => void;
  onOpenFilters: () => void;
  activeFilterCount: number;
  loading: boolean;
}

/**
 * Busca principal: palavra-chave, país, quantidade e localização.
 *
 * <p>País e quantidade ficam aqui, e não atrás de "Filtros", porque são decisões que a
 * pessoa toma antes de buscar — "quero vagas no Brasil, umas vinte" — e não refinamentos de
 * um resultado que ela já viu. O resto continua no painel: mostrar oito campos de uma vez
 * faz o usuário parar para ler em vez de buscar.
 *
 * <p>A lista de países vem da API. Sem ela (falha de rede), o seletor fica só com "Todos os
 * países" e a busca continua funcionando — nenhum país fica escrito no código do frontend.
 */
export function SearchBar({
  filters,
  countries,
  onChange,
  onSubmit,
  onOpenFilters,
  activeFilterCount,
  loading,
}: Props) {
  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        onSubmit();
      }}
      role="search"
      className="rounded-card border border-slate-200 bg-white p-2 shadow-card sm:p-2.5"
    >
      <div className="flex flex-col gap-2 lg:flex-row lg:items-center">
        <Field
          icon={<Search className="size-4" aria-hidden="true" />}
          className="lg:flex-[2]"
        >
          <input
            type="search"
            value={filters.keyword}
            onChange={(event) => onChange({ ...filters, keyword: event.target.value })}
            placeholder="Cargo, tecnologia ou palavra-chave"
            aria-label="Cargo, tecnologia ou palavra-chave"
            className="w-full bg-transparent py-2.5 text-[15px] text-slate-900 placeholder:text-slate-500 focus:outline-none"
          />
        </Field>

        <span className="hidden h-8 w-px bg-slate-200 lg:block" aria-hidden="true" />

        <Field icon={<Globe className="size-4" aria-hidden="true" />} className="lg:flex-1">
          <select
            value={filters.country}
            onChange={(event) => onChange({ ...filters, country: event.target.value })}
            aria-label="País"
            className="w-full cursor-pointer appearance-none bg-transparent py-2.5 text-[15px] text-slate-900 focus:outline-none"
          >
            <option value="">🌎 Todos os países</option>
            {countries.map((country) => (
              <option key={country.code} value={country.code}>
                {country.flag} {country.name} ({country.jobCount})
              </option>
            ))}
          </select>
        </Field>

        <span className="hidden h-8 w-px bg-slate-200 lg:block" aria-hidden="true" />

        <Field icon={<ListOrdered className="size-4" aria-hidden="true" />} className="lg:w-44">
          <select
            value={filters.size}
            onChange={(event) => onChange({ ...filters, size: Number(event.target.value) })}
            aria-label="Quantidade de vagas"
            className="w-full cursor-pointer appearance-none bg-transparent py-2.5 text-[15px] text-slate-900 focus:outline-none"
          >
            {SIZE_OPTIONS.map((size) => (
              <option key={size} value={size}>
                {size} vagas
              </option>
            ))}
          </select>
        </Field>

        <span className="hidden h-8 w-px bg-slate-200 lg:block" aria-hidden="true" />

        <Field icon={<MapPin className="size-4" aria-hidden="true" />} className="lg:flex-1">
          <input
            type="text"
            value={filters.location}
            onChange={(event) => onChange({ ...filters, location: event.target.value })}
            placeholder="Cidade (opcional)"
            aria-label="Cidade"
            className="w-full bg-transparent py-2.5 text-[15px] text-slate-900 placeholder:text-slate-500 focus:outline-none"
          />
        </Field>

        <div className="flex gap-2">
          <button
            type="button"
            onClick={onOpenFilters}
            aria-haspopup="dialog"
            className="flex flex-1 items-center justify-center gap-2 rounded-control border border-slate-200 px-4 py-2.5 text-sm font-medium text-slate-700 transition hover:border-slate-300 hover:bg-slate-50 lg:flex-none"
          >
            <SlidersHorizontal className="size-4" aria-hidden="true" />
            Filtros
            {activeFilterCount > 0 && (
              <span className="flex size-5 items-center justify-center rounded-full bg-brand-600 text-[11px] font-semibold text-white">
                {activeFilterCount}
              </span>
            )}
          </button>

          <button
            type="submit"
            disabled={loading}
            className="flex flex-1 items-center justify-center gap-2 rounded-control bg-brand-600 px-6 py-2.5 text-sm font-semibold text-white shadow-subtle transition hover:bg-brand-700 disabled:cursor-not-allowed disabled:opacity-60 lg:flex-none"
          >
            {loading ? (
              <span
                className="size-4 animate-spin rounded-full border-2 border-white/40 border-t-white"
                aria-hidden="true"
              />
            ) : (
              <Search className="size-4" aria-hidden="true" />
            )}
            Buscar
          </button>
        </div>
      </div>
    </form>
  );
}

function Field({
  icon,
  className = "",
  children,
}: {
  icon: React.ReactNode;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <div
      className={`flex items-center gap-2.5 rounded-control px-3 transition focus-within:bg-slate-50 ${className}`}
    >
      <span className="shrink-0 text-slate-500">{icon}</span>
      {children}
    </div>
  );
}
