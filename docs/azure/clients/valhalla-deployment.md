# Valhalla — Azure Deployment Plan

Follows `docs/azure/AZURE_DEPLOY_GUIDE.md`. This file records only this client's choices and what's still open.
Every command and portal step of the actual install is in `valhalla-install-log.md`. The clean, reusable procedure is `../INSTALL.md`.

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
| D11 | Email | Needed. Recommend Azure Communication Services Email | Pending: sender domain. If Valhalla's own domain, their IT/DNS owner supplies the INSTALL.md phase 18 "Collect first" items (2026-10-28) |
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
| VNet | `vnet-westus3-1`, `172.16.0.0/16` (⚠️ overlaps the SSS office LAN `172.16.x.x`: no problem over the public IP, blocks a future office↔Azure VPN without rebuilding the DB subnet); VM subnet `snetwestus3-1` `172.16.0.0/24` (VM `172.16.0.4`) | ✅ |
| DB subnet | `snet-db` `172.16.1.0/24`, delegated to `Microsoft.DBforPostgreSQL/flexibleServers` | ✅ |
| NSG | SSH (22) allowed only from SSS office IP `70.89.140.153` | ✅ (add an IP here if working from elsewhere) |
| Postgres | `psql-aspen-valhalla.postgres.database.azure.com`, PostgreSQL 18.6, Burstable B2s (2 vCore, 4 GiB), 32 GiB autogrow, 35-day geo-redundant backup, private access in `snet-db`, admin `aspenadmin` (password in SSS password manager) | ✅ 2026-10-19 |
| DB reachability from VM | private DNS → `172.16.1.4`; port 5432 open; TLS `verify-full` chain + hostname OK with the 3-cert bundle (built in `/tmp/ca` on the VM, not yet installed) | ✅ 2026-10-19 |
| VM tools | `postgresql-client-18` (psql/pg_dump 18.6) from the PGDG apt repo | ✅ 2026-10-19 |
| Server parameters | `max_prepared_transactions=100`, `log_min_duration_statement=2000`, `idle_in_transaction_session_timeout=600000`; restarted | ✅ 2026-10-19 |
| App database | DB `aspen` owned by login `aspen_app` (password in SSS password manager); `aspenadmin` granted `aspen_app`; `verify-full` login as `aspen_app` tested | ✅ 2026-10-19 |
| VM base setup | OS patched (no reboot needed), unattended-upgrades on; Temurin JDK 11.0.32.1; nginx 1.24 (default site only), certbot 2.9, fail2ban; service account `aspensvc` (no login); `/opt/aspen/{releases,shared/{conf,log,txlog,sessions}}` 0750; CA bundle at `/opt/aspen/shared/conf/azure-postgres-root-ca.pem`; ufw: deny in except 22/80/443 | ✅ 2026-10-19 |
| Release | `/opt/aspen/releases/2026-10-19_06cbcf0d` (WAR sha256 `da60abafb722…`, 173 MB unpacked, `RELEASE.txt` lists every repo commit, branch `update`), `current` → it; `log`/`txlog`/`sessions` → `shared/` | ✅ 2026-10-19 |
| Service | `/etc/systemd/system/aspen.service` enabled (Temurin 11 path pinned, preflight tested); `/opt/aspen/aspen.env` root 0600, secrets filled, `webapp_https_enabled=true`, host `valhalla.spokanesoftware.com`, `X-Real-IP` | ✅ 2026-10-20 |
| NSG 80/443 | `allow-web` TCP 80,443 from Any, priority 310 | ✅ 2026-10-19 |
| DNS | Cloudflare `A valhalla.spokanesoftware.com → 20.25.186.24`, DNS only | ✅ 2026-10-19 |
| TLS | Let's Encrypt (YE1) via certbot webroot, expires 2027-01-18, auto-renew (certbot.timer + `renewal-hooks/deploy/reload-nginx`, dry run OK); nginx 443 → 127.0.0.1:8080 | ✅ 2026-10-20 |
| Live URL | **https://valhalla.spokanesoftware.com** (admin account created; SSH alias `valhalla` on Ian's Mac) | ✅ 2026-10-20 |
| Storage account | `stvalhallaaspen`: StorageV2, Standard, GZRS (secondary East US), Hot, VM-subnet + office-IP network rules, soft delete 30 days; for the `aspen-content` file share (attachments) and `db-backups` container (monthly exports) | ✅ 2026-10-28: account, `aspen-content` share (quota 100 GiB), `db-backups` container (private). VM mount + Aspen config pending (CLI) |
| Budget | `valhalla-monthly` $175, alerts actual 80/100%, forecast 100% | ✅ 2026-10-28 |
| Postgres maintenance window | custom: Sunday 02:00 Pacific | ✅ 2026-10-28 |
| Azure access | Ian: Contributor on `rg-aspen-valhalla-prod` (2026-10-28). To do: confirm ≥2 Owners on subscription Spokane_Main (break-glass), MFA on both; per-person VM SSH keys; VM key into the SSS password manager | ⏳ |
| Skipped for now | Key Vault | add later as needed (guide §2). Budget, storage account and DNS are now done |
| Not doing | VM backup (Recovery Services vault) | Decided 2026-10-28: the VM is rebuilt from the runbook and a release. Data lives in Postgres (35-day geo PITR) and Azure Files |
| Monitoring cost | ~$5–12/mo | Estimate 2026-10-28: availability test ~$5/location/mo at 5-min runs (use 1–2 locations); 7 alert rules <$1; emails free; logs within free 5 GB |

