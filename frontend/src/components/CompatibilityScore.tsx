import { useId, useState } from "react";
import { Check, ChevronDown, TrendingUp } from "lucide-react";
import type { CompatibilityResult } from "../api/types";
import { scoreTone } from "../lib/labels";

interface Props {
  compatibility: CompatibilityResult;
  /**
   * `bar` só a barra; `expandable` a barra com a explicação sob demanda;
   * `full` a explicação sempre aberta (usada na tela de detalhes).
   */
  variant?: "bar" | "expandable" | "full";
}

const HEADLINE: Record<CompatibilityResult["recommendation"], string> = {
  HIGH: "Essa vaga combina bastante com o seu perfil.",
  MEDIUM: "Essa vaga combina em parte com o seu perfil.",
  LOW: "Essa vaga combina pouco com o seu perfil.",
};

/**
 * Compatibilidade entre o currículo e a vaga.
 *
 * <p>O número sozinho não ajuda a decidir nada — o que decide é ver o que bateu e o que
 * faltou. Por isso a explicação vem junto do score, e não escondida em outra tela.
 */
export function CompatibilityScore({ compatibility, variant = "bar" }: Props) {
  const [open, setOpen] = useState(variant === "full");
  const detailsId = useId();
  const tone = scoreTone(compatibility.score);

  const matched = compatibility.matchedSkills;
  const missing = compatibility.missingSkills;
  const showDetails = variant === "full" || open;

  return (
    <div>
      <div className="flex items-baseline justify-between gap-2">
        <span className="text-xs font-medium text-slate-500">Compatibilidade</span>
        <span className={`text-sm font-semibold tabular-nums ${tone.text}`}>
          {compatibility.score}%
        </span>
      </div>

      <div
        className="mt-1.5 h-1.5 w-full overflow-hidden rounded-full bg-slate-100"
        role="progressbar"
        aria-valuenow={compatibility.score}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label={`Compatibilidade de ${compatibility.score}% com o seu currículo`}
      >
        <div
          className={`h-full rounded-full transition-[width] duration-500 ease-out ${tone.bar}`}
          style={{ width: `${compatibility.score}%` }}
        />
      </div>

      {variant === "expandable" && (
        <button
          type="button"
          onClick={(event) => {
            // O card inteiro abre os detalhes da vaga; este botão não pode disparar isso.
            event.stopPropagation();
            setOpen((current) => !current);
          }}
          aria-expanded={open}
          aria-controls={detailsId}
          className="mt-2 flex items-center gap-1 text-xs font-medium text-brand-700 transition hover:text-brand-800"
        >
          {open ? "Ocultar" : "Por que combina?"}
          <ChevronDown
            className={`size-3.5 transition-transform duration-200 ${open ? "rotate-180" : ""}`}
            aria-hidden="true"
          />
        </button>
      )}

      {showDetails && (
        <div
          id={detailsId}
          onClick={(event) => variant === "expandable" && event.stopPropagation()}
          className={variant === "expandable" ? "mt-3 animate-expand overflow-hidden" : "mt-4"}
        >
          {variant === "full" && (
            <p className="mb-3 text-sm text-slate-600">{HEADLINE[compatibility.recommendation]}</p>
          )}

          {matched.length > 0 && (
            <Group title="Por que essa vaga combina?" tone="positive">
              {matched.map((skill) => (
                <Item key={skill} tone="positive">
                  {skill}
                </Item>
              ))}
              {compatibility.experienceMatch && <Item tone="positive">Nível compatível</Item>}
              {compatibility.workModelMatch && <Item tone="positive">Modalidade compatível</Item>}
            </Group>
          )}

          {missing.length > 0 && (
            <Group title="O que pode melhorar?" tone="gap">
              {missing.map((skill) => (
                <Item key={skill} tone="gap">
                  {skill}
                </Item>
              ))}
            </Group>
          )}

          {matched.length === 0 && missing.length === 0 && (
            <p className="text-sm text-slate-500">
              A vaga não detalha tecnologias suficientes para uma comparação confiável.
            </p>
          )}

          {variant === "full" && compatibility.reasons.length > 0 && (
            <ul className="mt-4 space-y-1.5 border-t border-slate-100 pt-3">
              {compatibility.reasons.map((reason, index) => (
                <li
                  key={`${reason.criterion}-${index}`}
                  className={`flex gap-2 text-sm ${
                    reason.positive ? "text-slate-600" : "text-amber-800"
                  }`}
                >
                  <span aria-hidden="true" className="mt-0.5 shrink-0">
                    {reason.positive ? (
                      <Check className="size-4 text-emerald-600" />
                    ) : (
                      <TrendingUp className="size-4 text-amber-600" />
                    )}
                  </span>
                  <span>{reason.text}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}

function Group({
  title,
  tone,
  children,
}: {
  title: string;
  tone: "positive" | "gap";
  children: React.ReactNode;
}) {
  return (
    <div className="mb-3 last:mb-0">
      <h4
        className={`mb-1.5 text-xs font-semibold ${
          tone === "positive" ? "text-emerald-800" : "text-amber-800"
        }`}
      >
        {title}
      </h4>
      <ul className="flex flex-wrap gap-1.5">{children}</ul>
    </div>
  );
}

function Item({ tone, children }: { tone: "positive" | "gap"; children: React.ReactNode }) {
  return (
    <li
      className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-medium ring-1 ring-inset ${
        tone === "positive"
          ? "bg-emerald-50 text-emerald-700 ring-emerald-200"
          : "bg-amber-50 text-amber-800 ring-amber-200"
      }`}
    >
      <span aria-hidden="true">{tone === "positive" ? "✓" : "+"}</span>
      {children}
    </li>
  );
}
