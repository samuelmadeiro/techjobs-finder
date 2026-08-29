import { ArrowLeft, FileText } from "lucide-react";
import { ResumeProfile } from "../components/ResumeProfile";
import { ResumeUploader } from "../components/ResumeUploader";
import type { Resume } from "../api/types";

interface Props {
  resume: Resume | null;
  loading: boolean;
  onResumeUploaded: (resume: Resume) => void;
  onResumeRemoved: () => void;
  onBackToSearch: () => void;
}

export function ProfilePage({
  resume,
  loading,
  onResumeUploaded,
  onResumeRemoved,
  onBackToSearch,
}: Props) {
  return (
    <div className="mx-auto max-w-6xl px-4 py-6 sm:px-6 sm:py-8">
      <button
        type="button"
        onClick={onBackToSearch}
        className="mb-5 inline-flex items-center gap-1.5 rounded-control text-sm font-medium text-slate-500 transition hover:text-slate-800"
      >
        <ArrowLeft className="size-4" aria-hidden="true" />
        Voltar para a busca
      </button>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-[20rem_1fr]">
        <ResumeUploader
          resume={resume}
          onUploaded={onResumeUploaded}
          onRemoved={onResumeRemoved}
        />

        <section className="rounded-card border border-slate-200 bg-white p-5 shadow-card sm:p-6">
          {loading && <ProfileSkeleton />}

          {!loading && !resume && (
            <div className="py-14 text-center">
              <span
                className="mx-auto flex size-12 items-center justify-center rounded-full bg-slate-100 text-slate-500"
                aria-hidden="true"
              >
                <FileText className="size-6" />
              </span>
              <h2 className="mt-4 text-base font-semibold text-slate-800">
                Nenhum currículo enviado ainda.
              </h2>
              <p className="mx-auto mt-1.5 max-w-sm text-sm leading-relaxed text-slate-500">
                Envie um PDF ou DOCX ao lado para o sistema entender o seu perfil e ordenar
                as vagas por compatibilidade.
              </p>
            </div>
          )}

          {!loading && resume && <ResumeProfile resume={resume} />}
        </section>
      </div>
    </div>
  );
}

function ProfileSkeleton() {
  return (
    <div className="animate-pulse space-y-5">
      <div className="space-y-2 border-b border-slate-100 pb-5">
        <div className="h-5 w-1/3 rounded bg-slate-200" />
        <div className="h-3 w-1/2 rounded bg-slate-100" />
      </div>
      <div className="h-20 rounded-card bg-slate-100" />
      <div className="flex gap-1.5">
        <div className="h-6 w-16 rounded-md bg-slate-100" />
        <div className="h-6 w-20 rounded-md bg-slate-100" />
        <div className="h-6 w-14 rounded-md bg-slate-100" />
      </div>
      <div className="space-y-2">
        <div className="h-3 w-full rounded bg-slate-100" />
        <div className="h-3 w-4/5 rounded bg-slate-100" />
      </div>
    </div>
  );
}
