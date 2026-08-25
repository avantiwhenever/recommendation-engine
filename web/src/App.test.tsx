import { describe, it, expect } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MockedProvider } from "@apollo/client/testing/react";
import App from "./App";
import { SEARCH_QUERY } from "./graphql/queries";

const mockSearchResponse = {
  request: {
    query: SEARCH_QUERY,
    variables: { query: "platform bed", topK: 12, strategy: "COLLABORATIVE" },
  },
  result: {
    data: {
      search: {
        query: "platform bed",
        strategy: "COLLABORATIVE",
        products: [
          {
            productId: "1",
            name: "Solid Wood Platform Bed",
            productClass: "Beds",
            categoryHierarchy: "Furniture / Bedroom / Beds",
            averageRating: 4.5,
            ratingCount: 120,
            score: 0.93,
            source: "search",
          },
          {
            productId: "2",
            name: "Queen Platform Bed Frame",
            productClass: "Beds",
            categoryHierarchy: "Furniture / Bedroom / Beds",
            averageRating: 4.1,
            ratingCount: 58,
            score: 0.81,
            source: "Collaborative Filtering",
          },
          {
            productId: "3",
            name: "Matching Nightstand",
            productClass: "Nightstands",
            categoryHierarchy: "Furniture / Bedroom / Nightstands",
            averageRating: null,
            ratingCount: null,
            score: 0.4,
            source: "Popularity",
          },
        ],
      },
    },
  },
};

describe("App", () => {
  it("renders search results with humanized source badges after a search", async () => {
    const user = userEvent.setup();

    render(
      <MockedProvider mocks={[mockSearchResponse]}>
        <App />
      </MockedProvider>,
    );

    await user.type(screen.getByLabelText("Search query"), "platform bed");
    await user.click(screen.getByRole("button", { name: /search/i }));

    await waitFor(() => {
      expect(screen.getByText("Solid Wood Platform Bed")).toBeInTheDocument();
    });

    expect(screen.getByText("Queen Platform Bed Frame")).toBeInTheDocument();
    expect(screen.getByText("Matching Nightstand")).toBeInTheDocument();

    // Ratings render correctly, including the "no ratings" fallback.
    expect(screen.getByText("★ 4.5")).toBeInTheDocument();
    expect(screen.getByText("No ratings yet")).toBeInTheDocument();

    // Raw `source` values are humanized into readable badges.
    expect(screen.getByText("Matched your search")).toBeInTheDocument();
    expect(screen.getByText("Boosted — shoppers like you also viewed this")).toBeInTheDocument();
    expect(screen.getByText("Boosted — popular with other shoppers")).toBeInTheDocument();
  });

  it("shows a friendly error state when the gateway is unreachable", async () => {
    const user = userEvent.setup();
    const errorMock = {
      request: {
        query: SEARCH_QUERY,
        variables: { query: "chair", topK: 12, strategy: "COLLABORATIVE" },
      },
      error: new Error("Failed to fetch"),
    };

    render(
      <MockedProvider mocks={[errorMock]}>
        <App />
      </MockedProvider>,
    );

    await user.type(screen.getByLabelText("Search query"), "chair");
    await user.click(screen.getByRole("button", { name: /search/i }));

    await waitFor(() => {
      expect(screen.getByRole("alert")).toBeInTheDocument();
    });
    expect(screen.getByText(/couldn't reach the recommendation engine/i)).toBeInTheDocument();
  });
});
