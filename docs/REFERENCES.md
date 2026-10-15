# References

Each strategy and method in this project comes from a published source. This list shows which source supports which part.

## By topic

| Topic | Source | Use in this project |
|---|---|---|
| System structure | [A Survey of Real-World Recommender Systems](https://arxiv.org/abs/2509.06002) (arXiv:2509.06002) | Candidate generation, then re-ranking |
| Multi-stage pipeline | LinkedIn, ["Making Your Feed More Relevant, Part I"](https://engineering.linkedin.com/blog/2015/11/making-your-feed-more-relevant--part-i); DoorDash, ["Powering Search & Recommendations at DoorDash"](https://careersatdoordash.com/blog/powering-search-recommendations-at-doordash/) | Get a wide candidate pool, then select a smaller pool |
| Collaborative filtering | Sarwar, Karypis, Konstan, and Riedl, ["Item-Based Collaborative Filtering Recommendation Algorithms"](https://dl.acm.org/doi/10.1145/371920.372071) (WWW 2001) | Item-to-item similarity, calculated as cosine similarity over sessions |
| Session recency | Pinterest, ["Real-time User Signal Serving for Feature Engineering"](https://medium.com/pinterest-engineering/real-time-user-signal-serving-for-feature-engineering-ead9a01e5b); Etsy, ["Leveraging Real-Time User Actions to Personalize Etsy Ads"](https://www.etsy.com/codeascraft/leveraging-real-time-user-actions-to-personalize-etsy-ads) | A higher weight for products in the current session |
| Bandit arms | Etsy, ["Building a Platform for Serving Recommendations at Etsy"](https://www.etsy.com/codeascraft/building-a-platform-for-serving-recommendations-at-etsy) | Arms are product categories, not single products |
| Bandit context and calibration | Spotify Research, ["Calibrated Recommendations with Contextual Bandits on Spotify Homepage"](https://research.atspotify.com/2025/9/calibrated-recommendations-with-contextual-bandits-on-spotify-homepage) | The user's category changes the prior. One category can fill a maximum of 60% of the first positions. |
| Diversity and cold start | Netflix, ["Netflix Recommendations: Beyond the 5 stars"](http://techblog.netflix.com/2012/04/netflix-recommendations-beyond-5-stars.html), [Part 2](http://techblog.netflix.com/2012/06/netflix-recommendations-beyond-5-stars.html) | MMR diversity re-ranking. Popularity for new users. |
| Shared embeddings | Pinterest, [ItemSage: Learning Product Embeddings for Shopping Recommendations](https://arxiv.org/abs/2205.11728) (KDD 2022) | One embedding index for search and for product similarity |
| Ranking model | Airbnb, [Applying Deep Learning to Airbnb Search](https://arxiv.org/pdf/1810.09591) (arXiv:1810.09591) | Pairwise training objective for the ranking model |
| Off-policy evaluation | Criteo, ["Offline A/B Testing for Recommender Systems"](https://arxiv.org/pdf/1801.07030); Spotify Research, [counterfactual evaluation](https://research.atspotify.com/publications/towards-a-fair-marketplace-counterfactual-evaluation-of-the-trade-off-between-relevance-fairness-satisfaction-in-recommendation-systems) | The IPS reward estimate in the evaluation |
| Offline evaluation limits | [A Survey of Real-World Recommender Systems](https://arxiv.org/abs/2509.06002) (arXiv:2509.06002) | Offline data cannot measure the value of products that users did not see |

## Background reading

These surveys give context for future work. The project does not implement their methods.

- [A Survey on Generative Recommendation: Data, Model, and Tasks](https://arxiv.org/abs/2510.27157) (arXiv:2510.27157)
- [Large Language Model Enhanced Recommender Systems: A Survey](https://arxiv.org/abs/2412.13432) (arXiv:2412.13432)
- [A Survey on LLM-powered Agents for Recommender Systems](https://arxiv.org/abs/2502.10050) (arXiv:2502.10050)

## Data

- [WANDS: Wayfair ANnotation DataSet](https://github.com/wayfair/WANDS): the product catalog, queries, and relevance labels
- [CLICKSTREAM.md](https://github.com/avantiwhenever/WANDS/blob/main/CLICKSTREAM.md): the method for the synthetic clickstream
