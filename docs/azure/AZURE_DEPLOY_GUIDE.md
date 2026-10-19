# Aspen on Azure — General Deployment Guide

Reusable for any new Aspen client on Azure. Client-specific choices live in `docs/azure/clients/<client>.md`.
Status: **draft**. ❓ marks items that still need verification in the first dry run.

## 0. What the codebase tells us

| Area | Finding | Source |
|---|---|---|
| Runtime | Moqui runs as one Java process (`java -jar moqui.war conf=...`), started by the prod scripts with `-Xmx8192m` (8 GB heap) and G1GC by default | `prodMySQL.sh`, `prodDb2i.sh` |
| Java | Compiled for Java 11; the Docker image uses `eclipse-temurin:11-jdk`. Dev machines run JDK 23 | `framework/build.gradle:45`, `docker/simple/Dockerfile` |
| DB options already wired | **MySQL** (`MoquiProductionConfMySQL.xml`, JDBC 8.0.33 in `runtime/lib`), **DB2 for i** (`jt400.jar`, `MoquiProductionConfDb2i.xml`), H2 (dev only). Some views and services branch on the DB type | `runtime/conf`, `aspen/entity/AspenViewEntities.xml:573` |
| Transactions | Moqui uses Bitronix **XA** transactions by default (`xa-properties`). Non-XA is possible with `jdbc-uri` | `MoquiDefaultConf.xml:310,466` |
| Existing deploy pattern | `aspendocker.sh` builds the image `sssonline/aspen` (a private Docker Hub repo) and SSHes to a VPS | `aspendocker.sh`, `docker/simple` |
| Reverse proxy / TLS | The compose files put nginx in front of Moqui (TLS ends at nginx) | `docker/moqui-mysql-compose.yml` |
| Search | OpenSearch/Elasticsearch is **optional**. It's only used by the Basalt Search screen and DataDocument indexing | `basalt/screen/basalt/Search.xml` |
| Attachments | Content defaults to `dbresource://mantle/content`, so **uploaded files are stored inside the database** | `mantle-usl/data/MantleInstallData.xml:17`, `basalt/.../ProjectServices.xml:279` |
| Scheduled jobs | ~30 ServiceJobs, including recurring orders, auto-approve, GL summary recalc, past-due emails, gateway polling, SFTP polling (OFX/BAI2) and payroll. These need outbound internet, SMTP, and correct time zones | `*/data/*.xml` |
| Housekeeping | Built-in daily cleanup: `ArtifactHit` kept 90 days, `ServiceJobRun` 30 days, `PrintJob` 7 days | `framework/data/MoquiSetupData.xml:58` |

