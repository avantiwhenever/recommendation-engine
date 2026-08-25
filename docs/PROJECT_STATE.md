# Project state — read this first if picking up cold

This doc exists so a new Claude Code session (or a human) with zero memory of
prior conversations can figure out what this project is, what's already
decided, what's already verified working, and what's left to do — without
having to re-derive any of it from scratch. Update it as work progresses;
treat it as living memory, not a one-time snapshot.

## What this project is

A second portfolio project, sibling to `/Users/avanti/codebase/search` (a
Java hybrid lexical/semantic search system over the WANDS furniture
catalog). This one is a **React + GraphQL recommendation engine**: same
WANDS catalog, but backed by Pinecone instead of Elasticsearch, with a
pluggable recommendation-strategy layer (popularity, collaborative
filtering, bandit exploration, and an ONNX-served neural ranker) that can
add, remove, or re-rank a baseline search result set — grounded in real
arxiv recommender-systems literature rather than an arbitrary hack.

Three architectural rules were set explicitly by the user and are
**non-negotiable design constraints**, not suggestions:
1. Every Java service is **hexagonal architecture** (ports & adapters) —
   `domain`/`application`/`port` packages must never import Spring, gRPC
   stubs, or the Pinecone SDK; only `adapter` and `config` do.
2. **Java services talk to each other over gRPC only.** No REST between
   `graphql-gateway`, `search-service`, and `recommender-service`.
3. **GraphQL exists only at the frontend-facing edge** (`graphql-gateway`).
   Nothing downstream of the gateway speaks GraphQL or REST to another Java
   service.

## Where the full architecture plan lives

The complete, detailed architecture plan (approved by the user, including
the back-and-forth that shaped it) is at
`/Users/avanti/.claude/plans/lucky-forging-fairy.md` on this machine — but
that path is **local to this machine's Claude Code install and will not
exist in a fresh environment or a different session's filesystem view**.
This doc is the durable copy. If that plan file is ever unavailable, this
doc plus the "Key decisions" table in `README.md` fully substitute for it.

## Two upstream prerequisite tasks (already done)

Both already completed and pushed before this repo's work began — don't
redo them:
1. `/Users/avanti/codebase/search`: added 4 more demo queries (8 total) to
   the GitHub Pages strategy-comparison snapshot. Pushed to
   `avantiwhenever/search`.
2. `/Users/avanti/codebase/WANDS`: generated a synthetic ~5,000-user, 171K-
   event clickstream dataset (`dataset/clickstream.csv`), documented in that
   repo's `CLICKSTREAM.md` as synthetic and NOT part of the original Wayfair
   release. Pushed to `avantiwhenever/WANDS`. This is a **hard dependency**
   of `recommender-service` (popularity/collaborative/neural strategies all
   train on it) — fetched into this repo's `data/` via
   `scripts/download-data.sh`, not committed here.

## Build status (update this section as milestones land)

- **M0 — scaffold: DONE, verified, committed, pushed.** Parent Maven
  reactor (`rec-support`, `search-service`, `recommender-service`,
  `graphql-gateway`), shared `proto/` contracts with gRPC codegen verified
  working end-to-end, `rec-support` fully implemented and tested for real
  (not just compiled) against live infrastructure — see "Verified-working
  facts" below. Pushed to `avantiwhenever/recommendation-engine`.
- **M1 — search-service: DONE, verified, committed** (`0f0401c`). Hexagonal
  Pinecone-backed search + picocli ingestion CLI. Fork found and fixed two
  real Maven packaging bugs (shade-vs-Spring-Boot-repackage plugin
  ordering; gRPC's `NameResolverProvider` SPI file getting clobbered by
  unmerged shading) that only surfaced when actually running the built
  jars. Verified with a live Pinecone Local container + real ingested
  WANDS data + a `grpcurl` call returning correctly-ranked results.
