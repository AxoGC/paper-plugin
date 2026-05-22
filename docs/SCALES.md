# Radar Scales — derivation

`StatsCollector.METRICS[*].scale` defines the "100%" point for each radar
axis. The values are not subjective; they are derived from real player data.

## Data source

Three production survival worlds:

| tag   | path                                  | players |
|-------|---------------------------------------|--------:|
| neo   | `/srv/neo-paper/world/stats`          | 16      |
| paper | `/mnt/paper/world/stats`              | 67      |
| old   | `/mnt/old-paper/_old/world/stats`     | 224     |
| total |                                       | **307** |

Each `<uuid>.json` is a vanilla `world/stats/<uuid>.json` and is read with the
same logic as `StatsCollector.computeFromJson`. `BLOCK_ITEM_KEYS` is
approximated by the union of every `minecraft:mined` key seen across the 307
files (907 distinct block ids) — `mined` entries are blocks by definition, so
this matches `Material.values().filter { isItem && isBlock }` for any block
that has ever been touched on the three servers.

## Activity filter

Players with `play_time < 30 min` are tourists: they joined, looked around,
and never came back. Including them collapses the distribution toward zero
and hides the natural ceiling. After filtering, `n = 188` active players.

## Choice of statistic

The scale for each axis is **p95 of active players**, rounded up to the next
`{1,2,5} × 10^k` tier.

- p100 / p99 are dominated by one or two outlier players (one account has
  ~14k hours of play time); using them would compress everyone else to a
  sliver of the chart.
- p90 is too generous — 1 in 10 active players fills the radar.
- p95 is the standard "tier ceiling": top 5% fill the chart, top 1% overflow
  past 100% (a useful visual signal for "legendary"), and the rest of the
  active community still gets meaningful relative position.

## Empirical distribution

Values for active players (`n = 188`):

| axis             | p50    | p75     | p90     | **p95**   | p99       | max         |
|------------------|-------:|--------:|--------:|----------:|----------:|------------:|
| walk_total_m     | 20,224 |  88,465 | 221,728 |   324,091 |   665,360 |     846,073 |
| play_time        | 28,976 | 112,731 | 314,517 |   505,668 | 1,130,821 |   2,163,943 |
| survival_index   |    359 |     842 |   1,787 |     3,744 |    15,174 |      19,596 |
| blocks_placed    |    878 |   8,633 |  25,229 |    67,684 |   165,606 |     470,912 |
| blocks_broken    |  2,123 |  20,015 |  52,176 |   109,771 |   330,999 |     899,722 |
| kills_total      |    149 |   3,988 |  20,163 |    29,769 |    73,427 |      87,595 |

## Adopted scales

| axis             | raw p95   | scale (tidy)  | previous (subjective) |
|------------------|----------:|--------------:|----------------------:|
| walk_total_m     |   324,091 |       500,000 |                50,000 |
| play_time        |   505,668 |     1,000,000 |               360,000 |
| survival_index   |     3,744 |         5,000 |                 8,000 |
| blocks_placed    |    67,684 |       100,000 |                80,000 |
| blocks_broken    |   109,771 |       200,000 |                80,000 |
| kills_total      |    29,769 |        50,000 |                 5,000 |

## When to re-derive

The protocol carries `percent` over the wire, so changing a scale is a
plugin-only edit — core and web stay untouched. Re-derive when the player
base shifts materially (new server, large playerbase turnover, MC version
that meaningfully changes how a stat accumulates). To re-run, point the
analysis script at the current `world/stats` directories and replace
`SCALES` with the rounded p95s.