### ⚠️ Security issues to fix before the first go-live
- DB passwords and `entity_ds_crypt_pass` were committed in plain text in `runtime/conf/MoquiProductionConf*.xml`, and every deploy WAR carried all of `runtime/conf`. Production secrets now come from environment variables (`aspen.env` here, WebSphere environment entries for Saticoy); the Postgres and Saticoy prod confs only hold `${...}` placeholders. `addRuntime` packs only an allowlist of confs into the WAR and fails the build on a literal secret (§5, [Trello card](https://trello.com/c/BfaA2YNv)). The old Saticoy password is still in git history until it's changed. The **crypt-pass can never change once data is encrypted with it**: pick it once, store it in Key Vault plus the SSS password manager. Generate a **fresh crypt-pass per client** (e.g. `openssl rand -base64 32`); never reuse one that has been committed anywhere.
- The `sentry.properties` DSN is committed with `environment=dev`. Set up a production environment and tag for each client.

## 1. Decision points (answer these per client, then record them in the client plan)

| # | Question | Options | Default recommendation |
|---|---|---|---|
| D1 | DB engine | PostgreSQL · MySQL 8.4 · DB2 for i (legacy IBM i clients only) | **PostgreSQL** (see below) |
| D2 | DB hosting | Managed Flexible Server · DB on the app VM | **Managed**. VM-hosted only if cost forces it |
| D3 | Go-live data | Fresh · GL + opening balances · full history migration | GL + opening balances (history migration is its own project) |
| D4 | Exposure | Public + TLS · office/VPN only · public + inbound integrations | Public + TLS, SSH locked to SSS IPs |
| D5 | Volume | orders/day, lines/order, users | Drives §2 and §7 |
| D6 | Modules | Sales/Inv/Purch · Accounting · Payroll · EDI/bank/gateway | Payroll adds PII, bank SFTP, NACHA, and tighter security |
| D7 | Recovery target | Nightly · PITR (≈5 min RPO, hours RTO) · zone-redundant HA (≈0 RPO, minutes RTO, ~2× DB cost) | **PITR + geo-redundant backup** |
| D8 | Subscription | SSS-hosted · client's own subscription | SSS-hosted (one runbook, our access) |
| D9 | Run style | JVM + systemd · Docker compose | **JVM + systemd** until we have CI + a private registry |
| D10 | Environments | Prod only · staging + prod | Prod only, test on dev Postgres. Add staging once there's more than 1 client |
| D11 | Email | Azure Communication Services · SendGrid/Mailgun · client M365 | **ACS Email** |
| D12 | Attachments | In DB · Azure Files · VM data disk | **Azure Files** if they attach regularly |
| D13 | Region / TZ | Closest Azure region with availability zones | SoCal/AZ → West US 3; WA/OR → West US 2 |
| D14 | Domain | Client domain · SSS subdomain | Either works. The email sender domain needs SPF/DKIM wherever it lives |

### D1: PostgreSQL vs MySQL
Moqui treats both as first-class (`postgres` and `mysql8` confs in `MoquiDefaultConf.xml`), and Azure prices them about the same.

**What the Moqui community says:** Moqui's creator, David E. Jones, wrote on the forum that Postgres is what he's "thinking is a good default recommended database for Moqui" ([forum thread](https://forum.moqui.org/t/updating-docker-compose-files-opensearch-compose-plugin-postgres/355)). Upstream ships both `moqui-postgres-compose.yml` and `moqui-acme-postgres.yml` (the production-style compose with nginx + Let's Encrypt is the Postgres one), and Moqui 4.0 (Feb 2026) bumped both Postgres (18.1) and MySQL (9.5). Neither is deprecated. There's no official production recommendation beyond that.

| | PostgreSQL | MySQL 8.4 |
|---|---|---|
| Moqui upstream lean | ✅ creator's suggested default, production compose example | Supported, MySQL compose example |
| Already in our repo | ❌ no prod conf, JDBC jar, or scripts (`MoquiDevConf.xml:21` has a commented hint) | ✅ prod conf, `devMySQL.sh`, backup/restore scripts, JDBC jar |
| Closeness to DB2 | **Closer.** It's strict and ANSI like DB2, with real schemas and transactional DDL | Looser (silent type coercion, different date functions) |
| Complex reporting queries | Better planner, CTEs, partial indexes | Good |
| Moqui XA (on by default, `PGXADataSource`) | Needs `max_prepared_transactions > 0`. ❓ verify it's settable on Azure (static, needs a restart) | Needs the `XA_RECOVER_ADMIN` grant. ❓ verify on Azure |
| Team familiarity | New. Need `devPostgres.sh` + Postgres.app/Docker locally | Devs can run it today |

**Recommendation: PostgreSQL** (decided Oct 2026). It's the upstream lean and closer to how DB2-minded developers think. Being stricter also catches bad SQL in dev instead of silently coercing it in prod. The extra one-time work is small: add the JDBC jar, a prod conf, a dev script, and backup scripts.

> ⚠️ **Nobody tests Aspen on Postgres (or MySQL) today.** Dev runs on H2, and legacy clients run DB2i. Example: `aspen/entity/AspenViewEntities.xml:573` (`daysOut`) emits `DATEDIFF(...)` on Postgres, which doesn't exist there (fix in progress). **Before the first go-live, run the full Aspen/Basalt test suite plus a click-through on Postgres** and fix the dialect gaps.

## 2. Azure infrastructure (baseline for a ~10-user, <50 orders/day client)

Put the VM and the DB **in the same region and the same VNet**.

| Resource | Pick | Why | ~Cost/mo (PAYG)* |
|---|---|---|---|
| Resource group | `rg-aspen-<client>-prod` | Everything in one place, one bill line | – |
| VNet | `/16` with 2 subnets: `snet-app` (VM), `snet-db` (delegated to PostgreSQL Flexible) | DB gets **private access only**. ⚠️ Private vs public networking is picked **at DB creation** | – |
| App VM | **Standard_D4as_v6** (4 vCPU AMD, 16 GiB), Ubuntu 24.04 LTS | One JVM. Startup (Groovy compile, data load) and reports/jobs are CPU-bursty, and 16 GiB fits an 8 GiB heap plus OS and nginx. **Avoid B-series**: Java exhausts its CPU credits. D2as_v6 (2/8) works but is slower at startup and on reports | ~$133 |
| VM OS disk | Premium SSD **P10, 128 GiB** | App, several releases for rollback, logs, temp | ~$20 |
| Database | **Azure Database for PostgreSQL Flexible Server, General Purpose D2ds (2 vCore, 8 GiB)**, PostgreSQL 17 (or newest GA in region) | Steady CPU. Burstable B2ms (~$99) is the budget fallback | ~$125–140 |
| DB storage | **64 GiB, autogrow ON**, IOPS autoscale ON | Years of growth at this volume (§7). Autogrow can't shrink | ~$8 |
| DB backups | **35-day retention, geo-redundant ON**. ⚠️ Geo-redundancy is set **at creation only** | PITR to the second, survives a region outage | ~storage-sized |
| Public IP + DNS | Static Standard IP; `A` record, e.g. `aspen.<client>.com` | TLS via Let's Encrypt | ~$4 |
| NSG | 443/80 from Any; **22 only from SSS office IPs** (or Bastion / JIT) | SSH stays off the internet | – |
| VM backup | Azure Backup, daily, 30 days | Fast rebuild | ~$10–15 |
| Storage account (if D12 = Azure Files) | Standard ZRS file share | Attachments outside the DB | ~$5 |
| Key Vault | DB password, crypt-pass, SMTP creds | Secrets out of git | ~$1 |

**Rough total: ~$300/mo PAYG → ~$200/mo** with 1-yr reservations on the VM + DB (≈35–40% off; Burstable can't be reserved). *From third-party price trackers (Oct 2026, East US). Confirm in the Azure Pricing Calculator for the actual region; West US regions run a little higher.

Cost pivot ladder: (1) reserve 1-yr → (2) DB to Burstable B2ms → (3) VM to D2as_v6 → (4) Postgres on the VM (saves ~$130, but then we own backups).

## 3. Install: OS, Java, DB, Aspen

Default (D9): **plain JVM + systemd, with nginx in front**.

### 3.1 Provision (in this order)
1. Resource group, VNet + subnets, NSG, Key Vault (soft-delete + purge protection).
2. PostgreSQL Flexible Server: **private access in `snet-db`**, **geo-redundant backup ON**, 35-day retention, custom maintenance window (Sunday ~2am client time).
   - Server params: `timezone=UTC` (server default only; pgjdbc sets each session's zone from the JVM, which Moqui sets from `default_time_zone`), `max_prepared_transactions=100` (≥ Moqui pool size, needed for XA, static so restart), `require_secure_transport=ON`, `log_min_duration_statement=2000` (slow-query log), `idle_in_transaction_session_timeout=600000`.
   - Create DB `aspen` (UTF8) and a login `aspen_app` that owns it. ❓ **Spike: confirm `max_prepared_transactions` is settable.** If not, switch the datasource to non-XA `jdbc-uri` mode.
   - Moqui creates tables in the `public` schema by default. Fine for a single-tenant DB.
3. VM: D4as_v6, Ubuntu 24.04, SSH key only.
4. (D12) Storage account + file share (§3.6).

### 3.2 VM setup
```
sudo apt update && sudo apt -y upgrade
sudo apt -y install nginx certbot python3-certbot-nginx unzip postgresql-client fail2ban unattended-upgrades cifs-utils
# Java: Temurin 11 LTS (what the code targets). Temurin 21 does NOT start Moqui as-is: Bitronix's javassist JDBC
# proxies need --add-opens=java.base/java.lang=ALL-UNNAMED on Java 17+ (found in the local Azure emulation, Oct 2026;
# upstream Moqui's java17 branch needs the same flag and was reverted to Java 11). 21 is otherwise untested.
sudo useradd -r -m -d /opt/aspen -s /usr/sbin/nologin aspen
sudo timedatectl set-timezone UTC
```
**Postgres TLS root CA bundle** (the prod conf uses `sslMode=verify-full` with `sslRootCert`). Microsoft's guidance for Java/Linux clients is to combine the Azure root CAs into one PEM ([Microsoft Learn](https://learn.microsoft.com/en-us/azure/postgresql/security/security-tls-how-to-connect)); confirm the current list there before building it:
```
cd /tmp
curl -fsSLO https://cacerts.digicert.com/DigiCertGlobalRootG2.crt.pem
curl -fsSL "https://www.microsoft.com/pkiops/certs/Microsoft%20RSA%20Root%20Certificate%20Authority%202017.crt" \
  | openssl x509 -inform der -out MicrosoftRSARoot2017.crt.pem
curl -fsSLO https://cacerts.digicert.com/DigiCertGlobalRootCA.crt.pem   # G1: still listed for rotation extensions
sudo install -d -o aspen -g aspen /opt/aspen/shared/conf
cat DigiCertGlobalRootG2.crt.pem MicrosoftRSARoot2017.crt.pem DigiCertGlobalRootCA.crt.pem \
  | sudo tee /opt/aspen/shared/conf/azure-postgres-root-ca.pem >/dev/null
sudo chown aspen:aspen /opt/aspen/shared/conf/azure-postgres-root-ca.pem
# verify: must print "Verify return code: 0 (ok)"
openssl s_client -starttls postgres -connect <server>.postgres.database.azure.com:5432 \
  -servername <server>.postgres.database.azure.com -CAfile /opt/aspen/shared/conf/azure-postgres-root-ca.pem </dev/null 2>/dev/null | grep "Verify return code"
```
Microsoft rotates these roots periodically; when they announce one, add the new root to the bundle **before** the cutover date and restart.

Layout. Releases are swappable, while shared state survives deploys:
```
/opt/aspen/
  releases/2026-11-01_prod-2026.11.01/   ← unzipped moqui-plus-runtime.war (includes runtime/conf/MoquiProductionConfPostgres.xml)
  current -> releases/2026-11-01_prod-2026.11.01
  shared/conf/azure-postgres-root-ca.pem  ← Postgres TLS root CA bundle (absolute path, see above)
  shared/log  shared/txlog  shared/sessions
  aspen.env                               ← secrets (chmod 600), loaded by systemd
```
Each release symlinks `runtime/log`, `runtime/txlog`, and `runtime/sessions` → `shared/`. **`runtime/conf` is not linked**: the conf ships inside each release. It has no secrets (they are all in `aspen.env`), and the WAR build refuses a conf with a literal secret, so a release always runs with the conf it was built with and a rollback restores the matching conf. Never hand-edit a release's conf; change `runtime/conf` in git and build a new release.

### 3.3 New conf: `MoquiProductionConfPostgres.xml` (no secrets in it)
Exists: `runtime/conf/MoquiProductionConfPostgres.xml` (secrets blank, supplied by env vars; launched by `prodPostgres.sh`). JDBC jar `runtime/lib/postgresql-42.7.13.jar` is in place. Values come from env vars in `aspen.env`:
`entity_ds_db_conf=postgres`, `entity_ds_host=<server>.postgres.database.azure.com`, `entity_ds_port=5432`, `entity_ds_database=aspen`, `entity_ds_user`, `entity_ds_password`, `entity_ds_crypt_pass`, `instance_purpose=production`, `webapp_http_host=<domain>`, `webapp_https_enabled=true`, `webapp_client_ip_header=X-Real-IP`, `default_time_zone=<client IANA tz>` **and** `database_time_zone=<same zone>`. Keep `entity_on_start_load_types=seed,seed-initial,install`.

The Bitronix transaction manager server ID needs no setup. Bitronix stamps every transaction with it and, at startup and every 60 seconds, recovers the unfinished transactions in its journal that carry it, so it must differ from every other instance and stay with that journal. On first start Moqui generates one (`aspen-<host>-<8 hex digits>`), saves it in `runtime/txlog/server-id` (which is `shared/txlog`, so it survives every release), and reuses it on every later start; the log shows `Bitronix transaction manager server ID: <id> (from .../txlog/server-id)`. Never copy `shared/txlog` to another instance or a rebuilt VM (a fresh VM gets a fresh journal and ID). To pick the ID yourself, add `-Dbitronix.tm.serverId=<id>` (US-ASCII, at most 51 characters) to `ExecStart` (§3.4); it overrides the saved one.

**Postgres settings the conf must keep (each one was found the hard way in local testing):**
- `entity_ds_schema=public`. The framework default is `""`, and Postgres JDBC `getTables()` reads that as "no schema". On every restart after the first, Moqui then thinks no tables exist, tries to recreate all 644, and the error flood deadlocks log4j during startup.
- `stringType="unspecified"` on the datasource `xa-properties`. Moqui binds string condition values (`econdition value="0"`) with `setString()` for any column type (`FieldInfo.java:478`). Postgres rejects `numeric > character varying` unless the driver sends strings untyped. Without it, 53 of 152 Aspen tests failed.
- **Set both** `default_time_zone` **and** `database_time_zone` in `aspen.env`, to the **same** IANA zone (e.g. `America/Los_Angeles`, `America/Denver`). The prod conf defaults both to `America/Los_Angeles`; overriding only one makes the JVM/session zone and the zone Moqui uses to bind timestamps disagree, so stored times drift hours from `CURRENT_DATE` in raw SQL (aging buckets, `daysOut`). Don't remove them either: the framework default `US/Pacific` is a legacy alias that Postgres 17 rejects at connect time.
- `sslMode="verify-full"` + `sslRootCert` (prod): encrypts **and** verifies the server cert chains to an Azure root and matches `entity_ds_host`, so a spoofed/misrouted DB endpoint can't collect the password. `require` would encrypt but accept any certificate. Bundle path: `entity_ds_ssl_root_cert` (default `/opt/aspen/shared/conf/azure-postgres-root-ca.pem`).

Local check before every release: `gradle cleanPullCompTest -Pconf=conf/MoquiDevConfPostgres.xml` (Docker/Colima running). With a Postgres dev conf, `cleanDb` resets the **local** Postgres DB via `cleanPostgres.sh` (always 127.0.0.1, ignores `PG*` env vars) and leaves H2 alone; it refuses any conf whose name contains `Production`.

### 3.4 systemd `/etc/systemd/system/aspen.service`
```
[Service]
User=aspen
WorkingDirectory=/opt/aspen/current
EnvironmentFile=/opt/aspen/aspen.env
# refuse to start if a secret is missing (a blank crypt pass would otherwise only fail at first encrypt/decrypt)
ExecStartPre=/bin/bash -c 'for v in entity_ds_host entity_ds_user entity_ds_password entity_ds_crypt_pass default_time_zone database_time_zone; do [ -n "${!v}" ] || { echo "aspen.env missing $v"; exit 1; }; done'
ExecStartPre=/usr/bin/test -r /opt/aspen/shared/conf/azure-postgres-root-ca.pem
ExecStart=/usr/bin/java -server -Xms4g -Xmx8g -XX:+UseG1GC -XX:-OmitStackTraceInFastThrow -Djava.awt.headless=true \
  -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/opt/aspen/shared/log \
  -cp . MoquiStart port=8080 conf=conf/MoquiProductionConfPostgres.xml
Restart=on-failure
TimeoutStartSec=600
LimitNOFILE=65536
```
⚠️ **Moqui's Jetty binds 8080 on all interfaces** (`MoquiStart.java` sets only the port, never the host), so the VM firewall must close it; otherwise anything that can reach the VM bypasses nginx and can forge `X-Real-IP` (defeats login lockout, pollutes audit/visit data):
```
sudo ufw default deny incoming
sudo ufw allow from <SSS office IP> to any port 22 proto tcp
sudo ufw allow 80/tcp && sudo ufw allow 443/tcp
sudo ufw enable        # 8080 stays closed to everything but loopback (nginx)
sudo ufw status verbose
```
Keep the NSG equally tight (no inbound 8080), so a loosened rule in one place isn't enough on its own. nginx terminates TLS (`certbot --nginx`, auto-renew) and proxies with `X-Real-IP`, `X-Forwarded-Proto https`, and `client_max_body_size 50m`. fail2ban watches nginx for login floods.

### 3.5 First data load
Load `seed,seed-initial,install` (no demo) against the Azure DB from the VM, then client setup (org, chart of accounts, fiscal periods, users), then opening balances (D3). **Do a full dry run first** on a throwaway DB, and time it.

### 3.6 Attachments on Azure Files (D12): config only
`UserFacadeImpl.getPreference()` checks **JVM system properties before the DB** (`framework/src/main/groovy/org/moqui/impl/context/UserFacadeImpl.groovy:389`), so there are no code or data changes:
1. Storage account (Standard, ZRS) → file share `aspen-content` (100 GiB quota to start). Firewall: allow only `snet-app` (service or private endpoint).
2. Mount over SMB 3 via `/etc/fstab` (`cifs`, `uid=aspen,gid=aspen,file_mode=0640,dir_mode=0750`, credentials file chmod 600) at `/mnt/aspen-content`.
3. Add to `ExecStart`: `-Dmantle.content.root=file:/mnt/aspen-content -Dmantle.content.large.root=file:/mnt/aspen-content`.
4. Azure Backup for the file share (daily, 30+ days) + soft delete.
5. ❓ Dry run: upload and download an attachment on an order and a project.

Only **new** uploads go to the new root, because content records store full locations. So set it **before go-live**.

## 4. Backups & disaster recovery

| Layer | How | Restore |
|---|---|---|
| DB (the important one) | Flexible Server automatic backups: **PITR to the second, 35 days, geo-redundant** | Portal → Restore to a **new** server at time X, then repoint `aspen.env` |
| DB long-term | Monthly `pg_dump -Fc` (new `backupPostgres.sh`) → Blob cool tier, immutable, 7-yr retention (accounting records) | `pg_restore` |
| Attachments (Azure Files) | Share snapshots via Azure Backup, daily, 30+ days, soft delete | Restore share/files from snapshot |
| VM | Azure Backup, daily | Restore VM, or rebuild from §3 (only conf + logs are stateful) |
| Secrets | Key Vault + SSS password manager | – |

**Run a restore drill before go-live and quarterly after.** Restore the DB to a new server, start Aspen against it, log in, and open an attachment.

## 5. Updates / release process

Default (D10): prod only, deploy off-hours. The trade-off is that **every release must pass on dev Postgres, not just H2** (new `devPostgres.sh`).

**Pin versions.** Aspen is ~13 git repos. `gitPullAll` grabs HEAD, so a release has to record each repo's commit. Tag them all the same (e.g. `prod-2026.11.01`) and keep a `RELEASE.txt` manifest in the war.

1. **Build** (dev Mac now, GitHub Actions later): check out the tag in every repo → `./gradlew cleanAllButDb build addRuntime` → `moqui-plus-runtime.war`. Tests pass on Postgres.
   - Only an allowlist of `runtime/conf` files goes into the WAR: by default `MoquiProductionConf.xml` (Saticoy) and `MoquiProductionConfPostgres.xml`. Change it per build with `-PwarConfs=A.xml,B.xml`. The build fails if a listed file is missing, if the conf in `MoquiInit.properties` isn't listed, or if a listed conf has a literal password/key (`-PwarConfsAllowSecrets=true` makes that a warning; don't use it for a client release).
   - `addRuntime` builds a production WAR by default: it leaves out the dev-only `runtime/component/aspendev`, the local H2 database (`runtime/db/h2`) and its snapshots (`runtime/db/snapshot`), the dev transaction journal (`runtime/txlog`) and the unused `runtime/elasticsearch`. So `cleanAllButDb` is enough (no H2 backup/restore, nothing to move aside). `-Pproduction=false` puts those folders back (never for a client release).
2. **Upload**: `scp` to `/opt/aspen/releases/`, unzip into a new folder, create the `shared/` symlinks (`log`, `txlog`, `sessions`; not `conf`).
3. **Pre-deploy backup**: on-demand Flexible Server backup, and note the timestamp.
4. **Switch**: notify users → `systemctl stop aspen` → repoint `current` → `systemctl start aspen` → tail the log until it's started → check the log shows `Bitronix transaction manager server ID: <id> (from /opt/aspen/current/runtime/txlog/server-id)` with the same ID as before the switch → smoke test (login, open an order, print an invoice).
5. **Rollback**: repoint `current` back and restart. Moqui only *adds* tables and columns, so old code runs on the newer schema. A bad data migration → PITR to the step-3 time.

New seed data loads automatically at startup (`seed,seed-initial,install`). One-off data upgrade services still need to be run deliberately per the release notes.

OS: `unattended-upgrades` for security patches, plus a monthly reboot in the off-hours window. Azure patches Postgres in its custom maintenance window.

## 6. Monitoring & maintenance
- **Uptime**: Azure Monitor availability test on `https://<domain>/status` → email/Teams alert.
- **VM alerts**: CPU > 85% for 15 min, disk > 80%, heartbeat lost.
- **DB alerts**: CPU > 80%, storage > 80%, failed connections. Review the slow query log monthly.
- **App errors**: Sentry with a production DSN/environment per client. Logs live in `shared/log` (`moqui.log`, `error.log`). The `aspen-debug` skill works on them.
- **Jobs**: System → Service Jobs. Pause jobs for unused modules (payroll, SFTP polling, demo Ziret jobs), and route job-error notifications to SSS.
- **Email (D11)**: Azure blocks outbound port 25. **Azure Communication Services Email** is in our subscription, costs pennies per 1k emails, and does SMTP on 587 with an Entra app credential. Sending from a custom domain needs SPF/DKIM DNS records. Configure it in Moqui under System → Email Servers (`SYSTEM`). Avoid client M365 password relay: Microsoft is retiring basic-auth SMTP, and Moqui's mailer is username/password.

## 7. Data growth & sizing

Rule of thumb: each order touches ~100–200 rows (items, statuses, shipment, inventory issuance, invoice, payment, GL transaction + entries, audit). With indexes that's **~100–250 KB per order**.
- <50 orders/day ≈ 13k/yr → **~1.5–3.5 GB/yr**, ~10–20 GB after 5 years. Small. No archiving needed for many years.
- Postgres note: heavy update/delete tables bloat until autovacuum runs. Azure's defaults are fine at this volume.
- Biggest wildcard: **attachments in the DB**. Move them to Azure Files (§3.6).
- `ArtifactHit` / `ServiceJobRun` / `PrintJob` are already auto-purged.
- `EntityAuditLog` grows forever. It's the first archive candidate if ever needed.

Watch it: alert at 80% DB storage, plus a monthly top-tables query (`pg_total_relation_size` across `pg_stat_user_tables`).
