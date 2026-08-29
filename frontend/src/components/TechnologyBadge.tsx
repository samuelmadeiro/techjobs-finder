import { Check, Plus } from "lucide-react";

interface Props {
  name: string;
  /** Tecnologia que a vaga pede e o currículo também tem. */
  matched?: boolean;
  /** Tecnologia que a vaga pede e o currículo não menciona. */
  missing?: boolean;
  emphasis?: boolean;
}

/** Etiqueta de tecnologia. A cor comunica a relação com o currículo, não a categoria. */
export function TechnologyBadge({ name, matched, missing, emphasis }: Props) {
  const tone = matched
    ? "bg-emerald-50 text-emerald-700 ring-emerald-200"
    : missing
      ? "bg-amber-50 text-amber-800 ring-amber-200"
      : emphasis
        ? "bg-brand-50 text-brand-700 ring-brand-200"
        : "bg-slate-50 text-slate-600 ring-slate-200";

  return (
    <span
      className={`inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs font-medium ring-1 ring-inset ${tone}`}
    >
      {matched && <Check className="size-3" aria-hidden="true" />}
      {missing && !matched && <Plus className="size-3" aria-hidden="true" />}
      {name}
    </span>
  );
}
