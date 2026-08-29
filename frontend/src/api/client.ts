import type {
  ApiResponse,
  CountryOption,
  FieldIssue,
  JobDetails,
  JobFilters,
  JobSummary,
  PageResponse,
  Resume,
  ResumeUpload,
  SessionInfo,
  SourceInfo,
  TechnologyOption,
} from "./types";

/**
 * Cliente HTTP da API.
 *
 * Caminho relativo por padrão: em produção o nginx faz proxy de `/api`, em
 * desenvolvimento o Vite faz o mesmo. `VITE_API_URL` só é necessário quando o backend
 * está em outro host.
 */
const API_BASE = (import.meta.env.VITE_API_URL ?? "").replace(/\/$/, "");

/**
 * A sessão não é mais guardada aqui.
 *
 * Antes, um token permanente ficava em `localStorage` e ia num cabeçalho. Ele dava acesso
 * ao currículo — nome, localização, histórico — e qualquer script injetado na página podia
 * lê-lo e levá-lo embora. Agora o servidor emite um cookie `HttpOnly`: o JavaScript não
 * enxerga o valor, o navegador o envia sozinho, e ele expira e pode ser revogado.
 *
 * Por isso todas as chamadas usam `credentials: "include"` — sem isso o cookie não viaja
 * quando a API está em outra origem.
 */

/** Depois disso a requisição é abandonada: melhor um erro tratado que uma tela travada. */
const REQUEST_TIMEOUT_MS = 30_000;

/** Upload envia megabytes e ainda espera a extração do texto; prazo próprio, mais folgado. */
const UPLOAD_TIMEOUT_MS = 120_000;

/** Tentativas extras, só em GET e só para falha transitória. */
const RETRY_ATTEMPTS = 2;
const RETRY_BASE_DELAY_MS = 400;

/** Status que costumam mudar sozinhos entre uma tentativa e a seguinte. */
const TRANSIENT_STATUSES = new Set([502, 503, 504]);

/**
 * Erro com mensagem já pronta para exibição — vem do campo `message` do envelope.
 *
 * `errors` traz o detalhe por campo quando a API recusa a entrada, e `status` permite ao
 * chamador distinguir o que exige reação própria: 401 significa sessão ausente ou vencida.
 */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly errors: FieldIssue[] = [],
  ) {
    super(message);
    this.name = "ApiError";
  }

  /** Mensagem do campo, quando a API apontou um. */
  messageFor(field: string): string | undefined {
    return this.errors.find((issue) => issue.field === field)?.message;
  }
}

/**
 * Texto para a tela a partir de qualquer falha.
 *
 * Quando a API aponta campos, eles entram na mensagem: "Dados inválidos" sozinho não diz o
 * que corrigir; com "password: tamanho deve ser entre 10 e 128", diz.
 */
export function describeError(cause: unknown, fallback: string): string {
  if (cause instanceof ApiError) {
    if (cause.errors.length === 0) {
      return cause.message;
    }
    const details = cause.errors
      .map((issue) => `${issue.field}: ${issue.message}`)
      .join("; ");
    return `${cause.message} (${details})`;
  }
  return cause instanceof Error ? cause.message : fallback;
}

async function unwrap<T>(response: Response): Promise<T> {
  let body: ApiResponse<T> | null = null;
  try {
    body = (await response.json()) as ApiResponse<T>;
  } catch {
    body = null;
  }

  if (!response.ok || !body?.success) {
    throw new ApiError(
      body?.message ?? "Não foi possível concluir a operação.",
      response.status,
      body?.errors ?? [],
    );
  }
  return body.data as T;
}

/**
 * Junta o cancelamento de quem chamou com o prazo máximo da requisição.
 *
 * `fetch` não tem timeout: sem isto, um backend que para de responder deixa a tela em
 * "carregando" para sempre, e o usuário não tem como saber que nada mais vai chegar.
 */
