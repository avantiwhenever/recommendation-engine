import type { Product } from "../graphql/types";
import { ResultCard } from "./ResultCard";

interface ResultsGridProps {
  products: Product[];
}

export function ResultsGrid({ products }: ResultsGridProps) {
  if (products.length === 0) {
    return <p className="results-empty">No results for this query.</p>;
  }

  return (
    <div className="results-grid" data-testid="results-grid">
      {products.map((product, index) => (
        <ResultCard key={product.productId} product={product} rank={index + 1} />
      ))}
    </div>
  );
}
