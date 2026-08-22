import { useState } from "react";
import type { FormEvent } from "react";

interface SearchBarProps {
  initialQuery: string;
  onSearch: (query: string) => void;
  isLoading: boolean;
}

export function SearchBar({ initialQuery, onSearch, isLoading }: SearchBarProps) {
  const [value, setValue] = useState(initialQuery);

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const trimmed = value.trim();
    if (trimmed.length > 0) {
      onSearch(trimmed);
    }
  }

  return (
    <form className="search-bar" onSubmit={handleSubmit} role="search">
      <input
        type="search"
        className="search-bar__input"
        placeholder="Search the catalog, e.g. “queen platform bed”"
        value={value}
        onChange={(event) => setValue(event.target.value)}
        aria-label="Search query"
      />
      <button type="submit" className="search-bar__button" disabled={isLoading || value.trim().length === 0}>
        {isLoading ? "Searching…" : "Search"}
      </button>
    </form>
  );
}