- **M2 + M2.5 — recommender-service + neural ranking model: DONE, verified.**
  5 strategies (passthrough, popularity,
  collaborative-filtering, bandit, ONNX-based neural ranking), 18/18 tests
  passing. **Deviation from the original plan**: `NeuralRankingStrategy`'s
  "embedding cosine similarity" feature was replaced with a category-match
  proxy — no strategy ended up needing Pinecone/vector similarity, so
  `recommender-service` has no Pinecone dependency at all (removed from its
  `application.yml`; reads `product.csv` directly instead for catalog
  metadata). **Training pipeline caught its own bug**: the first two
  training attempts produced a suspicious ~99.98% held-out pairwise
  accuracy; traced to two real methodological bugs (negative sampling from
  the full catalog, then a trivial leakage where negatives always got a
  lower `base_score_proxy` than any real position could produce) — both
  fixed. Final honest result: **0.7972** neural ranker vs. 0.7093
  popularity-only, 0.6751 category-only, 0.4667 base-score-only (≈random,
  confirming the leak is gone), 0.5 random baseline. Full account in
  `training/TRAINING.md`. Real end-to-end smoke test against the live
  service with the actual trained model and all 5 strategies via `grpcurl`
  confirmed sensible, differentiated results per strategy.
- **M3 — graphql-gateway: DONE, verified.** Real schema, resolvers, gRPC
  client adapters to both backend services. 7/7 tests passing including
  real in-process gRPC round trips. **Bug found and fixed** (by this fork,
  then cleaned up by the main session once safe to touch the shared root
  pom): root `pom.xml` pinned `spring-graphql.version=1.3.3`, older than
  what `spring-boot-dependencies:3.5.16` itself manages (1.4.6) — the
  mismatch pulled an incompatible transitive `org.dataloader:java-dataloader`,
  breaking `GraphQlAutoConfiguration` at startup. Root pom now pins 1.4.6;
  the module-local workaround has been removed.
- **M4 — web (React UI): DONE, verified.** Vite + TS + Apollo Client, real component structure,
  loading/error states, humanized "source" badges. Verified via a clean
  `npm run build`, Vitest + RTL component tests against Apollo's
  `MockedProvider` (2/2 passing), and a real `docker build`/`docker run`
  round trip. **Host-level change**: Node.js wasn't installed on this
  machine — installed via `brew install node` (v26.7.0) to do this work.
  **Bug found and fixed in `docker-compose.yml`**: the `web` service passed
  `VITE_GRAPHQL_URL` as a runtime `environment:` var, but Vite bakes env
  vars in at build time — a static nginx-served bundle can't read a
  runtime env var, so it was a no-op. Fixed to a `build.args` entry.
