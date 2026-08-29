import type { ExperienceLevel, Salary, WorkModel } from "../api/types";

/** Rótulos em português dos enums do backend, em um lugar só. */

export const WORK_MODEL_LABEL: Record<WorkModel, string> = {
  REMOTE: "Remoto",
  HYBRID: "Híbrido",
  ONSITE: "Presencial",
  UNKNOWN: "Não informado",
};

export const WORK_MODEL_DOT: Record<WorkModel, string> = {
  REMOTE: "bg-emerald-500",
  HYBRID: "bg-amber-500",
  ONSITE: "bg-sky-500",
  UNKNOWN: "bg-slate-300",
};

export const LEVEL_LABEL: Record<ExperienceLevel, string> = {
  INTERNSHIP: "Estágio",
  TRAINEE: "Trainee",
  JUNIOR: "Júnior",
  MID: "Pleno",
  SENIOR: "Sênior",
  UNKNOWN: "Nível não informado",
};

const PERIOD_LABEL = {
  HOUR: "/hora",
  MONTH: "/mês",
  YEAR: "/ano",
} as const;

/** Data curta e legível; entrada inválida não derruba a tela. */
export function formatDate(iso?: string): string | null {
  if (!iso) return null;
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return null;
  return new Intl.DateTimeFormat("pt-BR", { day: "2-digit", month: "short", year: "numeric" }).format(
    date,
  );
}

export function relativeDate(iso?: string): string | null {
  if (!iso) return null;
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return null;

  const days = Math.floor((Date.now() - date.getTime()) / 86_400_000);
  if (days <= 0) return "hoje";
  if (days === 1) return "ontem";
  if (days < 30) return `há ${days} dias`;
  if (days < 60) return "há 1 mês";
  return `há ${Math.floor(days / 30)} meses`;
}

/**
 * Prefere a faixa estruturada; cai no texto original da fonte quando não há números.
 * Nunca inventa valor: sem dado, devolve `null` e a interface omite o campo.
 */
export function formatSalary(salary?: Salary): string | null {
  if (!salary) return null;

  const currency = salary.currency ?? "BRL";
  const period = salary.period ? PERIOD_LABEL[salary.period] : "";

  const format = (value: number) =>
    new Intl.NumberFormat("pt-BR", {
      style: "currency",
      currency,
      maximumFractionDigits: 0,
    }).format(value);

  if (salary.min != null && salary.max != null) {
    return `${format(salary.min)} – ${format(salary.max)}${period}`;
  }
  if (salary.min != null) return `a partir de ${format(salary.min)}${period}`;
  if (salary.max != null) return `até ${format(salary.max)}${period}`;
  return salary.raw?.trim() || null;
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** Verde/âmbar/vermelho conforme a faixa de compatibilidade. */
export function scoreTone(score: number): { bar: string; text: string; ring: string } {
  if (score >= 75) {
    return { bar: "bg-emerald-500", text: "text-emerald-700", ring: "ring-emerald-200" };
  }
  if (score >= 50) {
    return { bar: "bg-amber-500", text: "text-amber-700", ring: "ring-amber-200" };
  }
  return { bar: "bg-rose-400", text: "text-rose-700", ring: "ring-rose-200" };
}
