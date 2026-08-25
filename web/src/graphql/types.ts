export type RecommenderStrategy = "NONE" | "POPULARITY" | "COLLABORATIVE" | "BANDIT" | "NEURAL" | "DIVERSE_POPULARITY";

export const STRATEGIES: { value: RecommenderStrategy; label: string }[] = [
  { value: "NONE", label: "None (baseline search only)" },
  { value: "POPULARITY", label: "Popularity boost" },
  { value: "COLLABORATIVE", label: "Collaborative filtering" },
  { value: "BANDIT", label: "Bandit exploration" },
  { value: "NEURAL", label: "Neural ranking (ONNX)" },
  { value: "DIVERSE_POPULARITY", label: "Diverse popularity (MMR)" },
];

export interface Product {
  productId: string;
  name: string;
  productClass: string | null;
  categoryHierarchy: string | null;
  averageRating: number | null;
  ratingCount: number | null;
  score: number;
  source: string;
}

export interface SearchResult {
  query: string;
  strategy: RecommenderStrategy;
  products: Product[];
}

export interface SearchQueryResult {
  search: SearchResult;
}

export interface SearchQueryVars {
  query: string;
  topK: number;
  strategy: RecommenderStrategy;
  userId?: string | null;
}
