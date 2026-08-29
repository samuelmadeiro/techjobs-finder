import { Briefcase, Clock, ExternalLink, MapPin, Wallet } from "lucide-react";
import type { JobSummary } from "../api/types";
import { CompanyInfo } from "./CompanyInfo";
import { CompatibilityScore } from "./CompatibilityScore";
import { TechnologyBadge } from "./TechnologyBadge";
import {
  LEVEL_LABEL,
  WORK_MODEL_DOT,
  WORK_MODEL_LABEL,
  formatSalary,
  relativeDate,
} from "../lib/labels";

interface Props {
  job: JobSummary;
  onOpen: (job: JobSummary) => void;
}

const MAX_VISIBLE_TECHNOLOGIES = 6;

/**
 * Card da listagem: só o que ajuda a decidir se vale abrir. A descrição completa fica
 * no detalhe — carregar tudo aqui deixaria a lista pesada sem melhorar a decisão.
 */
export function JobCard({ job, onOpen }: Props) {
  const salary = formatSalary(job.salary);
  const published = relativeDate(job.publishedAt);
  const matched = new Set(job.compatibility?.matchedSkills ?? []);
  const missing = new Set(job.compatibility?.missingSkills ?? []);

  const technologies = [...job.languages, ...job.technologies];
  const visible = technologies.slice(0, MAX_VISIBLE_TECHNOLOGIES);
  const overflow = technologies.length - visible.length;

  return (
    <article
      onClick={() => onOpen(job)}
      onKeyDown={(event) => {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          onOpen(job);
        }
      }}
      tabIndex={0}
      role="button"
      aria-label={`Ver detalhes da vaga ${job.title}`}
      className="group animate-slide-up cursor-pointer rounded-card border border-slate-200 bg-white p-4 shadow-card transition duration-200 hover:-translate-y-0.5 hover:border-brand-300 hover:shadow-lifted sm:p-5"
    >
      <div className="flex items-start gap-4">
        <div className="min-w-0 flex-1">
          <CompanyInfo company={job.company} />

          <h3 className="mt-3 text-base font-semibold text-slate-900 transition group-hover:text-brand-700 sm:text-[17px]">
            {job.title}
          </h3>

          <ul className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1.5 text-xs text-slate-500">
            <Meta icon={<span className={`size-2 rounded-full ${WORK_MODEL_DOT[job.workModel]}`} />}>
              {WORK_MODEL_LABEL[job.workModel]}
            </Meta>
            {job.location && (
              <Meta icon={<MapPin className="size-3.5" aria-hidden="true" />}>
                <span className="max-w-[16rem] truncate">{job.location}</span>
              </Meta>
            )}
            <Meta icon={<Briefcase className="size-3.5" aria-hidden="true" />}>
              {LEVEL_LABEL[job.experienceLevel]}
            </Meta>
            {salary && (
              <Meta icon={<Wallet className="size-3.5" aria-hidden="true" />}>
                <span className="font-medium text-emerald-700">{salary}</span>
              </Meta>
            )}
            {published && (
              <Meta icon={<Clock className="size-3.5" aria-hidden="true" />}>{published}</Meta>
            )}
          </ul>
        </div>

        {/* A compatibilidade é o diferencial do produto: no desktop ela fica à direita,
            alinhada ao topo, onde o olho bate antes de ler a descrição. */}
        {job.compatibility && (
          <div className="hidden w-44 shrink-0 border-l border-slate-100 pl-4 sm:block">
            <CompatibilityScore compatibility={job.compatibility} variant="expandable" />
          </div>
        )}
      </div>

      {job.shortDescription && (
        <p className="mt-3 line-clamp-2 text-sm leading-relaxed text-slate-600">
          {job.shortDescription}
        </p>
      )}

      {visible.length > 0 && (
        <div className="mt-3.5 flex flex-wrap gap-1.5">
          {visible.map((name) => (
            <TechnologyBadge
              key={name}
              name={name}
              matched={matched.has(name)}
              missing={missing.has(name)}
            />
          ))}
          {overflow > 0 && (
            <span className="inline-flex items-center px-1.5 text-xs text-slate-500">
              +{overflow}
            </span>
          )}
        </div>
      )}

      {job.compatibility && (
        <div className="mt-4 border-t border-slate-100 pt-3.5 sm:hidden">
          <CompatibilityScore compatibility={job.compatibility} variant="expandable" />
        </div>
      )}

      <div className="mt-4 flex flex-wrap items-center gap-2 border-t border-slate-100 pt-3.5">
        <button
          type="button"
          onClick={(event) => {
            event.stopPropagation();
            onOpen(job);
          }}
          className="rounded-control bg-brand-600 px-4 py-2 text-sm font-medium text-white transition hover:bg-brand-700"
        >
          Ver detalhes
        </button>

        <a
          href={job.originalUrl}
          target="_blank"
          rel="noopener noreferrer nofollow"
          onClick={(event) => event.stopPropagation()}
          className="inline-flex items-center gap-1.5 rounded-control border border-slate-200 px-4 py-2 text-sm font-medium text-slate-700 transition hover:border-slate-300 hover:bg-slate-50"
        >
          Acessar vaga
          <ExternalLink className="size-3.5" aria-hidden="true" />
        </a>

        {job.source && (
          <span className="ml-auto text-xs text-slate-500">via {job.source.name}</span>
        )}
      </div>
    </article>
  );
}

function Meta({ icon, children }: { icon: React.ReactNode; children: React.ReactNode }) {
  return (
    <li className="flex items-center gap-1.5">
      <span className="shrink-0 text-slate-500" aria-hidden="true">
        {icon}
      </span>
      {children}
    </li>
  );
}
