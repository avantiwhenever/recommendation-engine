# Data

The project uses two data sources: a real product catalog and a synthetic clickstream.

## Sources

| Data | Source | Size | Real or synthetic |
|---|---|---|---|
| Product catalog (`product.csv`) | [WANDS](https://github.com/wayfair/WANDS) from Wayfair | 42,994 products | Real |
| Search queries (`query.csv`) | WANDS | 480 queries | Real |
| Relevance labels (`label.csv`) | WANDS | 233,448 human labels: Exact, Partial, or Irrelevant | Real |
| Clickstream (`clickstream.csv`) | [avantiwhenever/WANDS](https://github.com/avantiwhenever/WANDS/blob/main/CLICKSTREAM.md) | 170,990 events, 12,818 sessions, 5,000 users | **Synthetic** |

To download all four files, run `./scripts/download-data.sh`. The files go into `data/`, which Git ignores.

## The synthetic clickstream

WANDS has no user behavior. The recommendation strategies need behavior data, so the project generates it.

- **Users.** Each of the 5,000 users has 1 to 3 preferred product categories.
- **Sessions.** Each user has 1 to 6 sessions. In each session, the user searches one of the 480 real WANDS queries.
- **Events.** Each product on the results page gets a `view` event. Then a cascade model decides on `click`, `add_to_cart`, and `purchase` events.
- **Click probability.** The probability depends on the position of the product and on its WANDS relevance grade. The position factor is `1 / position^0.8`.
- **Deterministic.** With the same seed (default 42), the generator always gives the same file.

[CLICKSTREAM.md](https://github.com/avantiwhenever/WANDS/blob/main/CLICKSTREAM.md) gives the full method.

**Important:** this data does not represent real user behavior. Results that use the clickstream show how a strategy behaves on this model of users. They do not predict results with real users.

## Effect on the evaluation

The generator uses the WANDS relevance grades to order each session and to make the clicks. Thus, the clickstream metric is partly circular. The evaluation also scores each strategy against the WANDS human labels directly, to give an independent result. See [EVALUATION.md](EVALUATION.md).

## File format notes

- The WANDS files have the `.csv` extension, but they are tab-separated.
- The WANDS column for the category path is `category hierarchy`, with a space in the name.
- `WandsProductCsvLoader` in `rec-support` handles both of these points.
