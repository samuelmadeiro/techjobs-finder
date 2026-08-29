import { useCallback, useEffect, useState } from "react";
import { Header, type Route } from "./components/Header";
import { ToastProvider } from "./components/Toast";
import { ProfilePage } from "./pages/ProfilePage";
import { SearchPage } from "./pages/SearchPage";
import { useCatalog } from "./hooks/useCatalog";
import { useResume } from "./hooks/useResume";

function routeFromPath(pathname: string): Route {
  return pathname.startsWith("/perfil") ? "profile" : "search";
}

/**
 * Duas telas e nada mais: um roteador de biblioteca aqui seria peso sem benefício.
 * O nginx e o Vite já devolvem index.html para qualquer caminho, então o History API
 * basta para /perfil funcionar até em recarga direta.
 */
export default function App() {
  const [route, setRoute] = useState<Route>(() => routeFromPath(window.location.pathname));
  const { resume, loading, setResume, clear } = useResume();
  const { languages, technologies, sources, countries } = useCatalog();

  useEffect(() => {
    const onPopState = () => setRoute(routeFromPath(window.location.pathname));
    window.addEventListener("popstate", onPopState);
    return () => window.removeEventListener("popstate", onPopState);
  }, []);

  const navigate = useCallback((next: Route) => {
    window.history.pushState({}, "", next === "profile" ? "/perfil" : "/");
    setRoute(next);
    window.scrollTo({ top: 0 });
  }, []);

  return (
    <ToastProvider>
      <div className="flex min-h-full flex-col">
        <Header route={route} resume={resume} onNavigate={navigate} />

        <main className="flex-1">
          {route === "search" ? (
            <SearchPage
              resume={resume}
              languages={languages}
              technologies={technologies}
              sources={sources}
              countries={countries}
              onResumeUploaded={setResume}
              onResumeRemoved={clear}
              onViewProfile={() => navigate("profile")}
            />
          ) : (
            <ProfilePage
              resume={resume}
              loading={loading}
              onResumeUploaded={setResume}
              onResumeRemoved={clear}
              onBackToSearch={() => navigate("search")}
            />
          )}
        </main>

        <footer className="border-t border-slate-200 bg-white">
          <div className="mx-auto max-w-6xl px-4 py-6 text-center text-xs leading-relaxed text-slate-500 sm:px-6">
            Os links levam sempre ao anúncio original na fonte. O currículo enviado é usado
            apenas para calcular a compatibilidade.
          </div>
        </footer>
      </div>
    </ToastProvider>
  );
}
