# MineFeed v2 launch: Discord

Prepared (hidden from @everyone, visible to Moderator and read-only to Preview):

| What | ID |
|---|---|
| Category `MINEFEED V2` | 1568042885482422314 |
| Forum `#feed` | 1568042886967070761 |
| Forum `#feed-18` (age-restricted) | 1568042888166899795 |
| `#community-requests` (STAFF) | 1568042890129838161 |
| Reports (reuse STAFF `#reports`) | 1525462614044315770 |
| Role `Preview` (sees dev feeds for demos) | 1568042878733787277 |
| Role `MineFeed updates` / `RetroConsole updates` | 1568042879908061279 / 1568042882072449066 |

Production worker vars:

```toml
FEED_FORUM_ID = "1568042886967070761"
FEED_TAGS = '{"rip":"1568042886967070762","help":"1568042886967070763","gg":"1568042886967070764","talk":"1568042886967070765","solved":"1568042886967070766"}'
FEED18_FORUM_ID = "1568042888166899795"
FEED18_TAGS = '{"rip":"1568042888166899796","help":"1568042888166899797","gg":"1568042888166899798","talk":"1568042888166899799","solved":"1568042888166899800","nsfw":"1568042888166899801"}'
APPLICATIONS_CHANNEL_ID = "1568042890129838161"
REPORT_CHANNEL_ID = "1525462614044315770"
MOD_ROLE_ID = "1567664207342604449"
```

On launch day:
1. Deploy the production worker + D1 with the vars above.
2. Bot `.env`: `WORKER_URL` = production worker, add category 1568042885482422314 (and the production communities category) to `FEED_CATEGORY_IDS`.
3. `#feed` / `#feed-18`: remove the @everyone "View" deny (category and both forums); keep Preview overwrites or drop them.
4. Retire v1 `#live-feed` (archive or delete) and point the #welcome links to `#feed`.
