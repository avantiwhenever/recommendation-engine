"use strict";

// Static GitHub Pages demo — fetches pre-captured JSON from docs/data/
// (see scripts/capture_demo_snapshots.py) rather than embedding the data
// in this file or in index.html, so re-capturing never requires hand-editing
// any JS/HTML.

const STRATEGY_ORDER = ["NONE", "POPULARITY", "COLLABORATIVE", "BANDIT", "NEURAL"];

// Kept in sync with web/src/utils/humanizeSource.ts's mapping — same badge
// language in both the live React app and this static demo.
const SOURCE_LABELS = {
  search: "Matched your search",
  none: "Baseline search result",
  popularity: "Boosted — popular with other shoppers",
  "collaborative filtering": "Boosted — shoppers like you also viewed this",
  "bandit exploration": "Surfaced for exploration",
  "neural ranking": "Ranked by the neural model",
};

function humanizeSource(source) {
  return SOURCE_LABELS[(source || "").trim().toLowerCase()] ?? source;
}

// Offline evaluation numbers from RESULTS.md, regenerated via
// scripts/run-recommender-eval.sh. RESULTS.md reports TWO tables:
//   - "Implicit clickstream eval" — relevance grades derived from the same
//     synthetic clickstream the strategies themselves read from. An earlier
//     version of this eval leaked future clickstream events into feature
//     computation and used only this circular ground truth; both bugs were
//     found and fixed (see TODO.md #1-#3) and the original "Collaborative
//     Filtering wins" headline did not survive the fix.
//   - "Independent WANDS relevance eval" — a second, ground-truth-independent
//     table scored against WANDS's own human relevance grades (label.csv),
//     which no strategy was trained or tuned against. This is the more
//     trustworthy of the two, and is what's shown below.
// Update these four fields per strategy if RESULTS.md's numbers change.
const STRATEGY_INFO = {
  NONE: {
    label: "None",
    tagline: "The control group — nothing changes.",
    how: "Passes search-service's candidates through completely unmodified. No re-ranking, no injected products, no removed products. This exists so every other strategy has something concrete to be measured against.",
    differs: "The only strategy that touches nothing. Every other strategy's numbers below are meaningful only relative to this baseline — and in the independent WANDS eval, none of them clearly beats it.",
    grounding: "Standard practice in any A/B or offline-eval setup: without an unmodified control, you can't tell whether another strategy actually helped.",
    metrics: { ndcg: 0.9673, mrr: 0.9981, recall: 0.0608, precision: 0.9967 },
  },
  POPULARITY: {
    label: "Popularity",
    tagline: "Blends search relevance with what's trending, and can add trending products search missed.",
    how: "Reranks by a 60/40 blend of search-service's own score and a clickstream-derived popularity score (view/click/cart/purchase counts, log-scaled). Then injects up to 2 globally popular products absent from the original candidates — a real addition, not just a reorder.",
    differs: "The only strategy using a purely global signal (popularity is the same for every user) rather than anything personalized. Cheapest and most robust of the five — and, in the independent WANDS eval, the highest-nDCG strategy of the five, though by a margin too small to call decisive.",
    grounding: "Popularity-based backfill is the most common production recommender pattern in practice — see arXiv:2509.06002, “A Survey of Real-World Recommender Systems,” which discusses this as a standard, robust component of real candidate-generation pipelines, not a naive strawman.",
    metrics: { ndcg: 0.9721, mrr: 0.9981, recall: 0.0610, precision: 0.9900 },
  },
  COLLABORATIVE: {
    label: "Collaborative Filtering",
    tagline: "Personalized to this user's real interaction history — not the top scorer, but not a coin flip either.",
    how: "Classic item-item collaborative filtering: boosts candidates by their adjusted-cosine similarity, computed from real clickstream sessions, to products this specific user already interacted with, and injects related items the search didn't surface.",
    differs: "The only strategy personalized to the requesting user via real interaction history, not just a global signal. An earlier version of this project's eval mistakenly credited this strategy as the outright winner — that result turned out to be an artifact of circular ground truth (clickstream-derived labels scoring a clickstream-driven strategy) plus a temporal leak. Once fixed, it lands in the same tight band as the other strategies on the independent eval.",
    grounding: "Item-item collaborative filtering with adjusted-cosine normalization — Sarwar, Karypis, Konstan & Riedl, “Item-Based Collaborative Filtering Recommendation Algorithms,” WWW 2001.",
    metrics: { ndcg: 0.9675, mrr: 0.9981, recall: 0.0608, precision: 0.9928 },
  },
  BANDIT: {
    label: "Bandit Exploration",
    tagline: "Scores slightly below baseline — that's the point, not a bug.",
    how: "Thompson Sampling over per-category arms: a Beta(α,β) posterior per category, warm-started from historical clickstream engagement rates and conditioned on the requesting user's top category, with a windowed cap on how much any one exploration draw can reorder the list.",
    differs: "The only strategy that intentionally sacrifices measured ranking quality. Every other strategy tries to rank the best result first; this one spends some of that precision on surfacing under-exposed products, on the bet that it pays off over time in ways a single offline snapshot can't measure. Honesty note: the priors are fixed at construction time from historical data — there's no live reward loop feeding results back into the arms yet.",
    grounding: "Thompson Sampling for explore/exploit tradeoffs in ranked recommendations — see Etsy's OPAR (Optimizely-style Personalization) engineering blog and Spotify Research's contextual-bandit writeups on recommendation ranking. Scoring below the None baseline in offline evaluation is expected: exploration's real value — surfacing under-exposed products over time — isn't something a single held-out snapshot can credit; see RESULTS.md.",
    metrics: { ndcg: 0.9458, mrr: 0.9944, recall: 0.0588, precision: 0.9938 },
  },
  NEURAL: {
    label: "Neural Ranking",
    tagline: "A learning-to-rank model, trained from scratch on this project's own data.",
    how: "Scores each candidate with an XGBoost pairwise ranking model (trained by training/train_neural_ranker.py on this project's synthetic clickstream with a rank:ndcg objective, exported to ONNX) over 6 features: category match, base search score, popularity, co-occurrence with the user's history, average rating, and review count.",
    differs: "The only strategy that learned its ranking function from data rather than having it hand-specified. Reported honestly: a simple logistic-regression baseline trained on the same features scored marginally higher than the XGBoost model on the held-out split (0.8046 vs 0.7995). XGBoost was kept anyway for its ability to model feature interactions, but the gap is a real finding, not rounding noise — see training/TRAINING.md.",
    grounding: "Pairwise learning-to-rank (LambdaRank-style, via XGBoost's rank:ndcg objective) over cheap, mostly-cached features and one small inference pass — same “cheap features, no transformer” philosophy as the sibling `search` project's NeuralRerankStrategy/RerankFeatureBuilder pattern. The training run initially produced a suspiciously perfect result — traced to two real data-leakage bugs and fixed before trusting these numbers.",
    metrics: { ndcg: 0.9638, mrr: 0.9977, recall: 0.0608, precision: 0.9967 },
  },
};

