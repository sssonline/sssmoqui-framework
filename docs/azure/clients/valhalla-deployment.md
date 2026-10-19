# Valhalla — Azure Deployment Plan

Follows `docs/azure/AZURE_DEPLOY_GUIDE.md`. This file records only this client's choices and what's still open.
Every command and portal step of the actual install is in `valhalla-install-log.md`.

## Decisions so far

| # | Decision | Choice | Confidence |
|---|---|---|---|
| D1 | DB engine | **PostgreSQL 18** (Azure GA; local Docker `postgres:18` via `cleanPostgres.sh`) | Decided (upstream Moqui lean, closer to DB2) |
| D2 | DB hosting | Azure Database for PostgreSQL Flexible Server, **Burstable B2s** to start; General Purpose D2ds only if CPU credits run low | Firm (start small, scale on evidence, guide §2) |
| D3 | Go-live data | GL balances + opening balances | **Unsure.** Confirm with client whether open AR/AP/inventory come over |
| D4 | Exposure | Public internet + TLS | Firm |
| D5 | Volume | ~10 users, < 50 orders/day | Firm |
| D6 | Modules | Sales/Inventory/Purchasing + Accounting/GL/AR/AP (no payroll) | Firm |
| D7 | Recovery | No data loss; downtime flexible → PITR 35 days, geo-redundant, no HA | Firm |
| D8 | Subscription | SSS's Azure subscription | Firm |
| D9 | Run style | JVM + systemd + nginx (no Docker yet) | Firm |
| D10 | Environments | Prod only, off-hours deploys. **Always on** (no VM auto-shutdown, DB never stopped) | Firm |
| D11 | Email | Needed. Recommend Azure Communication Services Email | Pending domain (D14) |
| D12 | Attachments | Used regularly → **Azure Files** (config only, guide §3.6) | Firm, set before go-live |
| D13 | Region / TZ | **West US 3 (Phoenix)**: ~10–15 ms from SoCal, has availability zones, newer SKUs, usually cheaper than West US (N. Calif.). TZ `America/Los_Angeles` | Firm (D2as_v6 and PG B2s both available there) |
| D14 | Domain | **`valhalla.spokanesoftware.com`** (SSS domain). DNS on Cloudflare (registrar GoDaddy): explicit `A` record → VM IP, **DNS only** (grey cloud) to start; there is a proxied wildcard `*.spokanesoftware.com` that redirects to sssonline.com, which the explicit record overrides | Decided 2026-10-19 |

## Estimated monthly cost (West US 3, portal prices Oct 2026, always on)
VM D2as_v6 ~$66 ($0.0908/hr) · OS disk 64 GiB ~$10 · Postgres B2s ~$50 ($49.64) · DB storage 32 GiB ~$4 ($3.84) · public IP ~$4 · backups a few $ · bandwidth ~$0 (100 GB/mo outbound free) → **~$130/mo**.
If it ever has to grow: DB to GP D2ds (+~$85), VM to D4as_v6 (+~$67). A 1-yr VM reservation saves ~35–40% once the size is settled.

