/**
 * Espelho dos DTOs do backend. Um campo só existe aqui se existir lá — quando o
 * contrato muda, o TypeScript aponta os pontos de uso em vez de a tela quebrar em
 * produção com `undefined`.
 *
 * Fonte: com.techjobs.finder.dto.*
 */

export type WorkModel = "REMOTE" | "HYBRID" | "ONSITE" | "UNKNOWN";

export type ExperienceLevel =
  | "INTERNSHIP"
  | "TRAINEE"
  | "JUNIOR"
  | "MID"
  | "SENIOR"
  | "UNKNOWN";

export type TechnologyKind = "LANGUAGE" | "FRAMEWORK" | "DATABASE" | "CLOUD" | "TOOL";

export type SalaryPeriod = "HOUR" | "MONTH" | "YEAR";

export type ParseStatus = "PENDING" | "PARSED" | "EMPTY" | "FAILED";

export type RecommendationLevel = "HIGH" | "MEDIUM" | "LOW";

/** Campo específico que a API recusou, com o motivo já em texto exibível. */
export interface FieldIssue {
  field: string;
  message: string;
}

/** Envelope de toda resposta da API. */
export interface ApiResponse<T> {
  success: boolean;
  data: T | null;
  message: string | null;
  /** Presente só em erro de validação; ausente no resto. */
  errors?: FieldIssue[];
  timestamp: string;
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
  meta?: SearchMeta;
}

export interface SearchMeta {
  fromCache: boolean;
  collectedAt?: string;
  sourcesQueried: string[];
  failures: { source: string; message: string }[];
  truncated: boolean;
  /** Há coleta enfileirada para estes filtros: o que está na tela vale, e vem coisa nova. */
  refreshing: boolean;
}

export interface CompanySummary {
  id?: number;
  name: string;
  website?: string;
  logoUrl?: string;
  description?: string;
}

export interface JobSourceSummary {
  code: string;
  name: string;
  url?: string;
}

export interface Salary {
  min?: number;
  max?: number;
  currency?: string;
  period?: SalaryPeriod;
  raw?: string;
}

export interface CompatibilityReason {
  criterion: string;
  positive: boolean;
  text: string;
}

export interface CompatibilityResult {
  jobId: number;
  score: number;
  matchedSkills: string[];
  missingSkills: string[];
  extraSkills: string[];
  experienceMatch: boolean;
  workModelMatch: boolean;
  locationMatch: boolean;
  recommendation: RecommendationLevel;
  reasons: CompatibilityReason[];
}

export interface JobSummary {
  id: number;
  title: string;
  company?: CompanySummary;
  location?: string;
  workModel: WorkModel;
  experienceLevel: ExperienceLevel;
  experienceYears?: number;
  languages: string[];
  technologies: string[];
  shortDescription?: string;
  salary?: Salary;
  publishedAt?: string;
  source?: JobSourceSummary;
  originalUrl: string;
  relevance?: number;
  compatibility?: CompatibilityResult;
}

export interface JobDetails {
  id: number;
  title: string;
  company?: CompanySummary;
  location?: string;
  country?: string;
  workModel: WorkModel;
  experienceLevel: ExperienceLevel;
  experienceYears?: number;
  shortDescription?: string;
  description?: string;
  requirements: string[];
  niceToHave: string[];
  languages: string[];
  technologies: string[];
  benefits?: string;
  salary?: Salary;
  publishedAt?: string;
  updatedAt?: string;
  expirationDate?: string;
  active: boolean;
  source?: JobSourceSummary;
  originalUrl: string;
  compatibility?: CompatibilityResult;
}

export interface ResumeSkill {
  slug?: string;
  name: string;
  kind?: TechnologyKind;
  occurrences: number;
  known: boolean;
}

export interface Resume {
  id: number;
  filename: string;
  sizeBytes: number;
  contentType: string;
  uploadedAt: string;
  parseStatus: ParseStatus;
  parseMessage?: string;
  candidateName?: string;
  headline?: string;
  experienceLevel: ExperienceLevel;
  experienceYears?: number;
  preferredWorkModel?: WorkModel;
  location?: string;
  skills: ResumeSkill[];
  experiences: string[];
  education: string[];
  certifications: string[];
  projects: string[];
}

export interface ResumeUpload {
  resume: Resume;
}

/**
 * Sessão atual. Não traz token: ele vive no cookie `HttpOnly` e o JavaScript não o vê.
 * `anonymous` diz se a conta ainda não tem e-mail e senha.
 */
export interface SessionInfo {
  userId: number;
  anonymous: boolean;
  email?: string;
}

export interface TechnologyOption {
  slug: string;
  name: string;
  kind: TechnologyKind;
  jobCount: number;
}

/**
 * País aceito no filtro. Nome e bandeira vêm do backend de propósito: manter a lista aqui
 * significaria atualizar dois lugares toda vez que um país entrasse ou saísse.
 */
export interface CountryOption {
  code: string;
  name: string;
  flag: string;
  jobCount: number;
}

export interface SourceInfo {
  code: string;
  name: string;
  baseUrl: string;
  enabled: boolean;
  lastRunAt?: string;
  lastStatus?: string;
  lastError?: string;
}

/** Filtros da busca, iguais aos parâmetros aceitos por GET /api/jobs. */
export interface JobFilters {
  language: string;
  technology: string;
  level: string;
  workModel: string;
  /** Código ISO-3166 alpha-2 ("BR"), nunca o nome do país. Vazio = todos. */
  country: string;
  location: string;
  keyword: string;
  sort: "relevance" | "date" | "company";
  /** Quantas vagas a resposta traz. É o `size` da API — não existe um segundo conceito. */
  size: number;
}

/** As opções de quantidade oferecidas na interface. O teto (100) é o mesmo do backend. */
export const SIZE_OPTIONS = [10, 20, 50, 100] as const;

export const EMPTY_FILTERS: JobFilters = {
  language: "",
  technology: "",
  level: "",
  workModel: "",
  country: "",
  location: "",
  keyword: "",
  sort: "relevance",
  size: 20,
};
