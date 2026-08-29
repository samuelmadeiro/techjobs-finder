import { useEffect, useRef, useState } from "react";
import { FileText, Menu, ScanSearch, Search, User, X } from "lucide-react";
import type { Resume } from "../api/types";

export type Route = "search" | "profile";

interface Props {
  route: Route;
  resume: Resume | null;
  onNavigate: (route: Route) => void;
}

export function Header({ route, resume, onNavigate }: Props) {
  const [menuOpen, setMenuOpen] = useState(false);
  const menuButtonRef = useRef<HTMLButtonElement>(null);

  // Trocar de tela fecha o menu; sem isso ele ficaria aberto sobre a página nova.
  useEffect(() => setMenuOpen(false), [route]);

  useEffect(() => {
    if (!menuOpen) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setMenuOpen(false);
        menuButtonRef.current?.focus();
      }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [menuOpen]);

  const resumeReady = resume?.parseStatus === "PARSED";

  return (
    <header className="sticky top-0 z-40 border-b border-slate-200 bg-white/90 backdrop-blur-sm">
      <div className="mx-auto flex h-16 max-w-6xl items-center gap-4 px-4 sm:px-6">
        <button
          type="button"
          onClick={() => onNavigate("search")}
          className="flex items-center gap-2.5 rounded-control text-left"
          aria-label="TechJobs Finder, ir para a busca"
        >
          <span className="flex size-9 items-center justify-center rounded-control bg-brand-600 text-white shadow-subtle">
            <ScanSearch className="size-5" aria-hidden="true" />
          </span>
          <span className="leading-tight">
            <span className="block text-[15px] font-semibold tracking-tight text-slate-900">
              Tech<span className="text-brand-600">Jobs</span>{" "}
              <span className="font-normal text-slate-500">Finder</span>
            </span>
            <span className="hidden text-[11px] text-slate-500 sm:block">
              Vagas de tecnologia compatíveis com você
            </span>
          </span>
        </button>

        {/* Desktop */}
        <nav className="ml-auto hidden items-center gap-1 md:flex" aria-label="Principal">
          <NavItem
            active={route === "search"}
            onClick={() => onNavigate("search")}
            icon={<Search className="size-4" aria-hidden="true" />}
          >
            Buscar vagas
          </NavItem>
          <NavItem
            active={route === "profile"}
            onClick={() => onNavigate("profile")}
            icon={<User className="size-4" aria-hidden="true" />}
          >
            Meu perfil
          </NavItem>
          <ResumeBadge resume={resume} ready={resumeReady} onClick={() => onNavigate("profile")} />
        </nav>

        {/* Mobile */}
        <button
          ref={menuButtonRef}
          type="button"
          onClick={() => setMenuOpen((open) => !open)}
          aria-expanded={menuOpen}
          aria-controls="menu-mobile"
          aria-label={menuOpen ? "Fechar menu" : "Abrir menu"}
          className="ml-auto rounded-control p-2 text-slate-600 transition hover:bg-slate-100 md:hidden"
        >
          {menuOpen ? (
            <X className="size-5" aria-hidden="true" />
          ) : (
            <Menu className="size-5" aria-hidden="true" />
          )}
        </button>
      </div>

      {menuOpen && (
        <nav
          id="menu-mobile"
          aria-label="Principal"
          className="animate-slide-up border-t border-slate-200 bg-white px-4 py-3 md:hidden"
        >
          <ul className="space-y-1">
            <li>
              <MobileItem
                active={route === "search"}
                onClick={() => onNavigate("search")}
                icon={<Search className="size-4" aria-hidden="true" />}
              >
                Buscar vagas
              </MobileItem>
            </li>
            <li>
              <MobileItem
                active={route === "profile"}
                onClick={() => onNavigate("profile")}
                icon={<User className="size-4" aria-hidden="true" />}
              >
                Meu perfil
              </MobileItem>
            </li>
            <li>
              <MobileItem
                active={false}
                onClick={() => onNavigate("profile")}
                icon={<FileText className="size-4" aria-hidden="true" />}
              >
                <span className="flex flex-1 items-center justify-between gap-2">
                  Meu currículo
                  <span
                    className={`rounded-full px-2 py-0.5 text-[11px] font-medium ${
                      resumeReady
                        ? "bg-emerald-50 text-emerald-700"
                        : "bg-slate-100 text-slate-500"
                    }`}
                  >
                    {resumeReady ? "Processado" : "Não enviado"}
                  </span>
                </span>
              </MobileItem>
            </li>
          </ul>
        </nav>
      )}
    </header>
  );
}

function NavItem({
  active,
  onClick,
  icon,
  children,
}: {
  active: boolean;
  onClick: () => void;
  icon: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-current={active ? "page" : undefined}
      className={`relative flex items-center gap-2 rounded-control px-3 py-2 text-sm font-medium transition ${
        active
          ? "bg-brand-50 text-brand-700"
          : "text-slate-600 hover:bg-slate-100 hover:text-slate-900"
      }`}
    >
      {icon}
      {children}
    </button>
  );
}

function MobileItem({
  active,
  onClick,
  icon,
  children,
}: {
  active: boolean;
  onClick: () => void;
  icon: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-current={active ? "page" : undefined}
      className={`flex w-full items-center gap-3 rounded-control px-3 py-3 text-left text-sm font-medium transition ${
        active ? "bg-brand-50 text-brand-700" : "text-slate-700 hover:bg-slate-100"
      }`}
    >
      {icon}
      {children}
    </button>
  );
}

/** Atalho para o currículo com o estado atual visível sem precisar abrir o perfil. */
function ResumeBadge({
  resume,
  ready,
  onClick,
}: {
  resume: Resume | null;
  ready: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      title={resume ? resume.filename : "Nenhum currículo enviado"}
      className={`ml-2 flex items-center gap-2 rounded-control border px-3 py-2 text-sm font-medium transition ${
        ready
          ? "border-emerald-200 bg-emerald-50 text-emerald-700 hover:bg-emerald-100"
          : "border-slate-200 text-slate-600 hover:bg-slate-50"
      }`}
    >
      <FileText className="size-4" aria-hidden="true" />
      <span className="hidden lg:inline">{ready ? "Currículo ativo" : "Enviar currículo"}</span>
      <span className="lg:hidden">Currículo</span>
      {ready && (
        <span className="size-1.5 rounded-full bg-emerald-500" aria-hidden="true" />
      )}
    </button>
  );
}