## Open questions (ask the client or decide internally)
- [x] IT contact: **SSS is Valhalla's IT** (they have no IT staff). So DNS, email sender setup, user accounts and support all land on us. Exception (2026-10-28): Valhalla's own email domain is handled by a separate IT department; email details are being requested from them
- [ ] D3: exactly what comes over at go-live (GL trial balance as of cutover date? open AR invoices? open AP? inventory on hand + cost?) and from what system/format
- [x] D14: `valhalla.spokanesoftware.com`. Email sender domain still open: spokanesoftware.com mail is on Microsoft 365 (MX `spokanesoftware-com.mail.protection.outlook.com`), so ACS sending from it needs SPF/DKIM records added in Cloudflare alongside M365's
- [ ] Integrations: payment gateway? EDI with any trading partners? bank feeds? (each adds outbound/inbound network rules)
- [x] Printers: no direct printer integration; printing is browser PDF
- [ ] Business hours → maintenance/deploy window (proposal: Sun 2–5am PT)
- [ ] Who at SSS holds production access (SSH keys, Azure RBAC), plus a break-glass account. The VM key currently exists only on Ian's Mac: copy it into the SSS password manager
- [ ] Budget sign-off on ~$130–140/mo (start-small sizing); Azure budget alert at $175 (INSTALL.md phase 16)

## Pre-go-live work (SSS)
Phase numbers refer to `../INSTALL.md`.

1. [x] **Postgres readiness**: confs, JDBC jar, scripts, dialect fixes; full suite green on local Postgres 18 (2026-10-19, after pulling the Simple Report framework changes). Open: 15 numeric `text-find` search fields (dialect table)
2. [x] `MoquiProductionConfPostgres.xml` + `prodPostgres.sh` (secrets via env vars), aligned with the prod credentials card ([Trello](https://trello.com/c/BfaA2YNv)): allowlisted secret-free confs ship inside each release
3. [x] XA on Azure Postgres: `max_prepared_transactions=100` accepted + restart (2026-10-19)
4. [x] Provision Azure: VM, DB subnet, Postgres 18 B2s with geo-redundant backup + private access (phases 1–4, 2026-10-19)
5. [x] First release built and deployed: `2026-10-19_06cbcf0d` with `RELEASE.txt` (phase 8). Release **tags** across repos still to do before the next release
6. [x] Bitronix server ID `aspen-vm-aspen-valhalla-3873d455`, unchanged across 5 starts; `shared/txlog` is the VM's own
7. [x] First start + data load (105 s), TLS `verify-full` to Postgres, admin account, HTTPS live at https://valhalla.spokanesoftware.com (phases 5–15, 2026-10-19/20)
8. [x] Budget alert (phase 16)
9. [ ] Azure Files attachments, verified with an upload/download, **before any user uploads** (phase 17)
10. [ ] ACS Email + spokanesoftware.com SPF/DKIM; test password reset and an invoice email (phase 18). Decide the sender address
11. [ ] Pause unused ServiceJobs (phase 19)
12. [x] Monitoring + alerts (phase 20, 2026-10-29). [ ] Sentry prod environment
13. [ ] Long-term backups + **restore drill** (phase 21)
14. [ ] Practice release + rollback (INSTALL.md "Deploying a new release")
15. [ ] fail2ban nginx jail for login floods
16. [ ] Valhalla setup data (org, chart of accounts, fiscal periods, users/roles), opening balances (D3), client sign-off on the trial balance
17. [ ] Go-live; watch logs daily for the first week

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