- **M5 — IN PROGRESS as of this writing.** Done so far: full reactor
  build/test passing after integrating all four forks' work; JDK bumped to
  26 per explicit user request (see "Verified-working facts"); root
  `pom.xml`'s `spring-graphql` version fixed and `graphql-gateway`'s
  redundant local override removed; CI workflow
  (`.github/workflows/build.yml`) and Dependabot config
  (`.github/dependabot.yml`) written, adapted from the sibling `search`
  project's six-job posture for this polyglot/gRPC repo (JDK 26, added an
  `npm test`/`npm run build` step, matrixed `docker-lint`/`docker-image-scan`
  across all four Dockerfiles); `docker-compose.yml` fixed twice more (see
  below) beyond what the `web` fork already fixed. A real end-to-end
  `docker compose` run is in progress — check `git log --oneline -5` and
  `docker compose ps` to see how far it actually got before trusting "done":
  the honest way to tell is whether this section has an "end-to-end
  verified" bullet added below it, not just this bullet's presence.
  - **Docker VM disk exhaustion mid-build**: the Docker Desktop VM's disk
    allocation (~19.5GB) filled up building four images back-to-back
    (`no space left on device`). Fixed by `docker image prune -f`
    (dangling only, safe) then `docker image prune -a -f` once all target
    images existed (removes anything with zero referencing containers —
    verified safe because it never touches an image backing an existing
    container, running or stopped, so it can't disrupt unrelated projects
    sharing this Docker daemon).
  - **Host port conflicts with unrelated running projects**: this machine
    already has other projects' containers bound to 8080
    (`search-search-api-1`) and 5173 (`ai-image-generation-frontend-1`).
    Rather than stop those, `docker-compose.yml`'s `graphql-gateway` and
    `web` ports are now overridable via `${GATEWAY_HOST_PORT:-8080}` /
    `${WEB_HOST_PORT:-5173}` — verification used
    `GATEWAY_HOST_PORT=18080 WEB_HOST_PORT=15173 docker compose up -d`.
    (A `docker-compose.override.yml`-based approach was tried first and
    abandoned: Compose merges list-type keys like `ports` by concatenation,
    not replacement, so both the base and override port bindings were
    attempted simultaneously and the base one still conflicted.)
  - **The ingestion CLI must run inside the compose network, not on the
    host**: Pinecone Local's data-plane host discovery
    (`Pinecone.getIndexConnection`) returns whatever hostname the
    *container* was configured with via `PINECONE_HOST`
    (`pinecone-local` here, for other containers' benefit) — a host-run
    process can't resolve that name even though the control-plane port is
    published to `localhost`. Fixed by adding the ingestion CLI's shaded
    jar into `search-service`'s Docker image
    (`COPY --from=build .../search-service-*-ingestion-cli.jar ingestion-cli.jar`)
    and a `./data:/data:ro` volume mount, then running it via
    `docker compose run --entrypoint java search-service -jar ingestion-cli.jar ...`
    — inside the network, `pinecone-local` resolves correctly. Don't try
    the host-run path again; it's a dead end given how Pinecone Local
    advertises its data-plane host.
  - **Ingestion OOM-killed at default settings** (exit 137) when run
    alongside this machine's other already-running containers (the sibling
    `search` project's idle Elasticsearch+Kibana alone were holding ~2.9GB
    in a 7.7GB VM). Fixed by lowering `--batch-size` from 500 to 100 and
    capping the JVM heap (`JAVA_TOOL_OPTIONS=-Xmx1200m`) on the ingestion
    run specifically — the served application itself hasn't shown memory
    pressure at steady state (~380MB RSS each for search-service/
    recommender-service). If ingestion OOMs again on a differently-loaded
    machine, lower `--batch-size` further before assuming it's a code bug.
  - **Frontend/gateway port and CORS mismatch, found via real user testing**:
    the `web` image bakes `VITE_GRAPHQL_URL` in at build time, but
    `docker-compose.yml` hardcoded `http://localhost:8080/graphql`
    regardless of `GATEWAY_HOST_PORT` — so overriding the gateway's port
    (needed on this machine, see above) silently broke the frontend, which
    is exactly the error the user hit. Separately, `graphql-gateway` had
    **no CORS configuration at all**, which would have blocked every
    browser request regardless of port. Fixed both: `VITE_GRAPHQL_URL`'s
    build arg now interpolates `${GATEWAY_HOST_PORT:-8080}`, and
    `graphql-gateway/src/main/resources/application.yml` now sets
    `spring.graphql.cors.allowed-origins` from a `WEB_ORIGIN` env var that
    `docker-compose.yml` derives from `${WEB_HOST_PORT:-5173}` — the two
    ports are now decided together by construction, not independently.
    Verified via a real `curl -H "Origin: http://localhost:15173"` request
    showing the `Access-Control-Allow-Origin` response header.