const statusEl = document.getElementById("status");
const columnsEl = document.getElementById("columns");
const selectEl = document.getElementById("query-select");
const glanceGridEl = document.getElementById("glance-grid");
const hoverCardEl = document.getElementById("hover-card");

let QUERY_DATA = {};

async function loadQueryData() {
  statusEl.textContent = "Loading captured query snapshots…";
  const manifestResponse = await fetch("data/manifest.json");
  if (!manifestResponse.ok) {
    statusEl.textContent = "No captured snapshots found under docs/data/ — run scripts/capture_demo_snapshots.py first.";
    return;
  }
  const files = await manifestResponse.json();
  const loaded = await Promise.all(files.map((name) => fetch(`data/${name}`).then((r) => r.json())));
  for (const entry of loaded) {
    QUERY_DATA[entry.query] = entry.resultsByStrategy;
  }
  statusEl.textContent = "";

  for (const query of Object.keys(QUERY_DATA)) {
    const option = document.createElement("option");
    option.value = query;
    option.textContent = query;
    selectEl.appendChild(option);
  }
  selectEl.addEventListener("change", () => renderColumns(selectEl.value));
  renderColumns(Object.keys(QUERY_DATA)[0]);
}

function formatRating(product) {
  if (!product.averageRating || !product.ratingCount) {
    return "No ratings yet";
  }
  return `★ ${product.averageRating.toFixed(1)} (${product.ratingCount})`;
}