function withTimeout(signal?: AbortSignal): AbortSignal {
  const timeout = AbortSignal.timeout(REQUEST_TIMEOUT_MS);
  return signal ? AbortSignal.any([signal, timeout]) : timeout;
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

/**
 * Uma tentativa de GET. Falha de rede e indisponibilidade momentânea do servidor viram
 * `ApiError` com status próprio, para quem chama decidir se repete.
 */
async function getOnce<T>(path: string, callerSignal?: AbortSignal): Promise<T> {
  try {
    const response = await fetch(`${API_BASE}${path}`, {
      headers: { Accept: "application/json" },
      credentials: "include",
      signal: withTimeout(callerSignal),
    });
    return await unwrap<T>(response);
  } catch (cause) {
    // Cancelamento pedido pela aplicação (busca nova substituindo a anterior) não é erro
    // e não pode virar mensagem na tela: sobe como está, e quem chamou já o ignora.
    if (callerSignal?.aborted) {
      throw cause;
    }
    if (cause instanceof ApiError) {
      throw cause;
    }
    if (cause instanceof DOMException && cause.name === "TimeoutError") {
      throw new ApiError(
        "O servidor demorou demais para responder. Tente novamente.",
        408,
      );
    }
    // Só sobra falha de rede: offline, DNS, conexão recusada.
    throw new ApiError("Não foi possível falar com o servidor.", 0);
  }
}

/**
 * GET com repetição.
 *
 * Só GET, porque repetir é seguro apenas em requisição idempotente — reenviar um POST
 * poderia gravar duas vezes. E só para falha transitória: 4xx significa que o pedido está
 * errado e vai continuar errado, repetir só atrasa a mensagem. Timeout também não se
 * repete: outra espera de 30 s é pior para o usuário do que o erro.
 */
async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
  for (let attempt = 0; ; attempt++) {
    try {
      return await getOnce<T>(path, signal);
    } catch (cause) {
      const transient =
        cause instanceof ApiError &&
        (cause.status === 0 || TRANSIENT_STATUSES.has(cause.status));
      if (!transient || attempt >= RETRY_ATTEMPTS || signal?.aborted) {
        throw cause;
      }
      // Espera crescente: se o servidor está se recuperando, insistir no mesmo ritmo atrapalha.
      await delay(RETRY_BASE_DELAY_MS * 2 ** attempt);
    }
  }
}

/** Só entra na query string o que o usuário realmente preencheu. */
/**
 * Filtros viram query string. É a mesma função que monta a URL da barra de endereços
 * (`filtersToQuery` no SearchPage usa este formato), então uma busca compartilhada por link
 * reproduz exatamente a requisição que a aplicação faria.
 */
export function buildQuery(filters: JobFilters, page: number, refresh: boolean): string {
  const params = new URLSearchParams();
  if (filters.language) params.set("language", filters.language);
  if (filters.technology) params.set("technology", filters.technology);
  if (filters.level) params.set("level", filters.level);
  if (filters.workModel) params.set("workModel", filters.workModel);
  if (filters.country) params.set("country", filters.country);
  if (filters.location.trim()) params.set("location", filters.location.trim());
  if (filters.keyword.trim()) params.set("keyword", filters.keyword.trim());
  params.set("sort", filters.sort);
  params.set("page", String(page));
  params.set("size", String(filters.size));
  if (refresh) params.set("refresh", "true");
  return params.toString();
}


