import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { AlertCircle, AlertTriangle, CheckCircle2, Info, X } from "lucide-react";

export type ToastKind = "success" | "error" | "warning" | "info";

interface Toast {
  id: number;
  kind: ToastKind;
  message: string;
}

interface ToastApi {
  success: (message: string) => void;
  error: (message: string) => void;
  warning: (message: string) => void;
  info: (message: string) => void;
}

const ToastContext = createContext<ToastApi | null>(null);

const DURATION: Record<ToastKind, number> = {
  // Erro fica mais tempo: é o único que o usuário talvez precise reler.
  success: 4000,
  info: 4000,
  warning: 6000,
  error: 7000,
};

const STYLE: Record<ToastKind, { ring: string; icon: string; Icon: typeof CheckCircle2 }> = {
  success: { ring: "ring-emerald-200", icon: "text-emerald-600", Icon: CheckCircle2 },
  error: { ring: "ring-rose-200", icon: "text-rose-600", Icon: AlertCircle },
  warning: { ring: "ring-amber-200", icon: "text-amber-600", Icon: AlertTriangle },
  info: { ring: "ring-brand-200", icon: "text-brand-600", Icon: Info },
};

/**
 * Notificações da aplicação.
 *
 * <p>Os temporizadores ficam em um ref e são cancelados no unmount: sem isso, sair da
 * página com um toast na tela deixaria um `setTimeout` tentando atualizar estado de um
 * componente que não existe mais.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const timers = useRef(new Map<number, number>());
  const nextId = useRef(1);

  const dismiss = useCallback((id: number) => {
    setToasts((current) => current.filter((toast) => toast.id !== id));
    const timer = timers.current.get(id);
    if (timer) {
      window.clearTimeout(timer);
      timers.current.delete(id);
    }
  }, []);

  const push = useCallback(
    (kind: ToastKind, message: string) => {
      const id = nextId.current++;
      setToasts((current) => [...current, { id, kind, message }]);
      timers.current.set(id, window.setTimeout(() => dismiss(id), DURATION[kind]));
    },
    [dismiss],
  );

  useEffect(() => {
    const pending = timers.current;
    return () => {
      pending.forEach((timer) => window.clearTimeout(timer));
      pending.clear();
    };
  }, []);

  const api = useMemo<ToastApi>(
    () => ({
      success: (message) => push("success", message),
      error: (message) => push("error", message),
      warning: (message) => push("warning", message),
      info: (message) => push("info", message),
    }),
    [push],
  );

  return (
    <ToastContext.Provider value={api}>
      {children}

      {/* No mobile as notificações vêm de baixo, longe do polegar que rola a lista. */}
      <div
        aria-live="polite"
        aria-atomic="false"
        className="pointer-events-none fixed inset-x-3 bottom-3 z-[60] flex flex-col items-center gap-2 sm:inset-x-auto sm:right-5 sm:bottom-5 sm:items-end"
      >
        {toasts.map((toast) => {
          const { ring, icon, Icon } = STYLE[toast.kind];
          return (
            <div
              key={toast.id}
              role={toast.kind === "error" ? "alert" : "status"}
              className={`pointer-events-auto flex w-full max-w-sm animate-toast-in items-start gap-3 rounded-control bg-white p-3.5 shadow-lifted ring-1 ${ring}`}
            >
              <Icon className={`mt-0.5 size-5 shrink-0 ${icon}`} aria-hidden="true" />
              <p className="flex-1 text-sm text-slate-700">{toast.message}</p>
              <button
                type="button"
                onClick={() => dismiss(toast.id)}
                aria-label="Fechar notificação"
                className="-m-1 rounded p-1 text-slate-500 transition hover:bg-slate-100 hover:text-slate-700"
              >
                <X className="size-4" aria-hidden="true" />
              </button>
            </div>
          );
        })}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastApi {
  const api = useContext(ToastContext);
  if (!api) {
    throw new Error("useToast precisa estar dentro de <ToastProvider>");
  }
  return api;
}
