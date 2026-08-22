import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { ApolloClient, InMemoryCache } from "@apollo/client";
import { HttpLink } from "@apollo/client/link/http";
import { ApolloProvider } from "@apollo/client/react";
import "./index.css";
import App from "./App";

const GRAPHQL_URL = import.meta.env.VITE_GRAPHQL_URL ?? "http://localhost:8080/graphql";

const client = new ApolloClient({
  link: new HttpLink({ uri: GRAPHQL_URL }),
  cache: new InMemoryCache(),
});

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <ApolloProvider client={client}>
      <App />
    </ApolloProvider>
  </StrictMode>,
);