/** POST/DELETE com corpo JSON opcional. Sempre com o cookie de sessão junto. */
async function send<T>(path: string, method: "POST" | "DELETE", body?: unknown): Promise<T> {
  try {
    const response = await fetch(`${API_BASE}${path}`, {
      method,
      headers: body === undefined
        ? { Accept: "application/json" }
        : { Accept: "application/json", "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials: "include",
      signal: withTimeout(),
    });
    return await unwrap<T>(response);
  } catch (cause) {
    if (cause instanceof ApiError) throw cause;
    if (cause instanceof DOMException && cause.name === "TimeoutError") {
      throw new ApiError("O servidor demorou demais para responder. Tente novamente.", 408);
    }
    throw new ApiError("Não foi possível falar com o servidor.", 0);
  }
}

export const api = {
  searchJobs(
    filters: JobFilters,
    page: number,
    options: { refresh?: boolean; recommended?: boolean; signal?: AbortSignal } = {},
  ): Promise<PageResponse<JobSummary>> {
    const path = options.recommended ? "/api/jobs/recommended" : "/api/jobs";
    return get(`${path}?${buildQuery(filters, page, options.refresh ?? false)}`, options.signal);
  },


  // ---------------------------------------------------------------- sessão

  /** Quem está autenticado agora. 401 quando não há sessão. */
  session(signal?: AbortSignal): Promise<SessionInfo> {
    return get("/api/auth/me", signal);
  },

  /**
   * Garante uma sessão antes de uma ação que exige identidade.
   *
   * Preguiçosa de propósito: criar sessão na abertura da página encheria a tabela de
   * contas de todo mundo que só passou para olhar vagas. A conta anônima nasce quando a
   * pessoa faz algo que precisa dela — na prática, ao enviar o currículo.
   */
  async ensureSession(): Promise<SessionInfo> {
    try {
      return await api.session();
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 401) {
        return send<SessionInfo>("/api/auth/sessions", "POST");
      }
      throw cause;
    }
  },

  /** Vincula e-mail e senha à conta atual, preservando o currículo já enviado. */
  register(email: string, password: string): Promise<SessionInfo> {
    return send("/api/auth/users", "POST", { email, password });
  },

  login(email: string, password: string): Promise<SessionInfo> {
    return send("/api/auth/sessions", "POST", { email, password });
  },

  logout(): Promise<void> {
    return send("/api/auth/sessions/current", "DELETE");
  },

  jobDetails(id: number, signal?: AbortSignal): Promise<JobDetails> {
    return get(`/api/jobs/${id}`, signal);
  },

  languages(signal?: AbortSignal): Promise<TechnologyOption[]> {
    return get("/api/languages", signal);
  },

  technologies(signal?: AbortSignal): Promise<TechnologyOption[]> {
    return get("/api/technologies", signal);
  },

  sources(signal?: AbortSignal): Promise<SourceInfo[]> {
    return get("/api/sources", signal);
  },

  /** Países aceitos no filtro, com nome e bandeira já prontos para exibir. */
  countries(signal?: AbortSignal): Promise<CountryOption[]> {
    return get("/api/countries", signal);
  },

  currentResume(signal?: AbortSignal): Promise<Resume> {
    return get("/api/resumes/me", signal);
  },

  /** Sem repetição: o DELETE não é repetido porque a segunda chamada responderia 404. */
  deleteResume(id: number): Promise<void> {
    return fetch(`${API_BASE}/api/resumes/${id}`, {
      method: "DELETE",
      headers: { Accept: "application/json" },
      credentials: "include",
      signal: withTimeout(),
    }).then((response) => unwrap<void>(response));
  },

  /**
   * Upload com progresso real. `fetch` não expõe o andamento do envio, e mostrar uma
   * barra falsa em um arquivo de megabytes é pior do que não mostrar nada — por isso
   * XMLHttpRequest aqui e só aqui.
   */
  async uploadResume(file: File, onProgress: (percent: number) => void): Promise<ResumeUpload> {
    // O upload exige sessão: sem conta não há a quem pertencer o currículo.
    await api.ensureSession();
    return new Promise((resolve, reject) => {
      const form = new FormData();
      form.append("file", file);

      const request = new XMLHttpRequest();
      request.open("POST", `${API_BASE}/api/resumes`);
      // Prazo maior que o dos GET: aqui há megabytes subindo por uma conexão que pode ser
      // lenta, e o servidor ainda extrai o texto do arquivo antes de responder.
      request.timeout = UPLOAD_TIMEOUT_MS;
      request.setRequestHeader("Accept", "application/json");
      // Manda o cookie de sessão junto; XHR não faz isso por padrão entre origens.
      request.withCredentials = true;

      request.upload.addEventListener("progress", (event) => {
        if (event.lengthComputable) {
          onProgress(Math.round((event.loaded / event.total) * 100));
        }
      });

      request.addEventListener("load", () => {
        let body: ApiResponse<ResumeUpload> | null = null;
        try {
          body = JSON.parse(request.responseText) as ApiResponse<ResumeUpload>;
        } catch {
          body = null;
        }
        if (request.status >= 200 && request.status < 300 && body?.success && body.data) {
          resolve(body.data);
        } else {
          reject(new ApiError(
            body?.message ?? "Falha ao enviar o currículo.",
            request.status,
            body?.errors ?? [],
          ));
        }
      });

      request.addEventListener("error", () =>
        reject(new ApiError("Falha de rede ao enviar o currículo.", 0)),
      );
      request.addEventListener("timeout", () =>
        reject(new ApiError("O envio demorou demais. Verifique a conexão e tente de novo.", 408)),
      );
      request.addEventListener("abort", () =>
        reject(new ApiError("Envio cancelado.", 0)),
      );

      request.send(form);
    });
  },
};
