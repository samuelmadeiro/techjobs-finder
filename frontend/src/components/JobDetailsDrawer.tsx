import { useCallback, useEffect, useState } from "react";
import {
  ArrowLeft,
  Briefcase,
  CalendarDays,
  Check,
  ExternalLink,
  MapPin,
  Monitor,
  Wallet,
  X,
} from "lucide-react";
import { api } from "../api/client";
import type { JobDetails } from "../api/types";
import { CompanyInfo } from "./CompanyInfo";
import { CompatibilityScore } from "./CompatibilityScore";
import { TechnologyBadge } from "./TechnologyBadge";
import { useDialog } from "../hooks/useDialog";
import { LEVEL_LABEL, WORK_MODEL_LABEL, formatDate, formatSalary } from "../lib/labels";

interface Props {
  jobId: number | null;
  onClose: () => void;
}

/**
 * Painel com a vaga completa.
 *
 * <p>Drawer em vez de página: o usuário volta para a lista sem perder a rolagem nem
 * refazer a busca, que numa tela de comparação é o que mais importa. No mobile ele
 * ocupa a tela inteira, porque um painel de 90% da largura só desperdiça espaço.
 */
export function JobDetailsDrawer({ jobId, onClose }: Props) {
  const [job, setJob] = useState<JobDetails | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const open = jobId != null;
  const handleClose = useCallback(() => onClose(), [onClose]);
  const panelRef = useDialog(open, handleClose);

  useEffect(() => {
    if (jobId == null) {
      setJob(null);
      setError(null);
      return;
    }

    const controller = new AbortController();
    setLoading(true);
    setError(null);
    api
      .jobDetails(jobId, controller.signal)
      .then((details) => setJob(details))
      .catch((cause: unknown) => {
        if (controller.signal.aborted) return;
        setError(cause instanceof Error ? cause.message : "Falha ao carregar a vaga.");
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });

    return () => controller.abort();
  }, [jobId]);

  if (!open) return null;

  const salary = formatSalary(job?.salary);
  const matched = new Set(job?.compatibility?.matchedSkills ?? []);
  const missing = new Set(job?.compatibility?.missingSkills ?? []);
  const technologies = job ? [...job.languages, ...job.technologies] : [];

  return (
    <div className="fixed inset-0 z-50 flex justify-end">
      <div
        className="absolute inset-0 animate-fade-in bg-slate-900/40"
        onClick={handleClose}
        aria-hidden="true"
      />

      <aside
        ref={panelRef as React.Ref<HTMLElement>}
        role="dialog"
        aria-modal="true"
        aria-labelledby="titulo-vaga"
        tabIndex={-1}
        className="relative flex h-full w-full animate-slide-in-bottom flex-col bg-white shadow-overlay focus:outline-none sm:max-w-xl sm:animate-slide-in-right lg:max-w-2xl"
      >
        <header className="flex items-start gap-3 border-b border-slate-200 px-4 py-3.5 sm:px-6 sm:py-4">
          <button
            type="button"
            onClick={handleClose}
            aria-label="Voltar para a lista"
            className="-ml-1 rounded-control p-2 text-slate-500 transition hover:bg-slate-100 hover:text-slate-800 sm:hidden"
          >
            <ArrowLeft className="size-5" aria-hidden="true" />
          </button>

          <div className="min-w-0 flex-1">
            <h2
              id="titulo-vaga"
              className="truncate text-base font-semibold text-slate-900 sm:text-lg"
            >
              {job?.title ?? "Carregando vaga..."}
            </h2>
            {job?.source && (
              <p className="mt-0.5 text-xs text-slate-500">Publicada em {job.source.name}</p>
            )}
          </div>

          <button
            type="button"
            onClick={handleClose}
            aria-label="Fechar detalhes"
            className="-mr-1 hidden rounded-control p-2 text-slate-500 transition hover:bg-slate-100 hover:text-slate-700 sm:block"
          >
            <X className="size-5" aria-hidden="true" />
          </button>
        </header>

        <div className="scroll-slim flex-1 overflow-y-auto px-4 py-5 sm:px-6">
          {loading && <DetailsSkeleton />}

          {error && (
            <div
              role="alert"
              className="rounded-control border border-rose-200 bg-rose-50 p-4 text-sm text-rose-700"
            >
              {error}
            </div>
          )}

          {job && !loading && (
            <div className="space-y-6">
              <CompanyInfo company={job.company} size="md" />

              <dl className="grid grid-cols-2 gap-4 rounded-card bg-slate-50 p-4 text-sm sm:grid-cols-3">
                <Info
                  icon={<Monitor className="size-3.5" aria-hidden="true" />}
                  label="Modalidade"
                  value={WORK_MODEL_LABEL[job.workModel]}
                />
                <Info
                  icon={<Briefcase className="size-3.5" aria-hidden="true" />}
                  label="Nível"
                  value={LEVEL_LABEL[job.experienceLevel]}
                />
                <Info
                  icon={<MapPin className="size-3.5" aria-hidden="true" />}
                  label="Localização"
                  value={job.location ?? "Não informada"}
                />
                <Info
                  icon={<Wallet className="size-3.5" aria-hidden="true" />}
                  label="Salário"
                  value={salary ?? "Não informado"}
                />
                <Info
                  icon={<CalendarDays className="size-3.5" aria-hidden="true" />}
                  label="Publicada em"
                  value={formatDate(job.publishedAt) ?? "Não informada"}
                />
                {job.experienceYears != null && (
                  <Info
                    icon={<Check className="size-3.5" aria-hidden="true" />}
                    label="Experiência"
                    value={`${job.experienceYears} ano(s)`}
                  />
                )}
              </dl>

              {job.compatibility && (
                <section className="rounded-card border border-brand-100 bg-brand-50/40 p-4">
                  <CompatibilityScore compatibility={job.compatibility} variant="full" />
                </section>
              )}

              {technologies.length > 0 && (
                <Section title="Tecnologias">
                  <div className="flex flex-wrap gap-1.5">
                    {technologies.map((name) => (
                      <TechnologyBadge
                        key={name}
                        name={name}
                        matched={matched.has(name)}
                        missing={missing.has(name)}
                      />
                    ))}
                  </div>
                </Section>
              )}

              {job.requirements.length > 0 && (
                <Section title="Requisitos">
                  <BulletList items={job.requirements} />
                </Section>
              )}

              {job.niceToHave.length > 0 && (
                <Section title="Diferenciais">
                  <BulletList items={job.niceToHave} />
                </Section>
              )}

              {job.description && (
                <Section title="Sobre a vaga">
                  {/* Texto puro vindo do backend (o HTML da fonte já foi removido lá).
                      Renderizado como texto, nunca como markup. */}
                  <p className="whitespace-pre-line text-sm leading-relaxed text-slate-700">
                    {job.description}
                  </p>
                </Section>
              )}

              {job.benefits && (
                <Section title="Benefícios">
                  <p className="text-sm leading-relaxed text-slate-700">{job.benefits}</p>
                </Section>
              )}

              {job.company?.description && (
                <Section title={`Sobre ${job.company.name}`}>
                  <p className="text-sm leading-relaxed text-slate-700">
                    {job.company.description}
                  </p>
                </Section>
              )}
            </div>
          )}
        </div>

        {job && (
          <footer className="border-t border-slate-200 bg-white px-4 py-3.5 sm:px-6 sm:py-4">
            {/* Link original, sem intermediário: é assim que a pessoa se candidata. */}
            <a
              href={job.originalUrl}
              target="_blank"
              rel="noopener noreferrer nofollow"
              className="flex w-full items-center justify-center gap-2 rounded-control bg-brand-600 px-5 py-3 text-sm font-semibold text-white transition hover:bg-brand-700"
            >
              Acessar vaga original
              <ExternalLink className="size-4" aria-hidden="true" />
            </a>
            <p className="mt-2 truncate text-center text-xs text-slate-500" title={job.originalUrl}>
              {job.originalUrl}
            </p>
          </footer>
        )}
      </aside>
    </div>
  );
}

