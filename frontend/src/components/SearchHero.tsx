import { Database, RefreshCw, Sparkles } from "lucide-react";
import type { SourceInfo } from "../api/types";

interface Props {
  sources: SourceInfo[];
  children: React.ReactNode;
}

/**
 * Apresentação do produto acima da busca.
 *
 * Os indicadores usam apenas dados que a API realmente fornece — o número de fontes
 * ativas vem de `/api/sources`. Nenhum número é estimado ou inventado.
 */
export function SearchHero({ sources, children }: Props) {
  const activeSources = sources.filter((source) => source.enabled).length;

  return (
    <section className="border-b border-slate-200 bg-white">
      <div className="mx-auto max-w-6xl px-4 py-10 sm:px-6 sm:py-14">
        <div className="max-w-2xl">
          <h1 className="text-2xl font-semibold tracking-tight text-slate-900 sm:text-4xl">
            Encontre a vaga certa para sua carreira
          </h1>
          <p className="mt-3 text-[15px] leading-relaxed text-slate-600 sm:text-base">
            Pesquise oportunidades de tecnologia em diferentes fontes e descubra quais
            combinam mais com o seu perfil.
          </p>
        </div>

        <div className="mt-7">{children}</div>

        <ul className="mt-6 flex flex-wrap items-center gap-x-6 gap-y-2 text-xs text-slate-500">
          {activeSources > 0 && (
            <Indicator icon={<Database className="size-3.5" aria-hidden="true" />}>
              {activeSources} {activeSources === 1 ? "fonte agregada" : "fontes agregadas"}
            </Indicator>
          )}
          <Indicator icon={<RefreshCw className="size-3.5" aria-hidden="true" />}>
            Vagas atualizadas automaticamente
          </Indicator>
          <Indicator icon={<Sparkles className="size-3.5" aria-hidden="true" />}>
            Compatibilidade com o seu currículo
          </Indicator>
        </ul>
      </div>
    </section>
  );
}

function Indicator({ icon, children }: { icon: React.ReactNode; children: React.ReactNode }) {
  return (
    <li className="flex items-center gap-1.5">
      <span className="text-brand-500">{icon}</span>
      {children}
    </li>
  );
}
