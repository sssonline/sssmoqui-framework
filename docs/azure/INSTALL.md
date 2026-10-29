# Aspen on Azure — Installation Runbook

Step-by-step install of a new Aspen production server on Azure: one Ubuntu VM running Aspen behind nginx, plus Azure Database for PostgreSQL. A person or an agent should be able to follow it top to bottom.

- **Why** each choice was made: [`AZURE_DEPLOY_GUIDE.md`](AZURE_DEPLOY_GUIDE.md).
- **Client decisions and resource list**: `clients/<client>-deployment.md` (first client: [`clients/valhalla-deployment.md`](clients/valhalla-deployment.md)).
- **What actually happened** on the first install, command by command: [`clients/valhalla-install-log.md`](clients/valhalla-install-log.md).

Status per phase: ✅ **verified** = done this way on Valhalla (Oct 2026) and working · ⏳ **drafted** = written from docs/research, not yet run; verify and update this file when you do it.

**Rules for whoever runs this**
- Never paste a password, key or token into chat, a ticket, a doc or a commit. Secrets go straight into the SSS password manager and are typed only where a step says so.
- Run each **Verify** before moving on. If a result differs, stop and fix it; the **Pitfall** notes cover everything that went wrong on Valhalla.
- Append every command you run and its outcome to `clients/<client>-install-log.md`, and fix this runbook if a step was wrong.

---

## 0. Inputs

Decide these first and record them in `clients/<client>-deployment.md`.

| Placeholder | Meaning | Valhalla value |
|---|---|---|
| `<client>` | short lowercase client name | `valhalla` |
| `<region>` | Azure region nearest the client, with availability zones | `West US 3` |
| `<rg>` | resource group | `rg-aspen-valhalla-prod` |
| `<vm>` | VM name | `vm-aspen-valhalla` |
| `<admin>` | VM admin/SSH login (not the app account) | `aspen` |
| `<vnet-range>` | VNet address space, **not** overlapping the SSS office LAN (`172.16.x.x`) or the client's network | `172.16.0.0/16` (overlaps the office LAN; use e.g. `10.50.0.0/16` next time) |
| `<app-subnet>` / `<db-subnet>` | two `/24`s inside it | `172.16.0.0/24` / `172.16.1.0/24` |
| `<pg>` | Postgres server name (globally unique) | `psql-aspen-valhalla` |
| `<pg-host>` | `<pg>.postgres.database.azure.com` | `psql-aspen-valhalla.postgres.database.azure.com` |
| `<office-ip>` | SSS office public IP for SSH (`curl -s https://api.ipify.org` from the office) | `70.89.140.153` |
| `<vm-ip>` | VM public IP (known after phase 1) | `20.25.186.24` |
| `<domain>` | public hostname | `valhalla.spokanesoftware.com` |
| `<tz>` | client IANA time zone | `America/Los_Angeles` |

Secrets you will create (all go into the SSS password manager, named per client):
`<client> Postgres aspenadmin`, `<client> Postgres aspen_app`, `<client> Aspen crypt pass — NEVER CHANGE`, `<client> Aspen admin`, the VM SSH key file.

Prerequisites in Azure (once per subscription): **Subscriptions → (subscription) → Settings → Resource providers** → register `Microsoft.Insights`, `Microsoft.AlertsManagement`, `Microsoft.OperationalInsights` and `Microsoft.Communication` (needs Owner/Contributor **on the subscription**; Contributor on only the resource group gets *"does not have authorization to perform action 'microsoft.insights/register/action'"*, so have a subscription owner do it while setting up access). Without them, creating an action group fails with *"The subscription is not registered to use namespace 'microsoft.insights'"* (hit on Valhalla), and email/Application Insights fail the same way. `Microsoft.Compute`, `Microsoft.Network`, `Microsoft.Storage` and `Microsoft.DBforPostgreSQL` were registered automatically by the portal wizards.

Prerequisites on the build machine (dev Mac): the moqui checkout on branch `update` with all components, JDK 11 as `JAVA_HOME`, Docker/Colima running for local Postgres tests.

---

## 1. Create the VM ✅ (Azure portal)

**Virtual machines → + Create → Azure virtual machine**

- **Basics**: subscription SSS · Resource group **Create new** `<rg>` · name `<vm>` · region `<region>` · no infrastructure redundancy · security type Trusted launch · image **Ubuntu Server 24.04 LTS – x64 Gen2** (publisher Canonical; not "Pro", not "Minimal") · size **Standard_D2as_v6** (2 vCPU, 8 GiB; not B-series) · authentication **SSH public key**, username `<admin>`, key source **Generate new key pair**, key name `<vm>_key` · public inbound ports: **SSH (22) only**.
- **Disks**: OS disk **64 GiB**, **Premium SSD (LRS)**, tick *Delete with VM*.
- **Networking**: **Virtual network → Create new**, address space `<vnet-range>`, subnet `<app-subnet>`. Public IP: new. Tick *Delete public IP and NIC when VM is deleted*.
- **Management**: **Auto-shutdown off** (Aspen runs 24/7). Backup off for now.
- **Tags**: `client=<client>`.
- **Review + create**: price should be about **$0.09/hr** for D2as_v6. **Create**, then **Download private key**.

