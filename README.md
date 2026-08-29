# TechJobs Finder

Plataforma de busca e recomendação de vagas de tecnologia. O usuário escolhe filtros
(linguagem, tecnologia, nível, modalidade, localização, palavra-chave) e o backend consulta
várias fontes, normaliza os dados para um formato único, remove duplicatas, pontua por
relevância e devolve o resultado paginado.

Enviando o currículo em PDF ou DOCX, o sistema extrai o perfil profissional, compara com cada
vaga, calcula um nível de compatibilidade de 0 a 100 e **explica** o que combinou e o que
faltou — em vez de mostrar só um número.

Os dados são **reais**, coletados das fontes em tempo de execução. Não há dados fictícios em
lugar nenhum do fluxo — apenas em fixtures de teste.

---

## Sumário

- [Funcionalidades](#funcionalidades)
- [Tecnologias](#tecnologias)
- [Arquitetura](#arquitetura)
- [Como executar](#como-executar)
- [Configuração de ambiente](#configuração-de-ambiente)
- [Endpoints](#endpoints)
- [Filtro por país e quantidade](#filtro-por-país-e-quantidade)
- [Exemplos de busca](#exemplos-de-busca)
- [Como funciona a relevância](#como-funciona-a-relevância)
- [Currículo e compatibilidade](#currículo-e-compatibilidade)
- [Banco de dados](#banco-de-dados)
- [Deduplicação](#deduplicação)
- [Cache e atualização automática](#cache-e-atualização-automática)
- [Cache distribuído com Redis](#cache-distribuído-com-redis)
- [Como adicionar um novo scraper](#como-adicionar-um-novo-scraper)
- [Testes](#testes)
- [Limitações conhecidas](#limitações-conhecidas)
- [Cuidados legais no scraping](#cuidados-legais-no-scraping)

---

## Funcionalidades

- Busca combinando **país**, **quantidade de vagas**, linguagem, tecnologia, nível,
  modalidade, localização e palavra-chave.
- Coleta paralela em **5 fontes**, com falha isolada por fonte e paginação profunda.
- Varredura agendada que mantém **milhares de vagas** na base (~3.700 na primeira execução).
- Normalização: modalidade, nível, tecnologias, datas e **faixa salarial numérica** em
  formato único.
- **Extração de requisitos e diferenciais** da descrição, a partir da estrutura do anúncio.
- **Inferência de nível por múltiplos sinais**: título, campo da fonte, anos de experiência
  exigidos e expressões típicas do anúncio.
- Deduplicação entre fontes (mesma vaga publicada em dois sites vira um registro).
- Pontuação de relevância de 0 a 100 e ordenação por relevância, data ou empresa.
- **Filtro por país** em código ISO-3166 (`country=BR`), com catálogo servido pela API e
  classificação do país de cada vaga feita na ingestão.
- Cache por combinação de filtros — país incluído —, com TTL configurável.
- Scheduler de atualização periódica e expiração de vagas antigas.
- Respeito a `robots.txt`, rate limiting por host, timeout e retry com backoff.
- **Upload de currículo** em PDF ou DOCX, com validação de tamanho, extensão e conteúdo.
- **Análise do currículo** por seções: nome, cargo, nível, tecnologias, formação,
  certificações e projetos.
- **Compatibilidade currículo × vaga** de 0 a 100, com pesos configuráveis e explicação
  item a item do que somou e do que faltou.
- **Recomendações personalizadas**: as vagas mais aderentes ao currículo aparecem primeiro.
- Interface React + TypeScript + Tailwind com design system próprio: busca em destaque,
  filtros avançados em painel com chips removíveis, cards com compatibilidade, drawer de
  detalhes com foco preso e Esc, skeleton loading, paginação numerada, toasts, navegação
  mobile e dashboard do candidato.
- Envelope JSON único (`success` / `data` / `message` / `timestamp`) em toda a API.
- Tratamento global de exceções com resposta padronizada.
- Documentação interativa via Swagger UI.

---

## Tecnologias

**Backend:** Java 21, Spring Boot 3.5 (Web, Data JPA, Validation, Cache, Data Redis, Actuator),
PostgreSQL 16, Redis 7,
Flyway, Caffeine, Jackson, Jsoup, Apache PDFBox, Apache POI, Apache Tika,
`java.net.http.HttpClient`, springdoc-openapi, Maven.

**Frontend:** React 19, TypeScript, Tailwind CSS 4, Vite, Lucide (ícones). Build estático
servido por nginx.

**Testes:** JUnit 5, AssertJ, Mockito, MockMvc, Testcontainers.

**Infra:** Docker, Docker Compose e Redis (cache distribuído, opcional).

---

## Arquitetura

```text
frontend React + Tailwind (nginx)
      │  HTTP / JSON
      ▼
JobController ──► JobSearchService ──► SearchCacheService  (cache fresco? serve do banco)
                        │
                        ├──► ScraperOrchestrator ──► JobScraper (N implementações, em paralelo)
                        │                                 │
                        │                                 └─► HttpFetcher
                        │                                       ├─ RobotsTxtService
                        │                                       └─ HostRateLimiter
                        │
                        ├──► JobIngestionService ──► JobNormalizer ──► TechnologyCatalog
                        │            │                    │
                        │            │                    └─► fingerprint de deduplicação
                        │            └──► DeduplicationService ──► JobRepository (PostgreSQL)
                        │
                        ├──► JobSpecifications (filtros) + RelevanceScorer (ordenação)
                        │
                        └──► RecommendationService ──► ResumeMatchingService
                                     │                        (score + explicação)
                                     └──► ResumeService

ResumeController ──► ResumeService ──► ResumeTextExtractor (PDFBox / POI)
                          │        └──► ResumeParserService (perfil estruturado)
                          └──► ResumeRepository + ResumeContentRepository (PostgreSQL)
```

Fluxo da recomendação:

```text
Currículo (PDF/DOCX)
   ↓ extração de texto
   ↓ análise por seções
Perfil profissional (skills, nível, preferências)
   ↓
Vagas filtradas ──► compatibilidade por vaga ──► ordenação por score ──► explicação
```

Pacotes:

```text
com.techjobs.finder
├── config       ScraperProperties, SearchProperties, SchedulerProperties,
│                ResumeProperties, Web/Http/Cache
├── controller   JobController, ResumeController, CatalogController
├── dto          ApiResponse (envelope), PageResponse, FieldIssue, CatalogDtos
│   ├── job              JobSummaryResponse, JobDetailsResponse, CompanySummary,
│   │                    SalaryResponse, JobSourceSummary, JobSearchRequest,
│   │                    JobSearchFilter, SearchMeta
│   ├── resume           ResumeResponse, ResumeSkillResponse, ResumeUploadResponse
│   └── recommendation   CompatibilityResult
├── entity       Job, JobRequirement, Company, Technology, JobSource, SearchCacheEntry,
│                AppUser, Resume, ResumeContent, ResumeSkill, ResumeItem,
│                JobApplication, enums
├── exception    GlobalExceptionHandler e exceções de domínio
├── mapper       JobMapper, ResumeMapper (entidade → DTO)
├── repository   Repositories + JobSpecifications
├── scheduler    JobRefreshScheduler (refresh e limpeza)
├── scraper      JobScraper, RawJob, ScrapeResult, ScraperOrchestrator
│   ├── http     HttpFetcher, RobotsTxtService, HostRateLimiter
│   └── source   ArbeitnowScraper, JobicyScraper, HimalayasScraper,
│                RemoteOkScraper, WeWorkRemotelyScraper, RemotiveScraper
├── service      JobSearchService, JobIngestionService, JobNormalizer,
│                JobDescriptionParser, SalaryParser, ExperienceLevelDetector,
│                DeduplicationService, RelevanceScorer, TechnologyCatalog,
│                CatalogService, ResumeService, ResumeTextExtractor,
│                ResumeParserService, ResumeMatchingService, RecommendationService
└── util         Text, Slugs
```

Estrutura do frontend:

```text
frontend/src
├── api          client.ts (fetch + envelope), types.ts (espelho dos DTOs)
├── components   Header, SearchHero, SearchBar, FilterPanel, FilterChips,
│                JobCard, JobList, JobDetailsDrawer, CompatibilityScore,
│                TechnologyBadge, CompanyInfo, Pagination, ResumeUploader,
│                ResumeProfile, Toast, States (loading / vazio / erro)
├── hooks        useJobSearch, useResume, useCatalog, useDialog
├── lib          labels.ts (rótulos e formatação)
├── pages        SearchPage, ProfilePage
└── index.css    design system: cores, raios, sombras, curvas e animações
```

O `index.css` concentra os tokens (`@theme` do Tailwind 4). Componente nenhum declara
cor, raio ou sombra própria — trocar o azul da marca é editar uma linha.

Princípios aplicados: scrapers dependem apenas da interface `JobScraper`; o orquestrador não
conhece site nenhum; normalização, deduplicação e relevância são serviços separados e testáveis
isoladamente; entidades JPA nunca são expostas na API; controllers apenas recebem, validam e
delegam; o frontend nunca fala com o banco, só com a API REST.

---

## Como executar

### Docker (recomendado)

```bash
cp .env.example .env
```

Defina `POSTGRES_PASSWORD` no `.env` (obrigatório — o compose falha sem isso). Depois:

```bash
docker compose up --build -d
```

- Frontend: <http://localhost:8081>
- API: <http://localhost:8080/api/jobs>
- Swagger UI: <http://localhost:8080/swagger-ui.html>
- Health: <http://localhost:8080/actuator/health>

Se as portas 8080/8081/5432 estiverem ocupadas, ajuste `BACKEND_PORT`, `FRONTEND_PORT` e
`POSTGRES_PORT` no `.env`.

Para acompanhar a coleta:

```bash
docker compose logs -f backend
```

### Local, sem Docker

Requer JDK 21+, Maven e um PostgreSQL acessível.

```bash
cd backend
DATABASE_URL=jdbc:postgresql://localhost:5432/techjobs DATABASE_USER=techjobs DATABASE_PASSWORD=suasenha mvn spring-boot:run
```

Frontend em modo de desenvolvimento (Node 20+):

```bash
cd frontend
npm install
npm run dev
```

Abre em <http://localhost:5173> e faz proxy de `/api` para `http://localhost:8080` — o mesmo
caminho relativo usado em produção, então o código não muda entre os ambientes.

---

## Configuração de ambiente

Nenhuma credencial fica no código. Tudo vem de variável de ambiente (ver `.env.example`).

| Variável | Padrão | Descrição |
|---|---|---|
| `POSTGRES_PASSWORD` | — | Obrigatória. Senha do banco. |
| `DATABASE_URL` / `DATABASE_USER` / `DATABASE_PASSWORD` | — | Conexão usada pelo backend. |
| `BACKEND_PORT` / `FRONTEND_PORT` / `POSTGRES_PORT` | 8080 / 8081 / 5432 | Portas publicadas. |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:8081` | Origens liberadas no CORS. |
| `SCRAPERS_ENABLED` | `true` | Desliga toda a coleta quando `false`. |
| `SCHEDULER_ENABLED` | `true` | Desliga o refresh periódico. |
| `HARVEST_ENABLED` | `true` | Desliga a varredura profunda e a inicial. |
| `SCRAPER_USER_AGENT` | — | Identificação enviada às fontes. Inclua um contato. |
| `VITE_API_URL` | vazio | URL da API para o frontend. Vazio usa o proxy `/api` do nginx. Embutida no bundle em tempo de build. |
| `RESUME_MAX_FILE_SIZE` | `5MB` | Tamanho máximo do currículo aceito no upload. |
| `RESUME_RETENTION` | `180d` | Prazo de guarda do currículo. A limpeza diária apaga o que passar disso. |
| `RATE_LIMIT_ENABLED` | `true` | Liga o limite de envios de currículo. |
| `RESUME_UPLOAD_BURST` / `RESUME_UPLOAD_PERIOD` | `5` / `10m` | Envios tolerados por cliente e tempo de reposição. |
| `DB_POOL_SIZE` | `10` | Conexões do pool (fixo: mínimo igual ao máximo). |
| `REDIS_HOST` | `redis` | Host do Redis. **Vazio desliga**: a aplicação usa cache em memória. |
| `REDIS_PORT` / `REDIS_PASSWORD` | `6379` / vazio | Porta e senha do Redis. |
| `REDIS_MAX_MEMORY` | `256mb` | Teto de memória do contêiner Redis. |
| `LOG_LEVEL` | `INFO` | Nível de log da aplicação. |

Ajustes finos (TTL de cache, rate limit, timeouts, tamanho de página, cron) ficam em
`backend/src/main/resources/application.yml`, sob a chave `techjobs`.

### Conexão com o banco

O pool (HikariCP) é **fixo em 10 conexões** — `minimum-idle` igual a `maximum-pool-size`,
para não pagar a abertura de conexão justamente durante um pico. O `max_connections`
padrão do Postgres é 100, o que deixa folga para migrations, `psql` e uma segunda
instância da aplicação.

O que está configurado e por quê:

| Ajuste | Valor | Motivo |
|---|---|---|
| `connection-timeout` | 10s | Espera por conexão livre. Erro tratado é melhor que requisição pendurada. |
| `max-lifetime` | 25m | Recicla antes que proxy ou banco derrubem a conexão no meio de uma query. |
| `keepalive-time` | 2m | Detecta conexão morta antes de ela chegar numa requisição de usuário. |
| `leak-detection-threshold` | 30s | Denuncia no log quem segurou conexão demais, com a stack. |
| `initialization-fail-timeout` | 60s | Banco indisponível na subida não vira crashloop. |
| `socketTimeout` (driver) | 60s | Nenhuma query segura uma conexão indefinidamente. |
| `flyway.connect-retries` | 10 × 5s | Tolera o banco subindo mais devagar que a aplicação. |

O `initialization-fail-timeout` e o retry do Flyway atacam um problema concreto: antes,
banco fora do ar na subida reiniciava o contêiner em série, e o log de verdade se perdia
no meio dos reinícios. Agora a aplicação insiste por um minuto e, se realmente não der,
falha uma vez com a causa visível.

Probes: `/actuator/health/liveness` responde só "o processo está vivo" e **não** depende do
banco — derrubar o contêiner porque o Postgres piscou só piora a indisponibilidade.
`/actuator/health/readiness` inclui o banco e é o que o healthcheck do Docker consulta.
As métricas do pool saem em `/actuator/metrics/hikaricp.connections.*`.

---

## Endpoints

| Método | Rota | Descrição |
|---|---|---|
| GET | `/api/jobs` | Busca com filtros combináveis |
| GET | `/api/jobs/search` | **Depreciado.** Alias de `/api/jobs`, mantido por compatibilidade |
| GET | `/api/jobs/{id}` | Detalhe completo, com descrição, requisitos e diferenciais |
| GET | `/api/jobs/recommended` | Vagas ordenadas por compatibilidade com o currículo |
| POST | `/api/resumes` | Envia o currículo (`multipart/form-data`, campo `file`) |
| GET | `/api/resumes/me` | Perfil extraído do currículo mais recente |
| GET | `/api/resumes/{id}` | Perfil de um currículo específico do próprio usuário |
| DELETE | `/api/resumes/{id}` | Exclui o currículo e o arquivo original |
| GET | `/api/languages` | Linguagens com contagem de vagas ativas |
| GET | `/api/technologies` | Frameworks, bancos, cloud e ferramentas |
| GET | `/api/countries` | Países aceitos no filtro, com nome, bandeira e contagem de vagas |
| GET | `/api/companies` | Empresas com vagas ativas |
| GET | `/api/sources` | Fontes cadastradas e status da última coleta |

### Envelope

Toda resposta — sucesso ou erro — usa o mesmo formato:

```json
{
  "success": true,
  "data": { },
  "message": null,
  "timestamp": "2026-08-11T13:00:00Z"
}
```

Em erro, `data` é `null` e `message` traz o texto exibível ao usuário. Quando a recusa é de
validação, `errors` diz exatamente qual campo corrigir:

```json
{
  "success": false,
  "data": null,
  "message": "Parâmetros de busca inválidos.",
  "errors": [
    { "field": "size", "message": "deve ser menor ou igual a 100" }
  ],
  "timestamp": "2026-08-14T13:00:00Z"
}
```

`errors` só aparece quando há algo a relatar — o Jackson omite campo nulo.

| Situação | Status |
|---|---|
| Filtro ou corpo inválido, cabeçalho obrigatório ausente | 400 |
| Recurso inexistente (ou de outro dono) | 404 |
| Arquivo recusado na validação de conteúdo | 422 |
| Arquivo acima do limite do multipart | 413 |
| Envios além do teto por cliente | 429, com `Retry-After` |
| Falha ao consultar uma fonte externa | 502 |
| Qualquer erro não previsto | 500, sem detalhe interno |

`POST /api/resumes` responde **201** com `Location: /api/resumes/{id}`. O endereço só diz
onde o recurso está; buscá-lo continua exigindo o mesmo `X-Resume-Token`.

### Identidade do usuário

Ainda não há login. `POST /api/resumes` devolve um `accessToken` opaco gerado pelo servidor;
o frontend o guarda no `localStorage` e o reenvia no cabeçalho `X-Resume-Token`. O cabeçalho
é **opcional** em `/api/jobs` e `/api/jobs/{id}` (quando presente, a resposta vem com a
compatibilidade calculada) e **obrigatório** em `/api/jobs/recommended` e nas rotas de
currículo.

Parâmetros de `/api/jobs`: `country`, `language`, `technology`, `level`, `workModel`,
`location`, `keyword`, `source`, `page`, `size` (1 a 100), `sort` (`relevance`, `date`,
`company`) e `refresh`. `language`, `technology` e `source` aceitam repetição
(`?language=java&language=go`) ou lista separada por vírgula.

`country` é o código ISO-3166 alpha-2 (`BR`, `US`, `PT`…) — nunca o nome do país — e `size` é
a quantidade de vagas que a resposta traz. Os dois estão detalhados em
[Filtro por país e quantidade](#filtro-por-país-e-quantidade).

Resposta de `/api/jobs`:

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 42,
        "title": "Desenvolvedor Java Júnior",
        "company": { "id": 15, "name": "Empresa XYZ", "logoUrl": null },
        "location": "João Pessoa - PB",
        "workModel": "REMOTE",
        "experienceLevel": "JUNIOR",
        "experienceYears": null,
        "languages": ["Java"],
        "technologies": ["Spring Boot", "PostgreSQL"],
        "shortDescription": "Resumo em texto puro…",
        "salary": { "min": 2500, "max": 4000, "currency": "BRL", "period": "MONTH" },
        "publishedAt": "2026-08-10T12:00:00Z",
        "source": { "code": "arbeitnow", "name": "Arbeitnow", "url": "https://www.arbeitnow.com" },
        "originalUrl": "https://…",
        "relevance": 100,
        "compatibility": {
          "jobId": 42,
          "score": 94,
          "matchedSkills": ["Java", "Spring Boot", "PostgreSQL"],
          "missingSkills": ["AWS"],
          "experienceMatch": true,
          "workModelMatch": true,
          "recommendation": "HIGH",
          "reasons": [
            { "criterion": "skill", "positive": true, "text": "Java" },
            { "criterion": "skill", "positive": false, "text": "A vaga pede AWS e o currículo não menciona." }
          ]
        }
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 42,
    "totalPages": 3,
    "last": false,
    "meta": {
      "fromCache": false,
      "collectedAt": "2026-08-10T12:01:00Z",
      "sourcesQueried": ["arbeitnow", "remoteok", "weworkremotely"],
      "failures": [{ "source": "remoteok", "message": "HTTP 503" }],
      "truncated": false,
      "refreshing": true
    }
  },
  "message": null,
  "timestamp": "2026-08-10T12:01:00Z"
}
```

`compatibility` só aparece quando a requisição traz `X-Resume-Token` de um currículo já
analisado. `GET /api/jobs/{id}` devolve os mesmos campos mais `description`, `requirements`,
`niceToHave`, `benefits` e os dados completos da empresa.

`meta.truncated` avisa que a busca bateu no teto de candidatos (`techjobs.search.candidate-limit`):
o total é um piso, não o número exato, e a interface mostra "2000+".

`meta.refreshing` diz que há coleta enfileirada para esses filtros: o que veio na resposta já
serve, e uma versão mais recente está a caminho. A interface usa isso para mostrar
"Atualizando vagas em segundo plano..." ao lado dos resultados, em vez de trocar a tela por um
spinner.

`meta.failures` lista fontes que falharam. A busca continua retornando 200 com o que as demais
fontes entregaram — uma fonte fora do ar não derruba a aplicação.

Erros usam o mesmo envelope:

```json
{
  "success": false,
  "data": [{ "field": "level", "message": "Valor inválido para 'level': 'chefe'." }],
  "message": "Valor inválido para 'level': 'chefe'. valores aceitos: INTERNSHIP, TRAINEE, JUNIOR, MID, SENIOR, ALL",
  "timestamp": "2026-08-10T17:00:00Z"
}
```

---

## Filtro por país e quantidade

### País

O valor que trafega na API é o código **ISO-3166 alpha-2**, não o nome:

```http
GET /api/jobs?country=BR      ✅
GET /api/jobs?country=Brasil  ❌ 400
```

Código porque "Brasil", "Brazil" e "brasil" seriam três buscas distintas para o cache e três
fingerprints diferentes para a mesma intenção. Códigos aceitos hoje:

| Código | País | Código | País | Código | País |
|---|---|---|---|---|---|
| `BR` | 🇧🇷 Brasil | `PT` | 🇵🇹 Portugal | `ES` | 🇪🇸 Espanha |
| `US` | 🇺🇸 Estados Unidos | `GB` | 🇬🇧 Reino Unido | `FR` | 🇫🇷 França |
| `CA` | 🇨🇦 Canadá | `DE` | 🇩🇪 Alemanha | `AU` | 🇦🇺 Austrália |

A lista vive no backend (`CountryCatalog`) e é publicada em `GET /api/countries` com nome e
bandeira já prontos para exibir — o frontend **não** mantém uma cópia. Acrescentar um país é
uma linha nesse catálogo; nada mais muda.

```json
{ "code": "BR", "name": "Brasil", "flag": "🇧🇷", "jobCount": 312 }
```

### O país de uma vaga: `country_code` e o balde `ZZ`

As fontes não mandam código de país. Mandam `"London"`, `"Berlin"`, `"Anywhere"`, `"LATAM"`
ou string vazia. A coluna `job.country` guarda esse texto como veio (serve para exibir); a
coluna **`job.country_code`** (`V11`) guarda o código decidido uma vez, na ingestão, pelo
`CountryCatalog`:

1. texto que já é o código (`"BR"`) → o código;
2. nome do país em qualquer alias conhecido (`"Brazil"`, `"Deutschland"`, `"Reino Unido"`);
3. cidade que identifica o país sozinha (`"London"` → `GB`, `"Berlin"` → `DE`);
4. nada disso → **`ZZ`**.

`ZZ` é "sem país definido": vaga remota global, região multipaís (`"LATAM"`, `"Europe"`) ou
localização não atribuível. **A busca de um país devolve `country_code IN (país, 'ZZ')`** — a
vaga aberta a qualquer lugar continua elegível para quem mora ali. Sem isso, uma busca no
Brasil devolveria quase nada, já que a maior parte do acervo é remoto sem país declarado.

Sigla solta de duas letras não decide país: `us` aparece dentro de "A**us**tin" e `de` dentro
de "Rio **de** Janeiro". Só o texto que é exatamente o código conta como código; o resto vem
de nome de país ou cidade. E o classificador erra sempre para o mesmo lado — o que ele não
reconhece vai para `ZZ` e aparece a mais, nunca some do país certo.

### O que cada fonte suporta

Auditado chamando as APIs, não lendo documentação:

| Fonte | Filtro de país na API? | Como o país é aplicado |
|---|---|---|
| **Jobicy** | **Sim** — parâmetro `geo` | A busca do usuário manda `geo` (`brazil`, `usa`, `canada`, `portugal`, `uk`, `germany`, `spain`, `france`, `australia`) |
| RemoteOK | Não (feed único em `/api`) | Seleção no banco, pelo `country_code` |
| Himalayas | Não (só `limit`/`offset`) | Seleção no banco |
| Arbeitnow | Não (só paginação) | Seleção no banco |
| We Work Remotely | Não (RSS por categoria) | Seleção no banco |

Nenhum suporte foi inventado: fonte sem filtro de país continua contribuindo com o acervo, e
quem seleciona é a consulta. No Jobicy, `united-kingdom` e `india` **não** são aceitos pela
API — `uk` é —, e por isso o slug de cada país é explícito no catálogo em vez de derivado do
nome.

### Quantidade

`size` é a quantidade de vagas na resposta, de **1 a 100** (a interface oferece 10, 20, 50 e
100). Não existe um parâmetro `limit`: seria um segundo nome para a mesma coisa, e `size` já
faz parte da chave da página em cache.

O limite é do **resultado**, não da coleta. Os scrapers seguem com seus próprios tetos
(`techjobs.scraper.max-results-per-source`) e o pipeline não muda:

```text
coleta → normalização → deduplicação → filtros → ranking → corta em size
```

Pedir 50 e existirem 37 vagas válidas devolve **37**, e a interface diz "37 vagas
encontradas". Nada é inventado para completar o número.

Valor acima do teto, zero ou negativo é recusado com 400 no envelope padrão, com `country`
ou `size` no campo `errors[].field`.

### Cache e coleta

País e quantidade fazem parte da identidade da busca, cada um no seu lugar:

```text
fingerprint  = SHA-256(linguagens | tecnologias | nível | modalidade | PAÍS | local | termo | fontes)
chave da página = v1:<fingerprint>:<sort>:<page>:<size>
```

Ou seja: `BR + Java + Júnior + Remoto` e `US + Java + Júnior + Remoto` são coletas separadas,
com entradas separadas em `search_cache_entry`; e `size=20` não reaproveita a página montada
para `size=50`. O fingerprint continua sendo **um só** — o país entrou no canônico existente,
não em uma segunda implementação.

Escolher um país nunca pesquisado **não** faz a busca esperar coleta: a resposta sai do banco
na hora, com `meta.refreshing: true`, e a coleta daquele país vira um `scraping_job` para o
worker. Medido com coleta simulada de 1,5 s, 200 amostras por combinação:

| Cenário | p50 | p95 | p99 | máx |
|---|---|---|---|---|
| `country=BR` cacheado, `size=20` | 7,42 ms | 11,60 ms | 18,53 ms | 19,79 ms |
| `country=US` cacheado, `size=20` | 5,76 ms | 8,44 ms | 9,19 ms | 13,43 ms |
| `country=BR&size=100` | 6,05 ms | 7,74 ms | 10,57 ms | 16,24 ms |
| país inédito, sem cache | 15,82 ms | 22,00 ms | 22,00 ms | 22,00 ms |
| 50 buscas simultâneas, 5 países | 29,54 ms | 159,36 ms | 171,35 ms | 171,35 ms |

### Interface

País e quantidade ficam na barra de busca, e não atrás de "Filtros": são decisões tomadas
antes de buscar ("quero vagas no Brasil, umas vinte"), não refinamentos de um resultado já
visto. Os filtros vão para a URL —
`/?country=BR&sort=date&page=0&size=20` —, então a busca pode ser compartilhada por link,
sobrevive à recarga e responde aos botões voltar/avançar do navegador.

---

## Exemplos de busca

```http
GET /api/jobs?country=BR&size=20&language=java&level=JUNIOR&workModel=REMOTE
GET /api/jobs?country=US&size=50&technology=spring-boot
GET /api/jobs?language=java&level=JUNIOR&workModel=REMOTE
GET /api/jobs?language=python&level=INTERNSHIP&workModel=HYBRID
GET /api/jobs?technology=spring-boot&level=MID
GET /api/jobs?language=java&language=kotlin&technology=docker&sort=date
GET /api/jobs?keyword=backend&location=João Pessoa&size=10&page=1
GET /api/jobs?source=weworkremotely&refresh=true
```

Os rótulos em português também são aceitos: `level=estágio`, `workModel=híbrido`, `level=todos`.

---

## Como funciona a relevância

Pesos: tecnologias 50, nível 25, modalidade 15, texto/localização 10, mais um bônus de até 3
pontos para vagas recentes. Critério não informado pelo usuário não penaliza — seu peso é
redistribuído entre os critérios efetivamente pedidos.

Para o filtro `Java + Spring Boot + Júnior + Remoto`:

| Vaga | Pontuação |
|---|---|
| Java + Spring Boot + Júnior + Remoto | 100% |
| Java + Spring + Júnior + Remoto | ~91% (tecnologia relacionada vale 60%) |
| Java + Spring Boot + Pleno + Remoto | ~88% (nível adjacente vale 50%) |
| Python + Django + Júnior + Remoto | baixa |

---

## Como o nível é deduzido

Poucos anúncios trazem o nível em um campo estruturado, então ele é inferido somando sinais
(`ExperienceLevelDetector`). Cada sinal encontrado pontua um nível; vence o de maior pontuação:

| Sinal | Peso | Exemplo |
|---|---|---|
| Título | 10 | "Desenvolvedor Java **Júnior**" |
| Campo da fonte | 8 | `jobLevel: Senior`, `seniority: Mid-level` |
| Anos de experiência exigidos | 6 | "mínimo de 8 anos", "3 a 5 years", "1+ years" |
| Expressão típica | 4 | "bolsa auxílio", "recém-formado", "liderar o time" |
| Palavra solta na descrição | 2 | "senior" citado no meio do texto |

Faixas de anos: até 2 → Júnior, 3 a 5 → Pleno, 6+ → Sênior. Quando o texto cita vários números
("2 anos de Java, 8 de cloud"), vale o menor — é o piso real de entrada. Em empate, o nível mais
baixo vence, para não esconder vaga de iniciante.

Sem nenhum sinal o nível fica `UNKNOWN` em vez de chutado, e a vaga continua aparecendo com
pontuação parcial. Os anos detectados são persistidos em `job.experience_years` e devolvidos na
API como `experienceYears`.

Na base coletada, cerca de **77% das vagas** ficam classificadas em algum nível.

---

## Currículo e compatibilidade

### Upload

`POST /api/resumes` aceita **PDF** ou **DOCX** de até **5 MB** (`RESUME_MAX_FILE_SIZE`).
O arquivo é tratado como entrada não confiável:

- extensão verificada contra uma lista fixa;
- **tipo detectado pelo conteúdo** (Apache Tika), não pelo cabeçalho enviado pelo cliente —
  um executável renomeado para `.pdf` é recusado. Quando a detecção só consegue dizer "é um
  pacote OOXML" (DOCX, XLSX e PPTX são todos ZIP), a confirmação é feita abrindo o arquivo
  com o POI: um ZIP qualquer com extensão `.docx` não passa, e um DOCX legítimo não é
  reprovado por limitação da detecção por assinatura;
- nome do arquivo higienizado e usado **apenas para exibição**, nunca como caminho;
- binário guardado em `BYTEA`, nunca no disco: não existe caminho interno para vazar e
  nada é servido estaticamente;
- `DELETE /api/resumes/{id}` apaga o binário de fato.

### Análise

O texto é extraído com PDFBox (PDF) ou POI (DOCX) e lido **por seções**, não por palavras
soltas: o parser identifica os títulos usuais ("Experiência", "Formação", "Certificações",
"Projetos") e interpreta cada bloco no seu contexto. É o que evita classificar como formação
uma linha que apenas cita "universidade" dentro de uma experiência.

Sai um perfil estruturado:

```json
{
  "candidateName": "Samuel Borba Madeiro",
  "headline": "Desenvolvedor Backend Java",
  "experienceLevel": "MID",
  "experienceYears": 3,
  "skills": [{ "slug": "java", "name": "Java", "occurrences": 8, "known": true }],
  "education": ["Bacharelado em Ciência da Computação - UFPB"],
  "certifications": ["AWS Cloud Practitioner"],
  "projects": ["Sistema backend com Spring Boot e Docker"]
}
```

PDF digitalizado (só imagem) não falha: o currículo é salvo com `parseStatus: "EMPTY"` e uma
mensagem explicando o que fazer.

### Pontuação

`ResumeMatchingService` compara perfil e vaga com pesos configuráveis em
`techjobs.resume.weights`:

| Critério | Peso padrão |
|---|---|
| Habilidades exigidas pela vaga | 50 |
| Nível de experiência | 20 |
| Modalidade | 10 |
| Localização | 10 |
| Tecnologias relacionadas | 10 |

Regras que valem a pena conhecer:

- **Critério não avaliável não penaliza.** Vaga sem nível declarado simplesmente não entra na
  conta, e o peso é redistribuído — assim um acerto completo nos critérios disponíveis chega
  a 100.
- **Tecnologia relacionada vale 60%.** Quem tem MySQL e a vaga pede PostgreSQL não é tratado
  como se não soubesse banco nenhum.
- **Estar acima do nível pedido não penaliza** em um degrau; dois ou mais degraus descontam
  pouco, para que um sênior não veja vagas júnior à frente de vagas sênior.
- **Sem nada para comparar o score é 50**, não 0 nem 100: "não sei" é a resposta honesta.

Cada ponto ganho ou perdido gera uma linha de explicação, exibida na interface:

```text
Por que essa vaga combina com você?

✓ Java
✓ Spring Boot
✓ PostgreSQL
✓ Nível Júnior compatível
⚠ A vaga pede AWS e o currículo não menciona.

Compatibilidade geral: 94%
```

### Recomendações

`GET /api/jobs/recommended` aplica os mesmos filtros da busca e ordena por compatibilidade
em vez de relevância. Currículo sem nenhuma tecnologia reconhecida não gera recomendação —
comparar sem dado nenhum produziria um ranking arbitrário.

---

## Banco de dados

PostgreSQL, versionado com Flyway (`backend/src/main/resources/db/migration`).

```text
app_user 1───N resume 1───1 resume_content   (arquivo + texto integral, tabela à parte)
              │
              ├──N resume_skill  N───1 technology
              └──N resume_item                (formação, certificação, projeto, experiência)

company 1───N job N───N technology            (via job_technology)
                  │
                  ├──N job_requirement        (requisitos e diferenciais, na ordem do anúncio)
                  └──1 job_source

app_user 1───N job_application N───1 job
```

Três decisões que não são óbvias no diagrama:

- **`resume_content` separada de `resume`**: o perfil é lido a cada busca; o binário de
  megabytes, quase nunca. Separar garante que ler o perfil não arraste o arquivo junto.
- **`job_requirement` como tabela, não texto concatenado**: a ordem dos itens importa e o
  matching consulta cada requisito individualmente.
- **Índices GIN de trigrama em `job`** (`V4`): a busca por palavra-chave gera
  `normalized_title LIKE '%termo%'` e `lower(summary) LIKE '%termo%'`. Padrão que começa com
  `%` não usa B-tree — não há faixa a percorrer —, então a consulta varria a tabela inteira.
  Medido em 50 mil vagas, termo raro: **35,9 ms de varredura sequencial contra 0,06 ms** com
  o índice. Os índices são parciais em `active`, porque toda busca filtra vaga ativa, e o de
  resumo é sobre a expressão `lower(summary)`, para casar com a consulta que o Hibernate
  gera. O B-tree `idx_job_normalized_title` foi removido na mesma migration: era o único
  índice da coluna e não atendia à única consulta que a usa.
- **`job.country_code` separada de `job.country`** (`V11`): a coluna antiga guarda o texto da
  fonte ("Brazil", "Anywhere", às vezes uma cidade) e serve para exibir. Filtrar por ela seria
  `ILIKE '%brasil%'`, que erra "Brazil" e casa "Brasilia" por acidente. `country_code` é o
  código fechado decidido na ingestão, com índice composto `(country_code, active)` — toda
  busca por país também filtra vaga ativa.
- **Índice em `job_application.resume_id`** (`V5`): era a única coluna de chave estrangeira
  sem índice. A FK é `ON DELETE SET NULL`, então cada currículo apagado pela retenção obriga
  o Postgres a procurar as candidaturas que apontavam para ele — sem índice, uma varredura
  da tabela inteira por currículo, multiplicada pelo tamanho do lote de purga. Medido com 50
  mil candidaturas: **2,64 ms de varredura sequencial contra 0,32 ms** com índice.

### Retenção de dados

| Dado | Prazo | O que acontece |
|---|---|---|
| Vaga não vista pelas fontes | 14 dias | Desativada (`active = false`) |
| Vaga desativada | 60 dias | Removida |
| Currículo | 180 dias (`RESUME_RETENTION`) | Registro, arquivo e texto apagados |
| Usuário sem nenhum currículo | 180 dias | Removido junto |

Currículo é dado pessoal — nome, localização, histórico e o arquivo original. Guardá-lo
para sempre enquanto a vaga era purgada em 60 dias invertia a ordem de importância.

---

## Deduplicação

Três critérios, do mais forte para o mais fraco:

1. **Fingerprint**: SHA-256 de empresa + título + localização normalizados. "Empresa XYZ Ltda" e
   "empresa xyz" são a mesma empresa; acentuação e caixa não contam.
2. **URL canônica**: mesma URL sem query string nem barra final.
3. **Similaridade de título**: mesma empresa e similaridade de tokens ≥ 0,85.

A checagem roda no lote coletado e também contra o que já está no banco. Entre duplicatas,
prevalece o registro com mais tecnologias detectadas.

---

## Cache e atualização automática

```text
busca do usuário
     │
     ▼
existe coleta recente para essa combinação de filtros?  (tabela search_cache_entry, TTL 30 min)
     ├── sim ──► responde direto do banco
     └── não ──► executa os scrapers ──► normaliza ──► deduplica ──► persiste ──► responde
```

`?refresh=true` ignora o TTL.

Três rotinas mantêm a base cheia, todas configuráveis em `techjobs.scheduler` e
`techjobs.scraper.harvest`:

| Rotina | Quando | O que faz |
|---|---|---|
| Varredura inicial | Na subida, se houver menos de 300 vagas ativas | Enche a base antes do primeiro acesso |
| Varredura profunda | A cada 4 horas | Pagina cada fonte até o limite (25 páginas / 5.000 vagas por fonte) |
| Refresh | A cada 30 minutos | Coleta rápida do feed recente |
| Limpeza | Diária | Desativa vagas não vistas há 14 dias, remove as inativas há 60 dias |

A varredura profunda usa `JobScraper#harvest()`, separado do `search()` da busca do usuário:
o `search` prioriza responder rápido, o `harvest` prioriza volume. Na primeira execução ela
trouxe **4.220 vagas cruas, 3.673 após deduplicação**, de 1.556 empresas.

Se uma fonte falhar no meio da paginação (429, timeout), o scraper devolve o que já coletou em
vez de descartar as páginas boas.

---

## Cache distribuído com Redis

São três camadas de cache, cada uma resolvendo um problema diferente:

| Camada | Onde vive | O que guarda | TTL |
|---|---|---|---|
| Cache de coleta | `search_cache_entry` (PostgreSQL) | Quando cada combinação de filtros foi coletada | 30 min |
| Catálogo | Redis (ou memória) | Linguagens, tecnologias e empresas com contagem de vagas | 10 min |
| Fontes | Redis (ou memória) | Status da última coleta de cada fonte | 1 min |

O cache de coleta **não** foi para o Redis de propósito: perder a marca de "já consultei
essa combinação" significa martelar as fontes de novo no próximo restart, que é
exatamente o que o rate limiting existe para evitar. Ele é dado, não cache.

O que ganhou com o Redis foram as agregações do catálogo — `GROUP BY` sobre
`job_technology` para contar vagas por tecnologia. Antes ficavam em memória e se perdiam
a cada reinício; agora sobrevivem e são compartilhadas entre instâncias, que passam a
mostrar os mesmos números.

### Degradação

**A aplicação funciona sem Redis.** Sem `REDIS_HOST` ela sobe com cache em memória
(Caffeine) e o log diz qual dos dois está ativo. Se o Redis cair com a aplicação no ar,
um `CacheErrorHandler` registra o aviso e deixa a chamada seguir para o banco — o padrão
do Spring seria propagar a exceção e transformar um cache fora do ar em erro 500 numa
requisição que o banco atenderia sem problema.

Medido com o Redis parado: `GET /api/languages` continua respondendo **HTTP 200**,
pagando o timeout de 1 s na leitura e de novo na gravação.

### Invalidação

O TTL cobre o caso comum. Além dele, a ingestão limpa o catálogo assim que **entra vaga
nova** — atualização de "visto por último" não altera nenhum número exibido, então não
invalida nada. Falha ao limpar o cache é registrada e não desfaz a ingestão.

### Serialização

JSON com informação de tipo, e não serialização Java: o conteúdo fica legível no
`redis-cli`, não exige `Serializable` nos DTOs e não quebra quando um campo é adicionado.
A desserialização é restrita às classes do próprio projeto.

```bash
docker exec techjobs-redis redis-cli KEYS "techjobs*"
docker exec techjobs-redis redis-cli GET "techjobs:catalog::languages"
```

O Redis roda sem persistência (`--save ""`) e com `allkeys-lru`: é cache, e ao encher
deve descartar a chave menos usada em vez de recusar escrita. Não publica porta no host.

---

## Como adicionar um novo scraper

1. Crie a classe em `scraper/source/` implementando `JobScraper` e anotada com `@Component`:

```java
@Component
public class MinhaFonteScraper implements JobScraper {

    private final HttpFetcher fetcher;

    public MinhaFonteScraper(HttpFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String getSource() {
        return "minhafonte";
    }

    @Override
    public String getDisplayName() {
        return "Minha Fonte";
    }

    @Override
    public String getBaseUrl() {
        return "https://minhafonte.com";
    }

    @Override
    public List<RawJob> search(JobSearchFilter filter) {
        String html = fetcher.get(getSource(), getBaseUrl() + "/vagas?q="
                + HttpFetcher.encode(filter.toQueryText()));
        Document document = Jsoup.parse(html, getBaseUrl());
        return document.select(".card-vaga").stream()
                .map(this::toRawJob)
                .filter(RawJob::isUsable)
                .toList();
    }
}
```

2. Cadastre a fonte no banco com uma migration nova em `db/migration`:

```sql
INSERT INTO job_source (code, name, base_url)
VALUES ('minhafonte', 'Minha Fonte', 'https://minhafonte.com');
```

3. Adicione um teste de parsing com fixture em `src/test/resources/fixtures/`. Nenhum teste faz
   requisição real.

Não é preciso alterar o orquestrador, o controller ou o frontend: a descoberta é automática via
injeção de dependência. Use sempre o `HttpFetcher` — é ele que aplica robots.txt, rate limit,
timeout, retry e a validação anti-SSRF.

**Antes de escrever um scraper novo, verifique o `robots.txt` e os termos de uso do site.**

---

## Testes

```bash
cd backend
mvn test                                                       # tudo (integração usa Testcontainers, exige Docker)
mvn test "-Dtest=!JobSearchIntegrationTest,!PostgresIntegrationTest"   # apenas unitários
```

156 testes unitários cobrindo filtros e validação, normalização, deduplicação, relevância,
extração de requisitos da descrição, parsing de salário, análise do currículo, validação do
upload, cálculo de compatibilidade, parsing dos scrapers com fixtures, isolamento de falhas do
orquestrador, regras de `robots.txt` e o envelope de erro. A suíte de integração roda o fluxo
completo contra um PostgreSQL real.

Frontend:

```bash
cd frontend
npm run lint    # tsc --noEmit
npm run build
```

Se o Docker não estiver acessível de dentro do processo de teste, aponte para um Postgres já em
execução com `TEST_DATABASE_URL`, `TEST_DATABASE_USER` e `TEST_DATABASE_PASSWORD`.

Sem Maven instalado, a suíte roda em contêiner:

```bash
docker run --rm -v "$PWD/backend:/build" -w /build maven:3.9-eclipse-temurin-21 mvn -B test "-Dtest=!JobSearchIntegrationTest,!PostgresIntegrationTest"
```

---

## Limitações conhecidas

- **Fontes disponíveis.** O projeto usa apenas fontes que permitem acesso automatizado:
  Arbeitnow, Jobicy, Himalayas, RemoteOK e We Work Remotely (feeds RSS). Sites como LinkedIn,
  Indeed e Gupy proíbem coleta automatizada em seus termos e/ou `robots.txt` e **não** estão
  implementados.
- **Filtro de nível inclui os adjacentes.** Pedir "Estágio" também traz Trainee e vagas com
  nível indefinido — a relevância coloca as realmente compatíveis em primeiro. É proposital:
  descartar `UNKNOWN` esconderia vagas boas apenas por serem mal descritas. Para ver só o nível
  exato, ordene por relevância e observe as de 100%.
- **Arbeitnow limita requisições.** Devolve HTTP 429 no ritmo padrão; por isso tem
  `min-request-interval: 3s` próprio. Fontes novas podem precisar do mesmo ajuste.
- **Remotive desativada.** O scraper existe, mas vem desligado: o `robots.txt` do site passou a
  proibir `/api`. Se voltar a permitir, ative em `techjobs.scraper.sources.remotive.enabled`.
- **Predominância de vagas remotas e internacionais.** É o perfil das fontes abertas hoje; o
  filtro de localização tende a devolver pouco para cidades brasileiras específicas.
- **Nível e modalidade são inferidos** do texto quando a fonte não informa; vagas sem indicação
  ficam como `UNKNOWN` e recebem pontuação parcial em vez de serem descartadas.
- **Salário** é interpretado em faixa numérica quando o texto permite (`salaryMin`,
  `salaryMax`, moeda e periodicidade); quando não permite, só o texto original é exibido.
  Não há conversão entre moedas.
- **Análise do currículo é heurística.** Currículos com layout muito fora do padrão (várias
  colunas, tabelas, títulos não convencionais) podem ter seções mal classificadas. PDF apenas
  digitalizado não tem texto extraível e é marcado como `EMPTY` — não há OCR.
- **Sem JavaScript rendering.** Só HTML estático e APIs/feeds. Sites que exigem execução de JS
  precisariam de Playwright ou Selenium, ainda não integrados.
- **Sem autenticação de verdade.** Não há login: a identidade do dono do currículo é um token
  opaco guardado no navegador e enviado em `X-Resume-Token`. Quem tiver o token acessa aquele
  currículo, e perder o token significa perder o acesso a ele. A entidade `AppUser` já existe
  para receber credenciais quando o login for implementado, sem quebrar os relacionamentos.
  A tabela `job_application` está criada e mapeada, mas ainda **não tem endpoint** — depende
  do login para fazer sentido.
- **Cache por combinação exata de filtros.** Filtros diferentes disparam coletas diferentes —
  país incluído: `BR` e `US` são duas coletas.
- **País por heurística de texto.** Fonte que manda só a cidade depende da lista de cidades do
  `CountryCatalog` (cerca de oito por país). Cidade fora da lista cai em `ZZ`: a vaga aparece
  em qualquer busca de país em vez de sumir, mas não é atribuída ao país certo.
- **Só o Jobicy filtra país na origem.** As demais fontes coletam o feed inteiro e a seleção
  acontece no banco. O acervo de um país cresce na velocidade das varreduras gerais, não de
  uma coleta dedicada àquele país.
- **`ZZ` é inclusivo por escolha.** Não há como pedir apenas vagas com país declarado: a busca
  por `BR` sempre traz junto as vagas sem país definido.
- **Contagem por país no seletor** vem do cache de catálogo (10 min), então fica defasada por
  esse tempo logo depois de uma ingestão.

---

## Cuidados legais no scraping

- `robots.txt` é verificado antes de **toda** requisição, com cache de 6 horas. Caminho proibido
  não é acessado; a fonte é registrada como falha e as demais seguem normalmente.
- Rate limiting por host (padrão: 1 requisição a cada 1,2 s), timeout e retry com backoff
  exponencial, para não sobrecarregar as fontes.
- User-Agent identificável, com URL do projeto e contato — configure `SCRAPER_USER_AGENT`.
- Nenhuma página HTML é armazenada: só os campos necessários da vaga.
- A URL original e o nome da fonte aparecem em todo resultado, e o botão "Ver vaga" leva sempre
  ao anúncio original. O RemoteOK exige atribuição e link de volta; ambos são respeitados.
- Nenhuma tentativa de contornar bloqueio, CAPTCHA ou autenticação.
- Antes de adicionar uma fonte, leia os termos de uso dela. Permitir tecnicamente não é o mesmo
  que permitir juridicamente, e a responsabilidade pelo uso é de quem opera a aplicação.
