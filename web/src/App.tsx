import { useState } from "react";
import { useLazyQuery } from "@apollo/client/react";
import { SearchBar } from "./components/SearchBar";
import { StrategySelector } from "./components/StrategySelector";
import { ResultsGrid } from "./components/ResultsGrid";
import { LoadingState } from "./components/LoadingState";
import { ErrorState } from "./components/ErrorState";
import { SEARCH_QUERY } from "./graphql/queries";
import type { RecommenderStrategy, SearchQueryResult, SearchQueryVars } from "./graphql/types";
import "./App.css";

const DEFAULT_STRATEGY: RecommenderStrategy = "COLLABORATIVE";
const TOP_K = 12;

export default function App() {
  const [query, setQuery] = useState("");
  const [strategy, setStrategy] = useState<RecommenderStrategy>(DEFAULT_STRATEGY);
  const [runSearch, { data, loading, error }] = useLazyQuery<SearchQueryResult, SearchQueryVars>(SEARCH_QUERY, {
    fetchPolicy: "network-only",
  });

  function handleSearch(nextQuery: string) {
    setQuery(nextQuery);
    runSearch({ variables: { query: nextQuery, topK: TOP_K, strategy } });
  }

  function handleStrategyChange(nextStrategy: RecommenderStrategy) {
    setStrategy(nextStrategy);
    if (query) {
      runSearch({ variables: { query, topK: TOP_K, strategy: nextStrategy } });
    }
  }

  function retry() {
    if (query) {
      runSearch({ variables: { query, topK: TOP_K, strategy } });
    }
  }

  const hasSearched = data !== undefined || loading || error !== undefined;

  return (
    <div className="app">
      <header className="app__header">
        <span className="brand">Recommendation Engine</span>
        <p className="app__tagline">
          Search the WANDS furniture catalog, then switch recommendation strategies to see results
          get added, removed, and re-ranked in real time.
        </p>
      </header>

      <div className="app__controls">
        <SearchBar initialQuery={query} onSearch={handleSearch} isLoading={loading} />
        <StrategySelector value={strategy} onChange={handleStrategyChange} />
      </div>

      <main className="app__main">
        {!hasSearched && (
          <p className="app__hint">Try a search like “cozy reading chair” or “outdoor patio set.”</p>
        )}
        {loading && <LoadingState />}
        {error && <ErrorState message={error.message} onRetry={retry} />}
        {!loading && !error && data && <ResultsGrid products={data.search.products} />}
      </main>
    </div>
  );
}
