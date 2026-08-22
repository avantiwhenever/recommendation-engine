import { STRATEGIES } from "../graphql/types";
import type { RecommenderStrategy } from "../graphql/types";

interface StrategySelectorProps {
  value: RecommenderStrategy;
  onChange: (strategy: RecommenderStrategy) => void;
}

export function StrategySelector({ value, onChange }: StrategySelectorProps) {
  return (
    <label className="strategy-selector">
      <span className="strategy-selector__label">Recommendation strategy</span>
      <select
        className="strategy-selector__select"
        value={value}
        onChange={(event) => onChange(event.target.value as RecommenderStrategy)}
      >
        {STRATEGIES.map((strategy) => (
          <option key={strategy.value} value={strategy.value}>
            {strategy.label}
          </option>
        ))}
      </select>
    </label>
  );
}