function renderColumns(query) {
  columnsEl.innerHTML = "";
  const resultsByStrategy = QUERY_DATA[query];
  if (!resultsByStrategy) {
    return;
  }

  for (const strategyKey of STRATEGY_ORDER) {
    const data = resultsByStrategy[strategyKey];
    const info = STRATEGY_INFO[strategyKey];
    const column = document.createElement("div");
    column.className = "column";

    const header = document.createElement("div");
    header.className = "column-header";
    header.textContent = info.label;
    header.addEventListener("click", () => openModal(strategyKey));
    column.appendChild(header);

    if (data) {
      for (const product of data.products) {
        column.appendChild(renderProductCard(product));
      }
    }
    columnsEl.appendChild(column);
  }
}

function renderProductCard(product) {
  const card = document.createElement("div");
  card.className = "product-card";

  const name = document.createElement("div");
  name.className = "product-name";
  name.textContent = product.name;
  card.appendChild(name);

  const meta = document.createElement("div");
  meta.className = "product-meta";
  meta.innerHTML = `<span>${formatRating(product)}</span><span>${product.score.toFixed(3)}</span>`;
  card.appendChild(meta);

  const badge = document.createElement("div");
  badge.className = "source-badge";
  badge.textContent = humanizeSource(product.source);
  card.appendChild(badge);

  card.addEventListener("mouseenter", (event) => showHoverCard(product, event));
  card.addEventListener("mousemove", (event) => positionHoverCard(event));
  card.addEventListener("mouseleave", hideHoverCard);

  return card;
}

function showHoverCard(product, event) {
  document.getElementById("hover-name").textContent = product.name;
  document.getElementById("hover-category").textContent = product.categoryHierarchy || "—";
  document.getElementById("hover-class").textContent = product.productClass || "—";
  document.getElementById("hover-rating").textContent = formatRating(product);
  document.getElementById("hover-score").textContent = product.score.toFixed(4);
  document.getElementById("hover-source").textContent = humanizeSource(product.source);
  document.getElementById("hover-id").textContent = product.productId;
  hoverCardEl.hidden = false;
  positionHoverCard(event);
}

function positionHoverCard(event) {
  const offset = 16;
  const cardWidth = 260;
  const viewportWidth = window.innerWidth;
  let left = event.clientX + offset;
  if (left + cardWidth > viewportWidth) {
    left = event.clientX - cardWidth - offset;
  }
  hoverCardEl.style.left = `${left}px`;
  hoverCardEl.style.top = `${event.clientY + offset}px`;
}

function hideHoverCard() {
  hoverCardEl.hidden = true;
}

function renderGlanceGrid() {
  for (const strategyKey of STRATEGY_ORDER) {
    const info = STRATEGY_INFO[strategyKey];
    const card = document.createElement("div");
    card.className = "glance-card";
    card.innerHTML = `
      <h3>${info.label}</h3>
      <p>${info.tagline}</p>
      <span class="metric-pill">nDCG@5 ${info.metrics.ndcg.toFixed(4)}</span>
    `;
    card.addEventListener("click", () => openModal(strategyKey));
    glanceGridEl.appendChild(card);
  }
}

function openModal(strategyKey) {
  const info = STRATEGY_INFO[strategyKey];
  document.getElementById("modal-title").textContent = info.label;
  document.getElementById("modal-tagline").textContent = info.tagline;
  document.getElementById("modal-how").textContent = info.how;
  document.getElementById("modal-differs").textContent = info.differs;
  document.getElementById("modal-grounding").textContent = info.grounding;
  document.getElementById("modal-metrics-row").innerHTML = `
    <td>${info.metrics.ndcg.toFixed(4)}</td>
    <td>${info.metrics.mrr.toFixed(4)}</td>
    <td>${info.metrics.recall.toFixed(4)}</td>
    <td>${info.metrics.precision.toFixed(4)}</td>
  `;
  document.getElementById("modal-overlay").hidden = false;
}

document.getElementById("modal-close").addEventListener("click", () => {
  document.getElementById("modal-overlay").hidden = true;
});
document.getElementById("modal-overlay").addEventListener("click", (event) => {
  if (event.target.id === "modal-overlay") {
    event.currentTarget.hidden = true;
  }
});

renderGlanceGrid();
loadQueryData();
