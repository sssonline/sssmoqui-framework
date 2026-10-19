# Valhalla — Azure Deployment Plan

Follows `docs/azure/AZURE_DEPLOY_GUIDE.md`. This file records only this client's choices and what's still open.

## Decisions so far

| # | Decision | Choice | Confidence |
|---|---|---|---|
| D1 | DB engine | **PostgreSQL** (17 or newest GA on Azure) | Decided (upstream Moqui lean, closer to DB2) |
| D2 | DB hosting | Azure Database for PostgreSQL Flexible Server, General Purpose D2ds | Firm, **unless price forces the pivot ladder** (guide §2) |
| D3 | Go-live data | GL balances + opening balances | **Unsure.** Confirm with client whether open AR/AP/inventory come over |
| D4 | Exposure | Public internet + TLS | Firm |
| D5 | Volume | ~10 users, < 50 orders/day | Firm |
| D6 | Modules | Sales/Inventory/Purchasing + Accounting/GL/AR/AP (no payroll) | Firm |
| D7 | Recovery | No data loss; downtime flexible → PITR 35 days, geo-redundant, no HA | Firm |
| D8 | Subscription | SSS's Azure subscription | Firm |
| D9 | Run style | JVM + systemd + nginx (no Docker yet) | Firm |
| D10 | Environments | Prod only, off-hours deploys | Firm |
| D11 | Email | Needed. Recommend Azure Communication Services Email | Pending domain (D14) |
| D12 | Attachments | Used regularly → **Azure Files** (config only, guide §3.6) | Firm, set before go-live |
| D13 | Region / TZ | **West US 3 (Phoenix)**: ~10–15 ms from SoCal, has availability zones, newer SKUs, usually cheaper than West US (N. Calif.). TZ `America/Los_Angeles` | Recommended. ❓ confirm SKU availability in-region |
| D14 | Domain | Probably an SSS-managed domain (e.g. `<client>.sssonline.com`) | **Unsure** |

## Estimated monthly cost (West US 3, verify in calculator)
VM D4as_v6 ~$135 · OS disk ~$20 · Postgres GP D2ds ~$130 · DB storage/backup ~$15 · Azure Files ~$5 · Backup ~$15 · IP/KV/ACS ~$6 → **~$325 PAYG, ~$220 with 1-yr reservations**.

## Open questions (ask the client or decide internally)
- [x] IT contact: **SSS is Valhalla's IT** (they have no IT staff). So DNS, email sender setup, user accounts and support all land on us
- [ ] D3: exactly what comes over at go-live (GL trial balance as of cutover date? open AR invoices? open AP? inventory on hand + cost?) and from what system/format
- [ ] D14: our subdomain or theirs? Since we're their IT: do we already have access to their domain registrar/DNS? If so, using their domain is easy (we add the A record + ACS SPF/DKIM records ourselves); if not, get the login before setup. Who sends email "from" (e.g. `billing@theirdomain.com`)?
- [ ] Integrations: payment gateway? EDI with any trading partners? bank feeds? (each adds outbound/inbound network rules)
- [x] Printers: no direct printer integration; printing is browser PDF
- [ ] Business hours → maintenance/deploy window (proposal: Sun 2–5am PT)
- [ ] Who at SSS holds production access (SSH keys, Azure RBAC), plus a break-glass account
- [ ] Budget sign-off on ~$220–325/mo

## Pre-go-live work (SSS)
1. [ ] **Postgres readiness**: add the JDBC jar + `devPostgres.sh`, run the Aspen/Basalt test suites on Postgres, fix dialect bugs (`daysOut` fix in progress; see the dialect-risk list)
2. [x] `MoquiProductionConfPostgres.xml` + `prodPostgres.sh` created (secrets via env vars). Aligned with the prod credentials card ([Trello](https://trello.com/c/BfaA2YNv)): same env-var mechanism for Saticoy, deploy WAR packs only allowlisted secret-free confs, conf ships inside each release (guide §3.2)
3. [ ] Spike: XA on Azure Postgres (`max_prepared_transactions`). Otherwise go non-XA
4. [ ] Provision Azure (guide §3.1), with geo-redundant backup + private networking chosen at creation
5. [ ] Release tagging across all repos + build of `moqui-plus-runtime.war`
6. [ ] Bitronix server ID: nothing to set (guide §3.3). Confirm the first start logs `Bitronix transaction manager server ID: aspen-<host>-<hex> (from .../txlog/server-id)`, that `shared/txlog` was not copied from the sim or another instance, and that the ID is unchanged after the next restart
7. [ ] Dry-run install + data load on a throwaway DB, and time it; build the Postgres root CA bundle and confirm `verify-full` connects (guide §3.2)
8. [ ] Azure Files attachments verified (upload/download)
9. [ ] ACS Email set up + domain verified; test password reset & invoice email
10. [ ] Pause unused ServiceJobs (payroll, SFTP polling, Ziret demos)
11. [ ] Monitoring + alerts + Sentry prod environment
12. [ ] **Restore drill** (PITR → new server → app boots → log in)
13. [ ] Opening balance load + client sign-off on trial balance
14. [ ] Go-live; first week watch logs daily

## Postgres dialect fixes (from code sweep, Oct 2026)
| Status | Where | Problem on Postgres | Fix |
|---|---|---|---|
| ✅ fixed (uncommitted) | `aspen/entity/AspenViewEntities.xml:572` `daysOut` (POAging) | `DATEDIFF` doesn't exist | `(CURRENT_DATE - CAST(OH.ENTRY_DATE AS DATE))`; explicit MySQL branch added too |
| ✅ fixed (uncommitted) | `basalt/entity/HumanResourcesViewEntities.xml:1900` | `month()` doesn't exist | `EXTRACT(MONTH FROM ...)` or a `dbConfName` branch |
| ✅ fixed (uncommitted) | `basalt/entity/WorkEffortViewEntities.xml:85` | `YEAR()` doesn't exist | `EXTRACT(YEAR FROM ...)` |
| ✅ fixed (uncommitted) | `basalt/entity/HumanResourcesViewEntities.xml:2074` `daysSinceLastWorked` | timestamp − timestamp = `interval`, not an integer | `EXTRACT(DAY FROM ...)` (also: the `'${fromDate}'` string interpolation is an SQL-injection risk if user-supplied) |
| ✅ fixed (uncommitted, not yet run in gradle) | `aspen/src/test/groovy/AspenBasicFlowTests.groovy:1905` | `INFORMATION_SCHEMA` lookup uses uppercase `'ASSET'`; Postgres stores lowercase | Compare case-insensitively |

Note: callers of `aspen.order.header.POAging` must set `dbConfName` in context (`POAgingReport.xml:27` does). A caller without it falls back to H2 syntax.
For reference, MySQL would have needed far more: 46 `||` string concatenations in basalt/aspen views would silently return 0/1 instead of text, plus wrong mantle-usl invoice aging buckets.