On the Mac:
```bash
mkdir -p ~/.ssh && mv ~/Downloads/<vm>_key.pem ~/.ssh/ && chmod 600 ~/.ssh/<vm>_key.pem
```
Also store the key file in the SSS password manager (it can't be downloaded again).

Add an SSH alias to `~/.ssh/config` (above any `Host *` block):
```
# <client> production Aspen VM (Azure <region>, <vm>)
Host <client>
  HostName <vm-ip>
  User <admin>
  IdentityFile ~/.ssh/<vm>_key.pem
  IdentitiesOnly yes
  ServerAliveInterval 30
  ServerAliveCountMax 6
```

## 2. Lock SSH to the office ✅ (portal)

VM → **Networking → Network settings** → inbound rule **SSH** → Source **IP Addresses** `<office-ip>` → **Save**. (Add more IPs comma-separated; this NSG rule is the only place SSH source IPs are restricted.)

**Verify** (from the office):
```bash
ssh -o StrictHostKeyChecking=accept-new <client> 'hostname; lsb_release -ds; nproc; free -h | head -2; timedatectl | grep "Time zone"'
# expect: <vm>, Ubuntu 24.04.x LTS, 2, ~7.7Gi, Etc/UTC
ssh <client> "ip -4 -o addr show eth0 | awk '{print \$4}'"     # the VM's address in <app-subnet>
```

## 3. Database subnet ✅ (portal)

**Virtual networks → (the VM's VNet) → Subnets → + Subnet**: name `snet-db`, range `<db-subnet>`, **Subnet delegation `Microsoft.DBforPostgreSQL/flexibleServers`**, everything else default → **Add**.

**Verify**: the Subnets list shows `snet-db` with *Delegated to* `Microsoft.DBforPostgreSQL/flexibleServers`.
> Pitfall: check the VNet's actual address space on its Overview first; the wizard's default isn't always `10.0.0.0/16`.

## 4. Create the Postgres server ✅ (portal)

**Azure Database for PostgreSQL flexible servers → + Create → Flexible server**

- **Basics**: `<rg>` · server name `<pg>` · region `<region>` · **PostgreSQL 18** (newest GA; never a Preview version) · workload type Development → **Configure server**: compute tier **Burstable**, size **Standard_B2s** (2 vCore, 4 GiB; not B1ms: its ~50 connections are below Moqui's pool of 90) · storage **Premium SSD 32 GiB**, **storage autogrow on** · high availability **Disabled** · backup retention **35 days** · **Geo-redundant backup Enabled** ⚠️ creation-only · **Save**. Availability zone no preference. Authentication **PostgreSQL authentication only**, admin `aspenadmin`, password generated → password manager.
- **Networking**: **Private access (VNet Integration)** ⚠️ creation-only · VNet = the VM's · subnet `snet-db` · private DNS zone **(New)**.
- **Tags**: `client=<client>`.
- **Review + create**: about **$53/mo** (compute ~$50 + storage 32 × $0.12). Bandwidth can stay blank (VNet traffic is free, 100 GB/mo outbound free). **Create** (5–10 min).

Then **Settings → Server parameters**:

| Parameter | Value |
|---|---|
| `max_prepared_transactions` | `100` (Moqui uses XA transactions; static, needs restart) |
| `log_min_duration_statement` | `2000` |
| `idle_in_transaction_session_timeout` | `600000` |
| `require_secure_transport` | leave `ON` |
| `timezone` | leave `UTC` |

**Save** → **Overview → Restart**.

Keep the local test database on the same major version: `PG_IMAGE` in `cleanPostgres.sh`.

## 5. VM base setup ✅ (on the VM)

`ssh <client>`, then:

```bash
export DEBIAN_FRONTEND=noninteractive
sudo -E apt-get update && sudo -E apt-get -y upgrade
sudo -E apt-get -y install nginx certbot python3-certbot-nginx unzip fail2ban unattended-upgrades cifs-utils

# Postgres client tools matching the server (Ubuntu's own client is 16; pg_dump must be >= the server version)
sudo -E apt-get -y install postgresql-common
sudo /usr/share/postgresql-common/pgdg/apt.postgresql.org.sh -y
sudo -E apt-get -y install postgresql-client-18

# Java: Temurin 11 JDK (what Aspen's update branch targets; 17/21 need extra JVM flags and are untested)
sudo install -d -m 0755 /etc/apt/keyrings
curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo gpg --dearmor --yes -o /etc/apt/keyrings/adoptium.gpg
echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb noble main" \
  | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo -E apt-get update && sudo -E apt-get -y install temurin-11-jdk

# service account that runs Aspen (no login, no sudo) and the directory layout
sudo useradd -r -m -d /opt/aspen -s /usr/sbin/nologin aspensvc
sudo install -d -o aspensvc -g aspensvc -m 0750 /opt/aspen/releases /opt/aspen/shared /opt/aspen/shared/conf \
  /opt/aspen/shared/log /opt/aspen/shared/txlog /opt/aspen/shared/sessions

# firewall: only 22/80/443 in. Moqui's port 8080 listens on all interfaces, so it must stay closed here.
sudo ufw default deny incoming && sudo ufw default allow outgoing
sudo ufw allow 22/tcp && sudo ufw allow 80/tcp && sudo ufw allow 443/tcp
sudo ufw --force enable
```

**Verify**:
```bash
/usr/lib/jvm/temurin-11-jdk-amd64/bin/java -version 2>&1 | head -1      # openjdk version "11.0.x"
psql --version                                                          # 18.x
sudo ufw status                                                          # 22, 80, 443 ALLOW; active
systemctl is-enabled unattended-upgrades; systemctl is-active nginx fail2ban
[ -f /var/run/reboot-required ] && echo "reboot needed" || echo "no reboot needed"
```
> The `debconf: unable to initialize frontend` lines during apt are harmless.
> `/opt/aspen` is 0750: the admin user needs `sudo` to look inside. That's intended.

## 6. Database connectivity and TLS bundle ✅ (on the VM)

```bash
H=<pg-host>
getent hosts $H                                   # a private address in <db-subnet>
timeout 5 bash -c "</dev/tcp/$H/5432" && echo "5432 reachable"

# Azure Postgres root CA bundle (check the current list at
# https://learn.microsoft.com/en-us/azure/postgresql/security/security-tls-how-to-connect)
mkdir -p /tmp/ca && cd /tmp/ca
curl -fsSLO https://cacerts.digicert.com/DigiCertGlobalRootG2.crt.pem
curl -fsSL "https://www.microsoft.com/pkiops/certs/Microsoft%20RSA%20Root%20Certificate%20Authority%202017.crt" \
  | openssl x509 -inform der -out MicrosoftRSARoot2017.crt.pem
curl -fsSLO https://cacerts.digicert.com/DigiCertGlobalRootCA.crt.pem
cat DigiCertGlobalRootG2.crt.pem MicrosoftRSARoot2017.crt.pem DigiCertGlobalRootCA.crt.pem > azure-postgres-root-ca.pem
echo | openssl s_client -starttls postgres -connect $H:5432 -servername $H -verify_hostname $H \
  -CAfile azure-postgres-root-ca.pem 2>/dev/null | grep "Verify return code"          # must be 0 (ok)

# copy for Aspen (service account) and a readable copy for admin psql sessions
sudo install -o aspensvc -g aspensvc -m 0644 azure-postgres-root-ca.pem /opt/aspen/shared/conf/azure-postgres-root-ca.pem
sudo install -o root -g root -m 0644 azure-postgres-root-ca.pem /etc/ssl/certs/azure-postgres-root-ca.pem
```

## 7. Create the app database and login ✅ (on the VM, interactive)

Generate the app password on the Mac (`openssl rand -base64 24`) and save it as `<client> Postgres aspen_app`. Then on the VM (asks for the `aspenadmin` password):

```bash
psql "host=<pg-host> dbname=postgres user=aspenadmin sslmode=verify-full sslrootcert=/etc/ssl/certs/azure-postgres-root-ca.pem"
```
```sql
CREATE ROLE aspen_app LOGIN;
\password aspen_app
GRANT aspen_app TO aspenadmin;
CREATE DATABASE aspen OWNER aspen_app;
SHOW max_prepared_transactions;            -- 100
\c aspen
ALTER SCHEMA public OWNER TO aspen_app;
\dn+ public                                -- Owner: aspen_app
\q
```
> Pitfall: on Azure, `public` is owned by `azure_pg_admin` and owning the database is **not** enough. Skip `ALTER SCHEMA` and Moqui's first start fails with 644 × `permission denied for schema public`, then `relation "public.localized_message" does not exist`.
> `GRANT aspen_app TO aspenadmin` is needed because Azure's admin isn't a superuser.

**Verify** (aspen_app password):
```bash
psql "host=<pg-host> dbname=aspen user=aspen_app sslmode=verify-full sslrootcert=/etc/ssl/certs/azure-postgres-root-ca.pem" \
  -c "select current_user, current_database(), version();"            # aspen_app | aspen | PostgreSQL 18.x
```

## 8. Build and stage the release ✅ (Mac → VM)

On the Mac, from the moqui root, with every repo on `update`, pulled, and the Postgres suite green:
```bash
gradle cleanPullCompTest -Pconf=conf/MoquiDevConfPostgres.xml     # local Postgres 18, must pass
./gradlew cleanAllButDb build addRuntime                           # -> moqui-plus-runtime.war (~100 MB)
```
> Pitfall: `gitPullAll` only *logs* a failed pull (e.g. uncommitted local edits overlapping incoming changes) and carries on. Before trusting a build, check every repo is clean and not behind: `for r in . runtime runtime/component/*/; do echo "$r $(git -C $r status --porcelain | wc -l) dirty, behind $(git -C $r rev-list --count HEAD..@{u})"; done`.

Check the WAR (expect only the allowlisted confs, the Postgres driver, and no dev data):
```bash
unzip -l moqui-plus-runtime.war | grep -oE "runtime/conf/[^ ]+"              # MoquiProductionConfPostgres.xml, MoquiProductionConf.xml
unzip -l moqui-plus-runtime.war | grep -c "WEB-INF/lib/postgresql-"          # 1
unzip -l moqui-plus-runtime.war | grep -cE "runtime/db/h2/|runtime/component/aspendev/|runtime/txlog/"   # 0
```

Release name = `<date>_<moqui-root-short-sha>`. Write a manifest and upload:
```bash
REL=$(date +%Y-%m-%d)_$(git rev-parse --short HEAD)
{ echo "release: $REL"; echo "built: $(date '+%Y-%m-%d %H:%M %Z') by $(git config user.name) from branch $(git branch --show-current)";
  echo "war: moqui-plus-runtime.war $(shasum -a 256 moqui-plus-runtime.war | cut -c1-16)";
  for r in . runtime runtime/component/*/; do [ -d "$r/.git" ] && printf "%-28s %s %s\n" "$(basename $(cd $r; pwd))" "$(git -C $r rev-parse --short HEAD)" "$(git -C $r branch --show-current)"; done; } > RELEASE.txt
scp moqui-plus-runtime.war RELEASE.txt <client>:/tmp/
```

On the VM (`REL` = the same release name):
```bash
REL=<release-name>; R=/opt/aspen/releases/$REL
sudo -u aspensvc mkdir -p $R
sudo unzip -q -o /tmp/moqui-plus-runtime.war -d $R
sudo install -m 0644 /tmp/RELEASE.txt $R/RELEASE.txt
for d in log txlog sessions; do sudo rm -rf $R/runtime/$d; sudo ln -sfn /opt/aspen/shared/$d $R/runtime/$d; done
sudo chown -R aspensvc:aspensvc $R
sudo -u aspensvc ln -sfn $R /opt/aspen/current
rm -f /tmp/moqui-plus-runtime.war /tmp/RELEASE.txt
```
**Verify**: `sudo ls -la /opt/aspen/current/runtime` shows `log`, `txlog`, `sessions` as symlinks into `/opt/aspen/shared`, and `conf` containing only the two allowlisted confs.

## 9. systemd service and environment file ✅ (on the VM)

`/etc/systemd/system/aspen.service` (exact Valhalla content):
```
[Unit]
Description=Aspen (Moqui) application server
After=network-online.target
Wants=network-online.target

[Service]
User=aspensvc
WorkingDirectory=/opt/aspen/current
EnvironmentFile=/opt/aspen/aspen.env
# refuse to start if a secret is missing (a blank crypt pass would otherwise only fail at first encrypt/decrypt)
ExecStartPre=/bin/bash -c 'for v in entity_ds_host entity_ds_user entity_ds_password entity_ds_crypt_pass default_time_zone database_time_zone; do [ -n "$${!v}" ] || { echo "aspen.env missing $$v"; exit 1; }; done'
ExecStartPre=/usr/bin/test -r /opt/aspen/shared/conf/azure-postgres-root-ca.pem
ExecStart=/usr/lib/jvm/temurin-11-jdk-amd64/bin/java -server -Xms2g -Xmx4g -XX:+UseG1GC -XX:-OmitStackTraceInFastThrow -Djava.awt.headless=true \
  -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/opt/aspen/shared/log \
  -cp . MoquiStart port=8080 conf=conf/MoquiProductionConfPostgres.xml
Restart=on-failure
RestartSec=10
TimeoutStopSec=120
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
```
> Pitfall: keep the `$$` in the preflight line. systemd expands `${...}` itself; with a single `$` the check reaches bash empty and refuses every start.
> Heap ≈ half the VM's RAM: `-Xms2g -Xmx4g` on 8 GiB; `-Xms4g -Xmx8g` after a resize to D4as_v6.

`/opt/aspen/aspen.env` (root:root 0600; systemd reads it, the app account can't). Start with the two secrets blank:
```
entity_ds_host=<pg-host>
entity_ds_database=aspen
entity_ds_user=aspen_app
entity_ds_password=
# fresh per client, NEVER change after data exists: openssl rand -base64 32 (save in the password manager first)
entity_ds_crypt_pass=
default_time_zone=<tz>
database_time_zone=<tz>
instance_purpose=production
webapp_https_enabled=false
```
```bash
sudo chown root:root /opt/aspen/aspen.env && sudo chmod 600 /opt/aspen/aspen.env
sudo systemctl daemon-reload && sudo systemctl enable aspen
```
**Verify the preflight** before adding secrets:
```bash
sudo systemctl start aspen; sudo journalctl -u aspen -n 5 --no-pager    # "aspen.env missing entity_ds_password"
sudo systemctl stop aspen; sudo systemctl reset-failed aspen
```

Then fill the secrets (person with password-manager access): `sudo nano /opt/aspen/aspen.env` → `entity_ds_password=` the aspen_app password, `entity_ds_crypt_pass=` a new `openssl rand -base64 32` value (save it first). No quotes, no spaces around `=`.
```bash
for v in entity_ds_password entity_ds_crypt_pass; do sudo grep -qE "^$v=.+" /opt/aspen/aspen.env && echo "$v: set"; done
```

## 10. First start ✅ (on the VM)

```bash
sudo systemctl start aspen
sudo journalctl -u aspen -f        # Ctrl+C once "Started Server@" appears (~2 min on first start)
```
On an empty database Moqui creates ~644 tables and loads `seed,seed-initial,install` data (no demo data; ~22,000 records), re-initializes, then starts Jetty.

**Verify**:
```bash
L=/opt/aspen/shared/log/moqui.log
sudo grep -E "Initializing DataSource transactional_DS|Bitronix transaction manager server ID|Loaded \[[0-9]+\] records|Moqui Framework initialized" $L | tail -5
#   transactional_DS (postgres) ... serverName:<pg-host> ... stringType:unspecified
#   Loaded [~22133] records (with types from empty-db-load: seed,seed-initial,install)
sudo grep -c "permission denied" $L                         # 0
sudo ss -tnp | grep java | grep -c ":5432"                   # >0 connections to <pg-host>'s private IP
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/status     # 200
```
> Expected noise: 2 ERRORs about `component://aspen/screen/aspen/Production/Run/RunProducts/ConsumeAsset.xml` come from the deprecated *New Available For Sale* screen; ignore them.
> To prove the data lives in Azure Postgres and not on the VM: no `postgresql-1x` server package (`dpkg -l | grep "^ii  postgresql-1"` shows only the client), no `*.mv.db` files under `/opt/aspen`, and the `ss` line above.

## 11. Create the admin account (SSH tunnel, before opening the site) ✅

Port 80/443 are still closed, and the first visitor to an empty system gets a **Create Initial Admin Account** form, so do this through a tunnel:
```bash
ssh -N -L 18080:127.0.0.1:8080 <client>        # leave running
open http://localhost:18080/Login
```
Create an SSS admin login (password → `<client> Aspen admin`), log in, and click through the main menu, Accounting, Sales, Inventory and System → Service Jobs.

## 12. DNS ✅ (Cloudflare, for `*.spokanesoftware.com`)

Cloudflare → `spokanesoftware.com` → **DNS → Records → Add record**: type `A`, name `<client>`, IPv4 `<vm-ip>`, **Proxy status DNS only (grey cloud)**, TTL Auto.
> `*.spokanesoftware.com` is a proxied wildcard that redirects to sssonline.com; the explicit record overrides it. Grey cloud: certbot reaches the VM directly, nginx/Moqui see real client IPs, no 100 s proxy timeout on long reports.

**Verify**: `dig +short A <domain> @1.1.1.1` and `@8.8.8.8` → `<vm-ip>` (your own machine may show the old answer for a few minutes).

## 13. Open the web ports ✅ (portal)

VM → **Networking → Network settings → Create port rule → Inbound port rule**: source **Any**, source ports `*`, destination Any, service Custom, ports **`80,443`**, TCP, Allow, priority `310`, name `allow-web` → **Add**. Never open 8080.
> Port 80 is required: Let's Encrypt validates over http at issue and at every renewal; nginx serves nothing else on 80.

## 14. nginx and the Let's Encrypt certificate ✅ (on the VM)

Port 80 site (ACME challenges + redirect), `/etc/nginx/sites-available/aspen`:
```nginx
# Aspen: port 80 only answers ACME challenges and redirects to https
server {
    listen 80;
    listen [::]:80;
    server_name <domain>;
    location ^~ /.well-known/acme-challenge/ { root /var/www/letsencrypt; default_type text/plain; }
    location / { return 301 https://$host$request_uri; }
}
```
```bash
sudo install -d -m 0755 /var/www/letsencrypt
sudo rm -f /etc/nginx/sites-enabled/default
sudo ln -sfn /etc/nginx/sites-available/aspen /etc/nginx/sites-enabled/aspen
sudo nginx -t && sudo systemctl reload nginx
```

Certificate (test against staging first; staging doesn't count against production limits):
```bash
sudo tail -f /var/log/nginx/access.log | grep --line-buffered acme-challenge     # in a second session: watch validators arrive
sudo certbot certonly --dry-run -v --webroot -w /var/www/letsencrypt -d <domain> --agree-tos --register-unsafely-without-email
sudo certbot certonly --webroot -w /var/www/letsencrypt -d <domain> --agree-tos --register-unsafely-without-email \
  --deploy-hook "systemctl reload nginx"
printf '#!/bin/sh\n# reload nginx so it serves the renewed certificate\nsystemctl reload nginx\n' \
  | sudo tee /etc/letsencrypt/renewal-hooks/deploy/reload-nginx && sudo chmod 755 /etc/letsencrypt/renewal-hooks/deploy/reload-nginx
sudo certbot renew --dry-run                     # "all simulated renewals succeeded"
```
> Pitfall (Valhalla, first evening): every attempt failed with `During secondary validation: DNS problem: networking error looking up A/AAAA`. Our side was fine (primary validation and several remote validators fetched the token, Let's Debug clean); some Let's Encrypt remote validators couldn't resolve via Cloudflare. It cleared by the next morning. If it happens: don't burn production attempts (5 failures/hour/hostname), test with `--dry-run`, check the access log to see which validators reach you, retry hours later.
> No account email: Let's Encrypt stopped sending expiry emails in 2025; certificate expiry is covered by monitoring (phase 20).

HTTPS site: append to `/etc/nginx/sites-available/aspen`:
```nginx
map $http_upgrade $connection_upgrade { default upgrade; "" close; }
server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name <domain>;

    ssl_certificate     /etc/letsencrypt/live/<domain>/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/<domain>/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_prefer_server_ciphers off;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 1d;

    client_max_body_size 50m;          # attachments / imports
    proxy_read_timeout 600s;           # long reports and exports
    proxy_send_timeout 600s;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-Ssl on;
        proxy_set_header Upgrade $http_upgrade;          # /notws notification websocket
        proxy_set_header Connection $connection_upgrade;
    }
}
```
(No HSTS header here: Moqui already sends `Strict-Transport-Security`; adding it in nginx duplicates it.)
```bash
sudo nginx -t && sudo systemctl reload nginx
```

## 15. Switch Aspen to the public URL ✅ (on the VM)

In `/opt/aspen/aspen.env` set:
```
webapp_https_enabled=true
webapp_http_host=<domain>
webapp_http_port=80
webapp_https_port=443
webapp_client_ip_header=X-Real-IP
```
```bash
sudo systemctl restart aspen          # ~80 s
```
**Verify** (from anywhere):
```bash
curl -s -o /dev/null -w "%{http_code} %{ssl_verify_result}\n" https://<domain>/status         # 200 0
curl -s -o /dev/null -w "%{http_code} %{redirect_url}\n" http://<domain>/Login                # 301 https://<domain>/Login
curl -sI https://<domain>/Login | grep -ic strict-transport                                    # 1
echo | openssl s_client -connect <domain>:443 -servername <domain> 2>/dev/null | grep "Verify return code"   # 0 (ok)
```
The site is live. The tunnel is no longer needed.

---

## 16a. Access ⏳ (portal)
- `<rg>` → **Access control (IAM)** → **+ Add → Add role assignment** → *Privileged administrator roles* → **Contributor** for each SSS person who manages the client's resources (Reader for view-only, e.g. billing). Inherited subscription roles show as *Subscription (Inherited)*.
- Break-glass: **Subscriptions → (subscription) → Access control (IAM)**: at least **two** SSS people hold **Owner**, both with MFA, so one lost login never locks SSS out.
- VM: store `<vm>_key.pem` in the SSS password manager; give each additional admin their own SSH key on the VM (append to `~<admin>/.ssh/authorized_keys`) and their office IP in the NSG SSH rule, so access can be revoked per person.

## 16. Cost budget ⏳ (portal)

**Resource groups → `<rg>` → Cost Management → Budgets → + Add**: name `<client>-monthly`, monthly, amount **$175** (expected ~$130–140), alerts **Actual 80%**, **Actual 100%**, **Forecasted 100%** → SSS email, no action group → **Create**.
Optional: **Cost Management → Cost alerts → Anomaly alerts → + Add** for the subscription.
> Budgets only alert; nothing is ever shut off. Cost data lags 8–24 h.

## 17. Attachments on Azure Files ✅ (portal + VM) — do before anyone uploads a file

Aspen stores uploads in the database by default (`dbresource://mantle/content`), and each attachment record keeps its location forever, so switch before go-live.

Portal: **Storage accounts → + Create**: `<rg>`, name `st<client>aspen` (lowercase, globally unique), region `<region>`, **Standard**, redundancy **GZRS** (or GRS; the DB's backups are geo-redundant, so attachments should survive a region outage too: ZRS only survives a zone failure), **Primary service: Azure Blob Storage or Azure Data Lake Storage Gen 2** (a general-purpose v2 account holds both the `aspen-content` file share and the `db-backups` blob container). ⚠️ Choosing *Azure Files* as the primary service creates a file-only account: on the Advanced tab *Access tier* and *hierarchical namespace* show "not supported", and it can't hold blob containers (seen on Valhalla). *Advanced*: hierarchical namespace off, NFS v3 off, SFTP off, cross-tenant replication off, access tier Hot, Managed Identity for SMB off, **Require encryption in transit for SMB on**. *Security* tab: secure transfer on, anonymous access on containers **off**, **storage account key access on** (the VM mounts with the key), minimum TLS 1.2, copy scope same tenant (or default). *Encryption* tab: Microsoft-managed keys, CMK support blobs and files only, infrastructure encryption off. *Networking*: **Enable public access from selected virtual networks and IP addresses** → add the VM's VNet and **only the VM's subnet** (not `snet-db`, the DB's delegated subnet), which enables the `Microsoft.Storage` service endpoint; add the SSS office IP under address range so the portal can create/browse the share and container. Review summary must show account kind **StorageV2 (general purpose v2)**; verify on the account's Overview after creation. *Advanced*: secure transfer required, minimum TLS 1.2. Tag `client=<client>`. Then **File shares → + File share** (the portal calls it a *classic* file share: the storage-account-based kind, which is what we want): `aspen-content`, tier Transaction optimized, Backup tab: off. The create form has no quota on pay-as-you-go shares (100 TiB ceiling, billed per GiB stored); afterwards open the share → **Edit quota → 100 GiB** as a guardrail; **Containers → + Container**: `db-backups`, private (phase 21). Soft delete for file shares and for blobs/containers: on, 30 days. Storage account → Networking: also add the SSS office IP so the portal can browse the share.
Storage account → **Access keys** → copy key1 (goes only into the credentials file below).

VM:
```bash
SA=st<client>aspen
sudo install -d -m 0700 /etc/smbcredentials
sudo nano /etc/smbcredentials/$SA.cred        # two lines: username=<storage account name> / password=<key1> (keeps the key out of shell history)
sudo chmod 600 /etc/smbcredentials/$SA.cred
sudo install -d -m 0750 -o aspensvc -g aspensvc /mnt/aspen-content
echo "//$SA.file.core.windows.net/aspen-content /mnt/aspen-content cifs nofail,_netdev,vers=3.1.1,credentials=/etc/smbcredentials/$SA.cred,uid=aspensvc,gid=aspensvc,dir_mode=0750,file_mode=0640,serverino,nosharesock,actimeo=30,mfsymlinks 0 0" | sudo tee -a /etc/fstab
sudo systemctl daemon-reload && sudo mount /mnt/aspen-content
sudo -u aspensvc touch /mnt/aspen-content/.write-test && sudo -u aspensvc rm /mnt/aspen-content/.write-test && echo "writable"
```
`aspen.service`: add `RequiresMountsFor=/mnt/aspen-content` under `[Unit]`, and `-Dmantle.content.root=file:/mnt/aspen-content -Dmantle.content.large.root=file:/mnt/aspen-content` to `ExecStart` (Moqui reads these JVM properties before the DB preference). Then `sudo systemctl daemon-reload && sudo systemctl restart aspen`.
Before editing: `nc -zvw3 $SA.file.core.windows.net 445` must succeed, and back up `/etc/fstab` and `aspen.service` (`sudo cp -a <file> <file>.bak-<date>`). Check: `ps -o args= -C java | tr ' ' '\n' | grep mantle.content` shows both flags. (Local `curl http://127.0.0.1:8080/status` returns 302, a redirect to HTTPS; check the public URL instead.)
**Verify** (on a dummy record you delete afterwards; Aspen's Groovy Shell isn't a usable shortcut):
1. Upload an attachment (e.g. on an incoming shipment), then `sudo find /mnt/aspen-content -type f`: the file is there.
2. Download it in the browser: the file is saved and Aspen shows **no** green message. A green message means the download transition lacks `read-only="true"` (fixed in basalt bfa91b535 / aspen 465b680b).
3. Database (read-only; as root with aspen.env's `entity_ds_*` vars): `select content_location from shipment_content where shipment_id = '<id>'` shows `file:/mnt/aspen-content/...`, and `select count(*) from db_resource_file` is 0. The database setting `mantle.content.root` still says `dbresource://...`, which is expected because the JVM flag overrides it.
4. Portal → storage account → File shares → `aspen-content` → Browse shows the file.

## 18. Email ⏳ (portal + Cloudflare + Aspen)

Azure blocks outbound port 25; Aspen sends through Azure Communication Services (ACS) over SMTP 587.

**Collect first** (from whoever runs the sender domain's DNS and mail; nothing needed if sending from `spokanesoftware.com`):
- Sender domain, and whether to send from the root domain or a subdomain (e.g. `mail.<client-domain>`; a subdomain keeps ACS's SPF/DMARC changes away from the client's normal mail).
- Sender address(es) (e.g. `aspen@`, `invoices@`) and a **Reply-To mailbox a human reads**, since replies don't go to ACS.
- Who adds DNS records, and how quickly: 1 TXT (verification), SPF change, 2 DKIM CNAMEs. ACS shows the exact values only after the domain is added in step 1.
- Their mail host (Microsoft 365, Google, other) and **current SPF and DMARC records** (`dig +short TXT <domain>`, `dig +short TXT _dmarc.<domain>`). There must stay exactly one `v=spf1` record.
- Not needed: any mailbox password or M365 admin access. ACS sends on its own.
1. Portal: **Email Communication Services → + Create** in `<rg>` (data location United States) → **Provision domains → Add a custom domain** `spokanesoftware.com` (or the client's domain). Add the **TXT verification** record it shows in Cloudflare → Verify.
2. Add the **SPF** and **DKIM** records it shows (DKIM = two CNAMEs). ⚠️ `spokanesoftware.com` mail runs on Microsoft 365: there must be **one** SPF TXT record. Check it first (`dig +short TXT spokanesoftware.com | grep spf1`); if it already contains `include:spf.protection.outlook.com`, ACS's requirement is likely already met — never add a second `v=spf1` record.
3. **Communication Services → + Create** (same data location) → **Email → Domains → Connect domain**. Under the email domain, **MailFrom addresses**: add the sender (e.g. `aspen@spokanesoftware.com`).
4. SMTP credentials: **Entra ID → App registrations → New** `aspen-<client>-smtp` → **Certificates & secrets → New client secret** (note its expiry; calendar a renewal). On the Communication Services resource: **Access control (IAM)** → grant the app a role that allows sending email, then **SMTP Usernames → Add** (Entra app → username). SMTP host `smtp.azurecomm.net`, port 587, STARTTLS, username = the SMTP username, password = the client secret.
5. Aspen: **System → Email Servers → SYSTEM**: SMTP host/port/STARTTLS/username/password, from address = the MailFrom address.
**Verify**: trigger a password reset to an SSS mailbox; check it arrives and passes SPF/DKIM (view headers).

## 19. Pause unneeded scheduled jobs ⏳ (Aspen)

**System → Service Jobs**: pause every job for modules the client doesn't use (Valhalla: payroll and time-period jobs, iSeries imports, bank SFTP polling, Ziret demo jobs, payment-gateway polling unless a gateway is configured). Keep the framework cleanup jobs (`clean_ArtifactData_daily`, `clean_PrintJobData_daily`, `clean_ServiceJobRun_daily`). Route job-error notifications to SSS.
**Verify**: after a day, System → Service Jobs → job runs show no recurring errors.

## 20. Monitoring and alerts ✅ (portal; Sentry still open)

Needs the providers from the prerequisites registered (a subscription Owner). Tag everything `client=<client>`.

1. **Action group**: Monitor → Alerts → Action groups → + Create: RG `<rg>`, region **Global**, name `ag-aspen-<client>`, display name ≤ 12 chars, notification `sss-email` (Email/SMS/Push/Voice → Email, common alert schema on), no actions. The Action groups list can show "No action groups found" for a few minutes (it reads Resource Graph); open it from the resource group instead. The email shows **Email verification: Pending** until someone clicks the link Azure emails (from `azure-noreply@microsoft.com`); alerts aren't delivered before that. If the address is an M365 group or shared mailbox, check spam/quarantine and that the group accepts external senders.
2. **Log Analytics workspace**: Create a resource → Log Analytics workspace: `log-aspen-<client>`, RG `<rg>`, `<region>`, Pay-as-you-go. Then Settings → Usage and estimated costs → **Daily cap On, 0.5 GB/day**; retention default 30 days. Ignore the "upgrade to AMA" banner and don't use *Connect a data source → Azure virtual machines* (installs the agent, starts paid ingestion).
3. **Application Insights**: `appi-aspen-<client>`, RG `<rg>`, `<region>`, workspace **`log-aspen-<client>`** (not a DefaultWorkspace). It also creates an "Application Insights Smart Detection" action group (emails subscription Monitoring Contributor/Reader on anomalies; Aspen sends no telemetry so it stays quiet; leave it).
4. **Uptime test**: appi → Investigate → Availability → + Add Standard test `<client>-status`: URL `https://<domain>/status`, parse dependent requests off, retries on, SSL certificate validity on + proactive lifetime check 7 days, every 5 min, **2 locations** (West US, Central US; ignore the "5 recommended" warning), GET, timeout 30 s, response code equals 200, alerts enabled. Then Monitor → Alerts → Alert rules → the auto-created `<test>-appi-aspen-<client>` → Edit: condition threshold **failed locations ≥ 2**, add action group `ag-aspen-<client>`, severity 1 → Save (as created it notifies nobody).
5. **VM**: VM → Monitoring → Alerts → recommended alert rules: Percentage CPU > 85, Available Memory (suggested), VM Availability < 1; untick disk IOPS and network; action group `ag-aspen-<client>`. Disk-space-used isn't a platform metric (needs an agent; not set up).
6. **Postgres**: server → Monitoring → Alerts → + Create → Alert rule (or Duplicate the first), action group `ag-aspen-<client>`, auto-resolve on, $0.10/month each:

| Rule | Signal | Condition | Lookback / every | Sev |
|---|---|---|---|---|
| `pg-cpu-high` | CPU percent | Average > 80 | 15 min / 5 min | 2 |
| `pg-storage-80` | Storage percent | Average > 80 | 1 h / 15 min | 1 |
| `pg-cpu-credits-low` | CPU Credits Remaining | Average < 200 (B2s banks up to ~864; low = time to leave Burstable) | 15 min / 5 min | 1 |
| `pg-failed-connections` | Failed Connections | Total > 10 | 15 min / 5 min | 2 |

7. **Verify**: action group → **Test** → sample type *Metric alert – Static threshold* → the email arrives (it shows sample IDs like `11111111-…` / `test-storageAccount`; that's normal). Email verification shows **Verified**.
- Still open: Sentry production DSN/environment for the client.

Cost (estimate, 2026-10-28; confirm in the pricing calculator): about **$5–12/month** total. The availability test is billed per run (~$0.0006–0.0007 each): every 5 min is ~8,600 runs/month per location, so ~$5 per location. Use 1–2 locations, not the default 5. Metric alert rules are ~$0.10/month per monitored series after 10 free, so the 7 rules cost under $1. Action-group emails are free at this volume. Log ingestion stays inside the 5 GB/month free allowance. Don't turn on VM Insights or diagnostic log export unless needed; those are the parts that grow.
No VM backup (decided for Valhalla): the VM is rebuilt from the runbook and a release. Data is in Postgres (PITR) and Azure Files.

## 21. Long-term backups and restore drill ⏳
- Automatic: 35-day point-in-time restore, geo-redundant (already on from phase 4).
- Long-term: monthly `pg_dump -Fc` with `postgresql-client-18` from the VM to Blob storage (cool tier, immutable retention ~7 years for accounting records).
- Restore drill before go-live: portal → Postgres → **Restore** to a new server at a point in time → point a copy of `aspen.env` at it → start Aspen → log in, open an attachment → delete the test server.

---

## Deploying a new release ⏳ (procedure; first release was phase 8)
1. Build and stage the new release exactly as in phase 8 (new `REL` name), **without** repointing `current` yet: skip the `ln -sfn ... /opt/aspen/current` line.
2. Portal → Postgres → **Backup and restore → Backup now** (and note the time).
3. Notify users, then on the VM:
```bash
sudo systemctl stop aspen
sudo -u aspensvc ln -sfn /opt/aspen/releases/<new-REL> /opt/aspen/current
sudo systemctl start aspen && sudo journalctl -u aspen -f
```
4. Smoke test: log in, open an order, print an invoice. Check the log shows the **same** `Bitronix transaction manager server ID` as before.

Rollback: point `current` back at the previous release and restart. Moqui only adds tables/columns, so older code runs on the newer schema. A bad data change → restore the database to the time noted in step 2.

## Operations quick reference
| Task | Command (on the VM) |
|---|---|
| Status / restart | `systemctl status aspen` · `sudo systemctl restart aspen` |
| Logs | `sudo tail -f /opt/aspen/shared/log/moqui.log` · `sudo journalctl -u aspen -f` |
| What's deployed | `readlink /opt/aspen/current` · `sudo cat /opt/aspen/current/RELEASE.txt` |
| Admin psql | `psql "host=<pg-host> dbname=aspen user=aspenadmin sslmode=verify-full sslrootcert=/etc/ssl/certs/azure-postgres-root-ca.pem"` |
| Certificate | `sudo certbot certificates` · `sudo certbot renew --dry-run` |
| Firewall | `sudo ufw status` (and the VM's NSG in the portal) |
| Office IP changed | portal → VM → Networking → SSH rule → add the new IP |
