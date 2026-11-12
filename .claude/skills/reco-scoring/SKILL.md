---
name: reco-scoring
description: Use when implementing or tuning recommendation scoring, actor weighting, or similarity blending (TMDB/Trakt).
---
# Recommendation scoring

Score(title) in [0,100] = 100 * clamp( wG*Genre + wA*Actor + wS*Similar + wT*Trakt + wC*Completion - Penalty )

Default weights: wG .30, wA .25, wS .25, wT .10 (0 if Trakt off), wC .10. Weights live in a config object, never inline.

- **Genre**: cosine similarity between the title's genre vector and the user's taste vector (genre mean of user ratings/100, weighted by completion).
- **Actor**: top 8 billed cast (TMDB `/tv/{id}/aggregate_credits`, `/movie/{id}/credits`). Affinity per actor = shrunk mean of user's 0-100 ratings for their titles: `(n*mean + k*global)/(n+k)`, k=3. Pinned liked actor: +0.25 boost; muted/disliked: score -0.30 per actor (cap -0.6). "Hide disliked" setting hard-filters instead.
- **Similar**: TMDB `/similar` + `/recommendations` hit rate against the user's top-rated titles (rank-decayed).
- **Trakt**: normalized rank from Trakt recommendations, if connected.
- **Completion**: share of started titles finished in that genre/franchise; dropped shows (<25% watched, >30 days idle) lower affinity.
- Exclude already-watched and removed items. Cache TMDB credits per title (7 days).
- Every result returns `reasons: List<Reason>` (top 2 contributors, e.g. "Because you rated Severance 92", "Features Rami Malek") for the explanation UI.
- Cold start (<5 ratings): popularity + genre picks only; show "rate more titles".
- Unit-test with fixed fixtures; assert ordering, boost/penalty caps and filter behavior.