function DetailsSkeleton() {
  return (
    <div className="animate-pulse space-y-5">
      <div className="flex items-center gap-3">
        <div className="size-12 rounded-control bg-slate-200" />
        <div className="h-3.5 w-40 rounded bg-slate-200" />
      </div>
      <div className="h-24 rounded-card bg-slate-100" />
      <div className="space-y-2">
        <div className="h-3 w-full rounded bg-slate-100" />
        <div className="h-3 w-5/6 rounded bg-slate-100" />
        <div className="h-3 w-4/6 rounded bg-slate-100" />
      </div>
    </div>
  );
}

function Info({
  icon,
  label,
  value,
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
}) {
  return (
    <div>
      <dt className="flex items-center gap-1.5 text-xs text-slate-500">
        <span className="text-slate-500" aria-hidden="true">
          {icon}
        </span>
        {label}
      </dt>
      <dd className="mt-1 font-medium text-slate-800">{value}</dd>
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section>
      <h3 className="mb-2.5 text-sm font-semibold text-slate-900">{title}</h3>
      {children}
    </section>
  );
}

function BulletList({ items }: { items: string[] }) {
  return (
    <ul className="space-y-2">
      {items.map((item, index) => (
        <li key={index} className="flex gap-2.5 text-sm text-slate-700">
          <Check className="mt-0.5 size-4 shrink-0 text-brand-500" aria-hidden="true" />
          <span>{item}</span>
        </li>
      ))}
    </ul>
  );
}
