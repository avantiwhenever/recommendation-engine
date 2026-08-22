import { gql } from "@apollo/client";

export const SEARCH_QUERY = gql`
  query Search($query: String!, $topK: Int, $strategy: RecommenderStrategy, $userId: String) {
    search(query: $query, topK: $topK, strategy: $strategy, userId: $userId) {
      query
      strategy
      products {
        productId
        name
        productClass
        categoryHierarchy
        averageRating
        ratingCount
        score
        source
      }
    }
  }
`;
