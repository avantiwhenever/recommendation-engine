import type { Product } from "../graphql/types";
import { humanizeSource } from "../utils/humanizeSource";

interface ResultCardProps {
  product: Product;
  rank: number;
}

export function ResultCard({ product, rank }: ResultCardProps) {
  return (
    <article className="result-card">
      <div className="result-card__rank">#{rank}</div>
      <h3 className="result-card__name">{product.name}</h3>
      {product.categoryHierarchy && <p className="result-card__category">{product.categoryHierarchy}</p>}
      <div className="result-card__meta">
        {product.averageRating != null ? (
          <span className="result-card__rating">
            ★ {product.averageRating.toFixed(1)}
            {product.ratingCount != null && <span className="result-card__rating-count"> ({product.ratingCount})</span>}
          </span>
        ) : (
          <span className="result-card__rating result-card__rating--none">No ratings yet</span>
        )}
        <span className="result-card__score" title="Retrieval/ranking score">
          score {product.score.toFixed(3)}
        </span>
      </div>
      <div className="result-card__badge" title="Why this result was shown">
        {humanizeSource(product.source)}
      </div>
    </article>
  );
}