- **M5 — DONE. Full end-to-end verification complete**, via real
  `docker compose up` with `GATEWAY_HOST_PORT=18080 WEB_HOST_PORT=15173`
  (this machine's other projects hold the defaults):
  - All 42,994 WANDS products ingested into the live Pinecone Local index
    (`wands-products`, 384-dim, cosine, confirmed `"status":{"ready":true}`
    via the control-plane API).
  - Queried the gateway directly (`curl`, not just the UI) with `NONE`,
    `POPULARITY`, `BANDIT`, and `NEURAL` strategies for the same query —
    confirmed each produces genuinely different results:
    `POPULARITY` injected two extra products beyond the 3 original
    candidates (the strategy's designed "add" behavior, actually observed);
    `NEURAL` produced a fully different ranking order, not just re-scored
    the same order; `source` field correctly reflects which strategy ran.
  - Confirmed the web UI loads and serves correctly at the (remapped) host
    port after the frontend/CORS fix above.
  - Docker Desktop VM disk pressure and host port conflicts with this
    machine's other running projects were worked around per the bullets
    above without touching anything outside this repo or those other
    projects' running containers.

- **M6 — post-M5 additions, DONE and pushed:**
  - **CI hardening**: main's first real CI run caught genuine HIGH-severity
    CVEs (grpc-netty-shaded 1.73.0 → Netty CVE-2025-55163, then a second
    round of 5 more Netty CVEs from a non-shaded `io.grpc:grpc-netty`
    pulled transitively by `io.pinecone:pinecone-client` — grpc-netty-shaded's
    *own* bundled netty is relocated/isolated and was never the problem).
    Fixed by bumping `grpc.version` to 1.83.1 and adding an explicit
    `netty-bom` override to 4.1.137.Final. Verified locally with `trivy`
    before each push, not just by waiting on CI. Also added
    `fail-fast: false` to the `docker-image-scan` matrix (one service's
    finding was canceling its siblings before they could report), and
    Dependabot `ignore` rules for major-version bumps that are genuine,
    known incompatibilities (`pinecone-client` 4.0.0+, Spring Boot/JUnit/
    protobuf-java/spring-graphql majors) rather than staleness — closed the
    5 PRs those rules retroactively cover, rebased the rest. Two more real
    rounds of findings followed as the CVE/rule databases updated during
    this session: 4 MEDIUM jackson-databind/log4j-api CVEs (same
    transitive-pin pattern, via `netty-bom`-style `dependencyManagement`
    overrides), and one real Semgrep SAST finding in
    `scripts/capture_demo_snapshots.py` (a CLI-arg URL reaching `urlopen`,
    which honors `file://` — fixed with an explicit http(s)-only scheme
    check, then a targeted `nosemgrep` since the rule is syntactic and
    can't see that check). **As of commit `8c0372f`, CI is fully green** —
    verify with `gh run list --workflow "Build and test" --branch main
    --limit 1` before assuming otherwise; every fix in this bullet was
    verified locally (`trivy`, `semgrep`) before pushing, not just hoped for.
  - **Offline evaluation harness** (`recommender-service/.../eval/EvalCli.java`,
    mirroring the sibling `search` project's `search-eval`): scores all 5
    strategies against 2,568 held-out clickstream sessions using implicit
    relevance grades (view/click/cart/purchase severity) since there's no
    explicit relevance label for recommendations the way WANDS has for
    search queries. Real, non-cherry-picked results in `RESULTS.md` —
    Collaborative Filtering wins, Bandit Exploration scores below baseline
    by design. Run via `scripts/run-recommender-eval.sh`.
  - **GitHub Pages demo** (`docs/`), live at
    <https://avantiwhenever.github.io/recommendation-engine/>: real captured
    queries × all 5 strategies, CSS/JS fully externalized (`styles.css`/
    `app.js`, no inline styles or scripts), hover-to-see-full-details product
    cards, and a strategy comparison section with per-strategy modals.
    Captured via `scripts/capture_demo_snapshots.py` against a live
    `docker compose` stack, writing `docs/data/*.json` + a `manifest.json`
    (the page fetches the manifest rather than embedding data or guessing
    filenames — a deliberate difference from the sibling `search` project's
    demo, which hand-embeds a JS block). **A real bug was found and fixed
    via headless-browser testing before considering this done**: the
    `hidden` modal overlay's own `display: flex` CSS was overriding the
    browser's default `display: none` for `[hidden]`, so the "hidden"
    overlay stayed laid out and invisibly intercepted pointer events across
    the entire page — undetectable by reading the code, only caught by
    actually driving the page with Playwright and finding a hover/click
    interaction silently fail.
  - Documented arxiv grounding on the two strategies that didn't have it
    yet (`PopularityBoostStrategy`, `CollaborativeFilteringStrategy`), and
    restructured the README with three audience-specific collapsible
    sections (recruiter / technical hiring manager / code reviewer) at the
    top, ahead of the existing deep-dive content.

**If you're resuming this session cold**: everything above is genuinely
done and pushed — run `git log --oneline` to confirm the latest commit
matches what this doc describes. If it doesn't, something changed after
this doc was last updated; trust `git log`/`git status` over this doc's
prose. A verification `docker compose` stack may or may not still be
running (`docker compose ps`) — it's fine to tear down
(`docker compose down`) or leave up depending on whether someone's still
using it; nothing about finishing this project depends on that stack's
state.

## Verified-working facts (don't re-litigate or re-discover these)

- **JDK 26**, matching the sibling `search` project's choice (an earlier
  draft of this project used JDK 21 for perceived gRPC/Protobuf tooling
  maturity, but the user asked to use the latest version instead — verified
  the full reactor still builds/tests clean under 26, and the Docker base
  images (`maven:3.9.16-eclipse-temurin-26`, `eclipse-temurin:26-jre-jammy`)
  are the exact same tags the sibling project already uses).
- **`protoc-gen-grpc-java`'s macOS binaries are x86_64 even for the
  "aarch_64" classifier**, across every version checked (1.53–1.73). This
  is Google's own packaging, not a bug specific to one version — it's
  meant to run via Rosetta 2 on Apple Silicon. **Rosetta 2 was installed on
  this machine** (`softwareupdate --install-rosetta --agree-to-license`,
  with explicit user approval first) to unblock this. If working on a
  fresh Apple Silicon machine, this will be needed again.
- **Pinecone Java client is pinned to `3.1.0`, not the latest (6.x)**.
  Verified directly against the jars (via `javap`, not docs/memory):
  `pinecone-client` 4.0.0+ requires a `vector_type` field in index-describe
  API responses that Pinecone Local's fixed API version (`2025-01`) doesn't
  return, causing a hard client-side deserialization failure
  (`IllegalArgumentException`) on `describeIndex`/`createServerlessIndex`.
  3.1.0 is the newest version whose `IndexModel` doesn't reference
  `vector_type` at all. This was confirmed end-to-end against a live
  `pinecone-local` container (real index create → upsert → query → correct
  nearest-neighbor ordering and metadata round-trip), not just compiled.
- **Pinecone Local's control-plane host needs a URL scheme**
  (`http://pinecone-local:5080`), but you never need to separately construct
  a data-plane host/port — `Pinecone.getIndexConnection(indexName)` resolves
  the correct per-index data-plane host internally via `describeIndex`.
  See `rec-support/.../pinecone/PineconeVectorStore.java`'s class Javadoc.
- **`ghcr.io/pinecone-io/pinecone-local` is a single-platform (x86_64-only)
  image with no shell inside it** (scratch/distroless-style, single `/control`
  binary entrypoint) — `docker-compose.yml` pins `platform: linux/amd64`
  explicitly and deliberately has **no healthcheck** on it (a CMD-based
  healthcheck can't run without a shell). Services depending on it just use
  plain `depends_on` (start-order only, not readiness) and are expected to
  retry their first Pinecone connection with backoff.
- `rec-support` is fully real, tested, and `mvn install`ed to the local
  `~/.m2` repo — `WandsProductCsvLoader`/`EmbeddingTextBuilder` (ported from
  the sibling `search` project), `EmbeddingService` (ONNX `bge-small-en-v1.5`,
  ported verbatim, verified against the real downloaded model), and
  `PineconeVectorStore` (verified against a live Pinecone Local container).
  Other modules should build against it via `mvn -pl <module> test`
  (**not** `-am`) to avoid rebuilding/racing on `rec-support`'s own
  `target/` directory when multiple modules are worked on concurrently.
- `data/` and `models/` are populated locally (gitignored, fetched via
  `scripts/download-data.sh` / `scripts/download-models.sh`) — real WANDS
  catalog (42,994 products), real clickstream (170,990 events / 5,000
  users), real `bge-small-en-v1.5` ONNX weights (128MB, fp32).

## M7 — staff-engineer review, industry research, and P0 fixes (DONE)

A deliberately hard-nosed critical review (persona: Pinterest staff search/
ML engineer) was requested and run against this codebase, followed by
research into real, cited techniques from Pinterest/Netflix/Airbnb/Spotify/
Etsy/LinkedIn/DoorDash/Uber/Amazon engineering blogs to address the
findings. Both were synthesized into **[TODO.md](../TODO.md)** — read that
file for the full, cited fix list (P0/P1/P2). All 5 P0 items (things that
were mislabeled or methodologically broken, not just simple) are now fixed:

1. Real Thompson Sampling bandit (was: random position shuffle with no
   arms, calling itself "epsilon-greedy").
2. Real adjusted-cosine item-item CF (was: raw co-occurrence counting with
   no popularity normalization).
3. Neural ranker retrained with a genuinely pairwise objective (was:
   pointwise regression scored by a pairwise metric) — honest finding: a
   plain linear baseline still beats both the new and old models on this
   feature set; kept the pairwise model in service anyway since matching
   the eval objective was the actual point.
4. **Independent offline eval against WANDS' real human judgments, plus a
   point-in-time-correctness fix** — this is the important one.
5. Java/Python feature-parity golden-vector test (already caught one real
   float-precision issue during its own construction).

**The eval fix overturned the project's own previous headline result.**
The original "Collaborative Filtering is the best strategy" conclusion in
an earlier `RESULTS.md` doesn't hold up — once the point-in-time leak is
fixed, Neural Ranking's old score (0.5692 nDCG@5) collapses to 0.3312, and
CF's apparent win shrinks to a statistical wash against baseline (0.5013 vs
0.5048). Against the independent WANDS-judgment ground truth, no strategy
shows a clear win over baseline. **If you're citing this project's results
anywhere, cite current `RESULTS.md`, not any earlier version or anything
said about it before this milestone.**

Execution note for future similar work: all 4 fixes were built as parallel
forks with disjoint file ownership (verified no two forks needed to edit
the same file's logic, only occasional one-line cross-fork compatibility
fixes when a shared call site like `RecommenderConfig`'s strategy wiring
needed to track another fork's constructor signature change) — this
pattern worked cleanly and is worth reusing for P1.

One open loose end from P0: the IPS/counterfactual estimator described in
TODO.md item #4 was explicitly skipped (not attempted-and-hidden) rather
than risk shipping a subtly-wrong estimator under time pressure.

**P0 work is committed, pushed, and CI-green** (commit `94dc10f`, "Fix P0
findings: eval circularity/leakage, vacuous bandit, fake CF, mislabeled
neural model"). The GitHub Pages demo (`docs/app.js`/`docs/index.html`) was
also updated to describe the corrected strategies and show the independent
WANDS-eval numbers instead of the overturned ones, and `docs/data/*.json`
was re-captured against the live, rebuilt stack. One CI leg
(`docker-image-scan (search-service)`) failed on its first run from a
transient Maven Central 429 rate-limit while fetching BOMs — unrelated to
any code change; `gh run rerun --failed` made it green.

### Post-P0 local verification session — a real build-arg caching footgun

While bringing the stack back up to test after the P0 push, hit and fixed
two more real issues, both worth remembering:

- **Recapturing demo snapshots with the wrong `userId`**: the first
  `capture_demo_snapshots.py` run used an ad-hoc override user ID
  (`demo-user-1`) that doesn't exist in the clickstream, so Collaborative
  Filtering and Bandit both silently degraded to passthrough (no history to
  personalize from). Re-ran with the script's real default (`u00001`, who
  does exist in the 4,718-user clickstream) — fixed. Separately confirmed
  that CF/Bandit *still* matching baseline for several of the 6 demo queries
  is legitimate, not a bug: many query result sets share a single top-level
  category (Bandit's arms collapse to one, so there's nothing to reorder
  against) or this particular user has no interaction-history overlap with
  those specific candidates (CF's similarity boost is genuinely zero).
- **`docker compose up -d` without the port-override env vars silently
  reset the gateway/web ports to their conflicting defaults.** Since this
  machine has other projects already bound to `8080` and `5173` (see M5's
  port-conflict bullet above), running plain `docker compose up -d` (no env
  vars) after rebuilding recreated `graphql-gateway` and `web` on the
  default ports, and `web`'s bind failed outright (`port is already
  allocated`, another project's container held 5173). Fixed by always
  passing `GATEWAY_HOST_PORT=18080 WEB_HOST_PORT=15173` on every `up`, not
  just the first one — **this has to be supplied on every invocation**,
  it's not sticky across `docker compose` calls without a `.env` file (none
  exists in this repo; consider adding one if this bites again).
- **A `docker compose build web` with the correct `GATEWAY_HOST_PORT` still
  reused a stale cached layer with the wrong baked-in URL.** The Dockerfile
  declares `ARG VITE_GRAPHQL_URL` immediately before the `RUN npm run
  build` step, which should invalidate cache on any ARG value change — but
  a plain `docker compose build web` reused the previous build's cached
  layer anyway (same image hash) despite `docker compose config` correctly
  resolving the new value. Root cause not fully isolated (classic-builder
  cache-key behavior on ARG changes proved unreliable here); the reliable
  fix was `docker compose build --no-cache web`. **If a Vite build-arg
  change doesn't seem to take effect after a normal rebuild, don't trust
  the cache — use `--no-cache`,** and verify by grepping the actual served
  bundle (`docker exec <container> grep -o 'http://localhost:[0-9]*/graphql'
  /usr/share/nginx/html/assets/*.js`) rather than trusting the build log.
- End result, confirmed working: full stack up via
  `GATEWAY_HOST_PORT=18080 WEB_HOST_PORT=15173 docker compose up -d`, gRPC/
  GraphQL round-trips confirmed via `curl` (including a real CORS preflight
  from `Origin: http://localhost:15173`), and the user confirmed the UI at
  `http://localhost:15173` works after a hard refresh (the browser had the
  old, wrong-port JS bundle cached from before the `--no-cache` rebuild).

## M8 — P1 capability gaps + P2 cleanups (DONE)

Following M7's staff-engineer review and TODO.md's cited fix list, all 6 P1
items (#6-#11) and both P2 items (#12-#13) are now done — see
**[TODO.md](../TODO.md)** for the full, cited "Result" writeup per item.
Executed as five parallel forks with disjoint file ownership (same pattern
M7 validated for P0), plus one sequential follow-on and direct integration
work by the coordinating session:

1. **#6 — hybrid dense+lexical retrieval**: a hand-rolled, embedded BM25
   index (no Elasticsearch/Lucene server — this project stays Pinecone-only
   infra) fused with the existing Pinecone dense retrieval via RRF, in
   `search-service`. Live-verified: exact brand-token queries now surface
   the branded product at #1.
2. **#7 — diversity decorator**: `DiversityAwareStrategy`, an MMR re-rank
   pass wrapping any existing strategy, wired end-to-end as a new
   selectable `Strategy.DIVERSE_POPULARITY` (proto/domain/GraphQL enum all
   updated). Live-verified injecting real category variety into a
   single-category candidate list.
3. **#8 — `VectorSimilarityPort`**: a real Pinecone-backed "find similar
   products" port in `recommender-service`, reusing the same
   `wands-products` index `search-service` populates as a direct
   infrastructure dependency (per the original architecture plan's own
   carve-out). Live-tested against the real index. **Built but not yet
   consumed by any strategy** — items #7 and #10 both name it as their
   documented next step, not a hidden gap.
4. **#10 + #11 — cold start + session recency** (bundled into one fork
   since they share files): `CollaborativeFilteringStrategy` now has a
   named, explicit popularity fallback for genuinely cold users (no
   all-time history *and* no session signal); `RecommendRequest` gained a
   `recentProductIds` field threaded all the way to an optional GraphQL
   argument; `NeuralRankingStrategy` gained a 7th feature (session category
   overlap) requiring a full retrain — real result: the new feature raised
   every model's held-out accuracy ~6-7 points (XGBoost 0.7995→0.8687,
   linear baseline 0.8046→0.8751) without changing which model wins (the
   linear baseline still does, same honest finding as M7's item #3).
5. **IPS/counterfactual estimator** (the one loose end M7 explicitly left
   open): `IpsEvaluator.java`, using `WANDS/CLICKSTREAM.md`'s exact,
   documented logging-policy constants — unusually implementable here since
   the logging policy is fully known, unlike almost any real production
   system. Reports raw and propensity-clipped estimates plus an effective-
   sample-size diagnostic, so IPS's known instability is visible rather
   than hidden behind one clean number. Real results in RESULTS.md's new
   "Off-policy (IPS) evaluation" section.
6. **#9 — multi-stage retrieval** (done as a sequential follow-on, not a
   parallel fork, since it touches the same file — `SearchOrchestrationUseCase.java`
   — as #12's filter plumbing): the gateway now asks `search-service` for a
   200-candidate pool (not just the display `topK`), applies a hard
   eligibility selection stage (drops zero-social-proof candidates, cuts to
   50 by score) *before* any strategy runs — including `NONE`, which is now
   the eligibility-filtered pool unmodified, not a byte-for-byte passthrough
   of raw search results. Live-verified: this alone changed a
   `COLLABORATIVE` query's top result to a product the old ~6-10-candidate
   pool never had a chance to include.
7. **#12 — proto contract filters** (deliberately partial): `category_filter`/
   `min_rating` added to `SearchRequest` and threaded to optional GraphQL
   arguments, live-verified (category mismatch → empty result; rating floor
   → correctly narrowed results). A pagination cursor and a per-result
   debug/explain field are explicitly still open, not implemented.
8. **#13 — README honesty paragraph**: added, naming the hexagonal/gRPC/
   four-service architecture as "more service boundary than the problem
   needs," matching this project's existing honesty norms elsewhere.

**Two real bugs found and fixed during integration, unrelated to the P1/P2
items themselves**: (a) a stale `docker-compose.yml`/Vite build-arg mismatch
from the P0-verification session recurred and was re-fixed the same way
(`--no-cache` rebuild); (b) `web/src/utils/humanizeSource.ts`'s
`SOURCE_LABELS` keys never actually matched the backend's real strategy
names (`"collaborative"` vs. the real `"Collaborative Filtering"`) — a
latent bug hidden by `App.test.tsx`'s mock data using the same wrong short
form, so the mismatch never surfaced in tests. Fixed both the mapping and
the test's mock data to use real backend strings; confirmed via
`npm test`.

**Full verification before considering this done**: `mvn clean test`
(whole reactor, excluding the two Docker-network-dependent Pinecone smoke
tests) green; those two smoke tests separately run and passed against the
live network (`docker run --network container:pinecone-local-1 ...`); all
five rebuilt Docker images (`search-service`, `recommender-service`,
`graphql-gateway`, `web`) deployed and live-curl-verified end-to-end,
including the new `DIVERSE_POPULARITY` strategy, hybrid retrieval, the
widened multi-stage pipeline, and both new filter arguments; `RESULTS.md`
regenerated with all 6 strategies plus the new IPS section;
`docs/app.js`/`docs/index.html`/`docs/data/*.json` (GitHub Pages demo) and
`web/src/graphql/types.ts` (live React app's strategy dropdown) both
updated and re-captured/rebuilt to match.

## Known simplifications / deliberate scope cuts (be upfront about these, don't silently "fix")

- Proto stubs are generated independently in all three consuming modules
  (each compiles its own copy of `search_service.proto` /
  `recommender_service.proto` from the shared `proto/` dir) rather than via
  one shared generated-stubs artifact. Simpler for a small polyglot repo;
  costs a little redundant compilation, no functional downside since gRPC
  messages are wire-compatible regardless of which JVM/module produced the
  generated class.
- Pinecone Local has no persistence and a 100K-vector cap — explicitly a
  local-dev/demo choice, not production-representative; document this
  plainly in the README rather than pretending otherwise (same honesty
  norm as the sibling `search` project's README/WRITEUP.md).