## Azure build log (what actually exists)
| Item | Value | Status |
|---|---|---|
| Subscription | SSS | existing |
| Resource group | `rg-aspen-valhalla-prod` (West US 3), tag `client=valhalla` | ✅ created by the VM wizard |
| VM | `vm-aspen-valhalla`, Standard_D2as_v6, Ubuntu Server 24.04.5 LTS, Premium SSD 64 GiB, no auto-shutdown | ✅ 2026-10-19 |
| VM admin login | `aspen`, key `~/.ssh/vm-aspen-valhalla_key.pem` (Ian's Mac; back it up to the SSS password manager). Moqui itself will run as the no-login service account `aspensvc` (guide §3.2) | ✅ |
| Public IP | `20.25.186.24` (static) | ✅ |
| VNet | `vnet-westus3-1`, `172.16.0.0/16`; VM subnet `snetwestus3-1` `172.16.0.0/24` (VM `172.16.0.4`) | ✅ |
| DB subnet | `snet-db` `172.16.1.0/24`, delegated to `Microsoft.DBforPostgreSQL/flexibleServers` | ✅ |
| NSG | SSH (22) allowed only from SSS office IP `70.89.140.153` | ✅ (add an IP here if working from elsewhere) |
| Postgres | `psql-aspen-valhalla.postgres.database.azure.com`, PostgreSQL 18.6, Burstable B2s (2 vCore, 4 GiB), 32 GiB autogrow, 35-day geo-redundant backup, private access in `snet-db`, admin `aspenadmin` (password in SSS password manager) | ✅ 2026-10-19 |
| DB reachability from VM | private DNS → `172.16.1.4`; port 5432 open; TLS `verify-full` chain + hostname OK with the 3-cert bundle (built in `/tmp/ca` on the VM, not yet installed) | ✅ 2026-10-19 |
| VM tools | `postgresql-client-18` (psql/pg_dump 18.6) from the PGDG apt repo | ✅ 2026-10-19 |
| Server parameters | `max_prepared_transactions=100`, `log_min_duration_statement=2000`, `idle_in_transaction_session_timeout=600000`; restarted | ✅ 2026-10-19 |
| App database | DB `aspen` owned by login `aspen_app` (password in SSS password manager); `aspenadmin` granted `aspen_app`; `verify-full` login as `aspen_app` tested | ✅ 2026-10-19 |
| VM base setup | OS patched (no reboot needed), unattended-upgrades on; Temurin JDK 11.0.32.1; nginx 1.24 (default site only), certbot 2.9, fail2ban; service account `aspensvc` (no login); `/opt/aspen/{releases,shared/{conf,log,txlog,sessions}}` 0750; CA bundle at `/opt/aspen/shared/conf/azure-postgres-root-ca.pem`; ufw: deny in except 22/80/443 | ✅ 2026-10-19 |
| Release | `/opt/aspen/releases/2026-10-19_06cbcf0d` (WAR sha256 `da60abafb722…`, 173 MB unpacked, `RELEASE.txt` lists every repo commit, branch `update`), `current` → it; `log`/`txlog`/`sessions` → `shared/` | ✅ 2026-10-19 |
| Service | `/etc/systemd/system/aspen.service` enabled (Temurin 11 path pinned, preflight tested), `/opt/aspen/aspen.env` root 0600 with secrets still blank, `webapp_https_enabled=false` for the tunnel dry run | ⏳ secrets to fill |
| NSG 80/443 | still closed: open once nginx + TLS cert are ready (needs the domain, D14) | ⏳ |
| Skipped for now | Budget alert, Key Vault, VM backup, Azure Files storage account, DNS record | add before go-live as needed (guide §2) |

## Open questions (ask the client or decide internally)
- [x] IT contact: **SSS is Valhalla's IT** (they have no IT staff). So DNS, email sender setup, user accounts and support all land on us
- [ ] D3: exactly what comes over at go-live (GL trial balance as of cutover date? open AR invoices? open AP? inventory on hand + cost?) and from what system/format
- [x] D14: `valhalla.spokanesoftware.com`. Email sender domain still open: spokanesoftware.com mail is on Microsoft 365 (MX `spokanesoftware-com.mail.protection.outlook.com`), so ACS sending from it needs SPF/DKIM records added in Cloudflare alongside M365's
- [ ] Integrations: payment gateway? EDI with any trading partners? bank feeds? (each adds outbound/inbound network rules)
- [x] Printers: no direct printer integration; printing is browser PDF
- [ ] Business hours → maintenance/deploy window (proposal: Sun 2–5am PT)
- [ ] Who at SSS holds production access (SSH keys, Azure RBAC), plus a break-glass account
- [ ] Budget sign-off on ~$220–325/mo

## Pre-go-live work (SSS)
1. [ ] **Postgres readiness**: JDBC jar, confs, `devPostgres.sh`/`cleanPostgres.sh` and dialect fixes are done; full suite passed on Postgres 17 (261 tests). ⏳ Re-run on Postgres 18 after pulling the Simple Report framework changes. Open: 15 numeric `text-find` search fields (see dialect table)
2. [x] `MoquiProductionConfPostgres.xml` + `prodPostgres.sh` created (secrets via env vars). Aligned with the prod credentials card ([Trello](https://trello.com/c/BfaA2YNv)): same env-var mechanism for Saticoy, deploy WAR packs only allowlisted secret-free confs, conf ships inside each release (guide §3.2)
3. [x] Spike: XA on Azure Postgres: `max_prepared_transactions=100` accepted + restart (2026-10-19). Also set `log_min_duration_statement=2000`, `idle_in_transaction_session_timeout=600000`; `require_secure_transport` ON, `timezone` UTC left at defaults
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
| ✅ fixed | `aspen/entity/AspenViewEntities.xml:572` `daysOut` (POAging) | `DATEDIFF` doesn't exist | `(CURRENT_DATE - CAST(OH.ENTRY_DATE AS DATE))`; explicit MySQL branch added too |
| ✅ fixed | `basalt/entity/HumanResourcesViewEntities.xml:1900` | `month()` doesn't exist | `EXTRACT(MONTH FROM ...)` or a `dbConfName` branch |
| ✅ fixed | `basalt/entity/WorkEffortViewEntities.xml:85` | `YEAR()` doesn't exist | `EXTRACT(YEAR FROM ...)` |
| ✅ fixed | `basalt/entity/HumanResourcesViewEntities.xml:2074` `daysSinceLastWorked` | timestamp − timestamp = `interval`, not an integer | Per-DB day number (`CAST AS DATE` / `TO_DAYS` / `DAYS` / `DATEDIFF`); `fromDate` normalized to a Timestamp before it reaches SQL. No callers or tests yet |
| ✅ fixed, passes on Postgres | `aspen/src/test/groovy/AspenBasicFlowTests.groovy:1905` | `INFORMATION_SCHEMA` lookup uses uppercase `'ASSET'`; Postgres stores lowercase | Compare case-insensitively |
| ✅ fixed (Raleigh) | `aspen` `GlJournalACPDetail.checkNumber` | `COALESCE(numeric, '')` | cast to VARCHAR first on Postgres/H2 |
| ✅ fixed (Raleigh) | `mantle-usl` `PaymentMethodTransAndBankAccountCheckAndPayment` | text check number joined to numeric | numeric-cast join alias on Postgres |
| ✅ note only | `mantle-usl` `ProductWithFeaturesAndAssets` | `LISTAGG` doesn't exist | view unused; switch to `STRING_AGG` before using |
| ☐ open | 15 `text-find` search fields on numeric columns (e.g. `aspen/.../Receiving/FindIncomingInventoryShipment.xml` expectedQuantity, `RunAssetsProduced.xml` quantityAccepted, serverPort/udpPort setup finds) | "contains" search becomes `numeric LIKE '%x%'`: `operator does not exist: numeric ~~ unknown` | equals or range-find on those fields |
| ☐ verify | DB2i payroll reports (ACA/NASDA census, payroll time reports) | `EXTRACT(MONTH/YEAR)` now runs on IBM i | run once via `devDb2iPayrollGb.sh` |

Note: callers of `aspen.order.header.POAging` must set `dbConfName` in context (`POAgingReport.xml:27` does). A caller without it falls back to H2 syntax.
For reference, MySQL would have needed far more: 46 `||` string concatenations in basalt/aspen views would silently return 0/1 instead of text, plus wrong mantle-usl invoice aging buckets.
