import { useRef, useState } from "react";
import {
  AlertTriangle,
  CheckCircle2,
  FileText,
  RefreshCw,
  Trash2,
  Upload,
  UserRound,
} from "lucide-react";
import { api, describeError } from "../api/client";
import type { Resume } from "../api/types";
import { useToast } from "./Toast";
import { formatBytes, formatDate } from "../lib/labels";

interface Props {
  resume: Resume | null;
  onUploaded: (resume: Resume) => void;
  onRemoved: () => void;
  /** Leva para o perfil; ausente quando o componente já está dentro dele. */
  onViewProfile?: () => void;
}

const MAX_BYTES = 5 * 1024 * 1024;

/**
 * Envio do currículo com arrastar-e-soltar e progresso real.
 *
 * A validação de tamanho e extensão acontece aqui só para dar resposta imediata; a que
 * vale é a do servidor, que também confere o conteúdo do arquivo.
 */
export function ResumeUploader({ resume, onUploaded, onRemoved, onViewProfile }: Props) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [progress, setProgress] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  const toast = useToast();

  async function send(file: File) {
    setError(null);

    if (file.size > MAX_BYTES) {
      const message = `Arquivo de ${formatBytes(file.size)}. O limite é 5 MB.`;
      setError(message);
      toast.error(message);
      return;
    }
    if (!/\.(pdf|docx)$/i.test(file.name)) {
      const message = "Formato não aceito. Envie um PDF ou DOCX.";
      setError(message);
      toast.error(message);
      return;
    }

    setProgress(0);
    try {
      const uploaded = await api.uploadResume(file, setProgress);
      onUploaded(uploaded.resume);
      if (uploaded.resume.parseStatus === "PARSED") {
        toast.success("Currículo processado com sucesso.");
      } else {
        toast.warning(
          uploaded.resume.parseMessage ?? "Currículo salvo, mas não foi possível ler o conteúdo.",
        );
      }
    } catch (cause) {
      // describeError acrescenta o detalhe por campo quando a API mandou um.
      const message = describeError(cause, "Falha ao enviar o currículo.");
      setError(message);
      toast.error(message);
    } finally {
      setProgress(null);
      if (inputRef.current) inputRef.current.value = "";
    }
  }

  async function remove() {
    if (!resume) return;
    setError(null);
    try {
      await api.deleteResume(resume.id);
      onRemoved();
      toast.info("Currículo removido.");
    } catch (cause) {
      const message = describeError(cause, "Falha ao excluir o currículo.");
      setError(message);
      toast.error(message);
    }
  }

  const uploading = progress !== null;
  const processed = resume?.parseStatus === "PARSED";

  return (
    <section className="rounded-card border border-slate-200 bg-white p-5 shadow-card">
      <div className="flex items-start gap-3">
        <span
          className="flex size-9 shrink-0 items-center justify-center rounded-control bg-brand-50 text-brand-600"
          aria-hidden="true"
        >
          <UserRound className="size-5" />
        </span>
        <div>
          <h2 className="text-sm font-semibold text-slate-900">Seu perfil profissional</h2>
          <p className="mt-0.5 text-xs leading-relaxed text-slate-500">
            Envie seu currículo para encontrar vagas mais compatíveis com você.
          </p>
        </div>
      </div>

      {resume ? (
        <div className="mt-4">
          <div className="flex items-start gap-3 rounded-control border border-slate-200 bg-slate-50 p-3">
            <span
              className={`mt-0.5 shrink-0 ${processed ? "text-emerald-600" : "text-amber-600"}`}
              aria-hidden="true"
            >
              {processed ? (
                <CheckCircle2 className="size-5" />
              ) : (
                <AlertTriangle className="size-5" />
              )}
            </span>
            <div className="min-w-0 flex-1">
              <p className="text-sm font-medium text-slate-800">
                {processed ? "Currículo processado" : "Currículo enviado"}
              </p>
              <p className="mt-0.5 truncate text-xs text-slate-500" title={resume.filename}>
                {resume.filename} · {formatBytes(resume.sizeBytes)}
              </p>
              <p className="text-xs text-slate-500">
                {formatDate(resume.uploadedAt) ?? "data desconhecida"}
              </p>
            </div>
          </div>

          {!processed && resume.parseMessage && (
            <p className="mt-2 rounded-control bg-amber-50 p-2.5 text-xs leading-relaxed text-amber-800">
              {resume.parseMessage}
            </p>
          )}

          <div className="mt-3 flex flex-wrap gap-2">
            {onViewProfile && (
              <button
                type="button"
                onClick={onViewProfile}
                className="flex-1 rounded-control bg-brand-600 px-4 py-2 text-sm font-medium text-white transition hover:bg-brand-700"
              >
                Ver perfil
              </button>
            )}
            <button
              type="button"
              onClick={() => inputRef.current?.click()}
              disabled={uploading}
              className="inline-flex flex-1 items-center justify-center gap-1.5 rounded-control border border-slate-200 px-4 py-2 text-sm font-medium text-slate-700 transition hover:bg-slate-50 disabled:opacity-60"
            >
              <RefreshCw className="size-4" aria-hidden="true" />
              Substituir
            </button>
            <button
              type="button"
              onClick={remove}
              disabled={uploading}
              aria-label="Remover currículo"
              className="inline-flex items-center justify-center gap-1.5 rounded-control border border-slate-200 px-3 py-2 text-sm font-medium text-rose-600 transition hover:border-rose-200 hover:bg-rose-50 disabled:opacity-60"
            >
              <Trash2 className="size-4" aria-hidden="true" />
            </button>
          </div>
        </div>
      ) : (
        <button
          type="button"
          onDragOver={(event) => {
            event.preventDefault();
            setDragging(true);
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={(event) => {
            event.preventDefault();
            setDragging(false);
            const file = event.dataTransfer.files[0];
            if (file) void send(file);
          }}
          onClick={() => inputRef.current?.click()}
          disabled={uploading}
          className={`mt-4 w-full rounded-card border-2 border-dashed p-6 text-center transition ${
            dragging
              ? "border-brand-500 bg-brand-50"
              : "border-slate-300 hover:border-brand-400 hover:bg-slate-50"
          }`}
        >
          <span
            className="mx-auto flex size-11 items-center justify-center rounded-full bg-slate-100 text-slate-500"
            aria-hidden="true"
          >
            <Upload className="size-5" />
          </span>
          <span className="mt-3 block text-sm font-medium text-slate-700">
            Arraste seu currículo aqui
          </span>
          <span className="mt-0.5 block text-xs text-slate-500">ou clique para selecionar</span>
          <span className="mt-2 inline-flex items-center gap-1 rounded-full bg-slate-100 px-2 py-0.5 text-[11px] font-medium text-slate-500">
            <FileText className="size-3" aria-hidden="true" />
            PDF ou DOCX, até 5 MB
          </span>
        </button>
      )}

      {uploading && (
        <div className="mt-3" aria-live="polite">
          <div className="flex justify-between text-xs text-slate-500">
            <span>Enviando currículo...</span>
            <span className="tabular-nums">{progress}%</span>
          </div>
          <div
            className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-slate-100"
            role="progressbar"
            aria-valuenow={progress ?? 0}
            aria-valuemin={0}
            aria-valuemax={100}
          >
            <div
              className="h-full rounded-full bg-brand-500 transition-[width] duration-200"
              style={{ width: `${progress}%` }}
            />
          </div>
        </div>
      )}

      {error && (
        <p role="alert" className="mt-3 rounded-control bg-rose-50 p-2.5 text-xs text-rose-700">
          {error}
        </p>
      )}

      <p className="mt-3 text-[11px] leading-relaxed text-slate-500">
        O arquivo é usado apenas para calcular a compatibilidade e pode ser removido a
        qualquer momento.
      </p>

      <input
        ref={inputRef}
        type="file"
        accept=".pdf,.docx"
        className="hidden"
        aria-label="Selecionar arquivo de currículo"
        // Escondido do leitor de tela e do Tab: quem opera o upload é o botão visível
        // acima, que já anuncia o que faz. Dois controles para a mesma ação confundem.
        aria-hidden="true"
        tabIndex={-1}
        onChange={(event) => {
          const file = event.target.files?.[0];
          if (file) void send(file);
        }}
      />
    </section>
  );
}
