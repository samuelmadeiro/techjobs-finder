import {
  Award,
  Briefcase,
  FolderGit2,
  GraduationCap,
  Info,
  Layers,
  MapPin,
  Monitor,
  TrendingUp,
} from "lucide-react";
import type { Resume, ResumeSkill } from "../api/types";
import { TechnologyBadge } from "./TechnologyBadge";
import { LEVEL_LABEL, WORK_MODEL_LABEL, formatDate } from "../lib/labels";

/**
 * Perfil lido do currículo, como o sistema o entendeu.
 *
 * <p>Nada aqui é inferido pela interface: todo campo vem do que a API devolveu. Seção
 * sem dado não aparece — inventar um "não informado" para tudo só cria ruído.
 */
export function ResumeProfile({ resume }: { resume: Resume }) {
  const known = resume.skills.filter((skill) => skill.known);
  const unknown = resume.skills.filter((skill) => !skill.known);

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-start justify-between gap-4 border-b border-slate-200 pb-5">
        <div className="min-w-0">
          <h2 className="text-xl font-semibold tracking-tight text-slate-900">
            {resume.candidateName ?? "Perfil profissional"}
          </h2>
          {resume.headline && <p className="mt-1 text-sm text-slate-600">{resume.headline}</p>}
          <p className="mt-1.5 text-xs text-slate-500">
            Currículo enviado em {formatDate(resume.uploadedAt) ?? "data desconhecida"}
          </p>
        </div>

        {known.length > 0 && (
          <span className="inline-flex items-center gap-1.5 rounded-full bg-emerald-50 px-3 py-1.5 text-xs font-medium text-emerald-700 ring-1 ring-emerald-200">
            <TrendingUp className="size-3.5" aria-hidden="true" />
            Recomendações ativas
          </span>
        )}
      </header>

      <dl className="grid grid-cols-2 gap-4 rounded-card bg-slate-50 p-4 sm:grid-cols-4">
        <Field
          icon={<Briefcase className="size-3.5" aria-hidden="true" />}
          label="Nível"
          value={LEVEL_LABEL[resume.experienceLevel]}
        />
        <Field
          icon={<TrendingUp className="size-3.5" aria-hidden="true" />}
          label="Experiência"
          value={
            resume.experienceYears != null
              ? `${resume.experienceYears} ano(s)`
              : "Não informada"
          }
        />
        <Field
          icon={<Monitor className="size-3.5" aria-hidden="true" />}
          label="Preferência"
          value={
            resume.preferredWorkModel
              ? WORK_MODEL_LABEL[resume.preferredWorkModel]
              : "Não informada"
          }
        />
        <Field
          icon={<MapPin className="size-3.5" aria-hidden="true" />}
          label="Localização"
          value={resume.location ?? "Não informada"}
        />
      </dl>

      <Block
        icon={<Layers className="size-4" aria-hidden="true" />}
        title="Tecnologias"
        count={known.length}
      >
        {known.length > 0 ? (
          <>
            <div className="flex flex-wrap gap-1.5">
              {known.map((skill) => (
                <SkillBadge key={skill.slug ?? skill.name} skill={skill} />
              ))}
            </div>
            {unknown.length > 0 && (
              <p className="mt-3 text-xs text-slate-500">
                Também identificamos, fora do catálogo:{" "}
                {unknown.map((skill) => skill.name).join(", ")}.
              </p>
            )}
          </>
        ) : (
          <p className="text-sm text-slate-500">
            Nenhuma tecnologia do catálogo foi encontrada no texto. Sem isso, a
            compatibilidade não é calculada.
          </p>
        )}
      </Block>

      <ListBlock
        icon={<Briefcase className="size-4" aria-hidden="true" />}
        title="Experiência"
        items={resume.experiences}
      />
      <ListBlock
        icon={<GraduationCap className="size-4" aria-hidden="true" />}
        title="Formação"
        items={resume.education}
      />
      <ListBlock
        icon={<Award className="size-4" aria-hidden="true" />}
        title="Certificações"
        items={resume.certifications}
      />
      <ListBlock
        icon={<FolderGit2 className="size-4" aria-hidden="true" />}
        title="Projetos"
        items={resume.projects}
      />

      <section className="rounded-card border border-brand-100 bg-brand-50/50 p-4">
        <h3 className="flex items-center gap-2 text-sm font-semibold text-brand-900">
          <Info className="size-4" aria-hidden="true" />
          Como esse perfil é usado
        </h3>
        <p className="mt-2 text-sm leading-relaxed text-brand-900/80">
          Cada vaga é comparada com as tecnologias acima, com o seu nível e com a sua
          preferência de modalidade. O resultado é a compatibilidade exibida na busca, junto
          com o que combina e o que falta para combinar mais.
        </p>
      </section>
    </div>
  );
}

/** Quanto mais o currículo cita a tecnologia, mais destaque ela recebe. */
function SkillBadge({ skill }: { skill: ResumeSkill }) {
  return (
    <span title={`Citada ${skill.occurrences}x no currículo`}>
      <TechnologyBadge name={skill.name} emphasis={skill.occurrences > 1} />
    </span>
  );
}

function Field({
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
      <dd className="mt-1 text-sm font-medium text-slate-800">{value}</dd>
    </div>
  );
}

function Block({
  icon,
  title,
  count,
  children,
}: {
  icon: React.ReactNode;
  title: string;
  count?: number;
  children: React.ReactNode;
}) {
  return (
    <section>
      <h3 className="mb-2.5 flex items-center gap-2 text-sm font-semibold text-slate-900">
        <span className="text-slate-500">{icon}</span>
        {title}
        {count != null && count > 0 && (
          <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-500">
            {count}
          </span>
        )}
      </h3>
      {children}
    </section>
  );
}

/** Seção que simplesmente não aparece quando o currículo não trouxe o dado. */
function ListBlock({
  icon,
  title,
  items,
}: {
  icon: React.ReactNode;
  title: string;
  items: string[];
}) {
  if (items.length === 0) return null;
  return (
    <Block icon={icon} title={title}>
      <ul className="space-y-2">
        {items.map((item, index) => (
          <li key={index} className="flex gap-2.5 text-sm leading-relaxed text-slate-700">
            <span
              aria-hidden="true"
              className="mt-2 size-1 shrink-0 rounded-full bg-brand-400"
            />
            <span>{item}</span>
          </li>
        ))}
      </ul>
    </Block>
  );
}
