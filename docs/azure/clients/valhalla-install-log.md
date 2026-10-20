# Valhalla — Azure install log

Chronological record of every step and command for the Valhalla install, so it can be audited or repeated for the next client.
Plan and decisions: `valhalla-deployment.md`. Reusable guide: `../AZURE_DEPLOY_GUIDE.md`.
Secrets are never written here; the log says where they were entered and where they are stored.

Legend: **[Portal]** clicks in portal.azure.com (by Ian) · **[Mac]** commands on Ian's Mac · **[VM]** commands on `vm-aspen-valhalla` (run over SSH as the admin user `aspen`).

---

## 2026-10-19 — Session 1: VM, database, base setup

### Pre-work (local, before Azure)
- **[Mac]** Postgres readiness, all committed and pushed on branch `update` (root, runtime, aspen, basalt, mantle-usl): Postgres dev/prod confs, `devPostgres.sh` / `prodPostgres.sh` / `cleanPostgres.sh`, JDBC `postgresql-42.7.13.jar`, dialect fixes, `build.gradle` `load -Pconf` fix and `cleanDb` Postgres branch. Full suite on local Postgres 17: 261 tests, 0 failures.
- **[Mac]** Azure CLI is not installed; the install is done through the portal by choice (CLI can be added later for repeatability).

### 1. VM (portal)
**[Portal]** Virtual machines → + Create → Azure virtual machine:
- Basics: subscription SSS; resource group **Create new** `rg-aspen-valhalla-prod`; name `vm-aspen-valhalla`; region **West US 3**; no infrastructure redundancy; security type Trusted launch (default); image **Ubuntu Server 24.04 LTS – x64 Gen2** (Canonical, not Pro/Minimal); size **Standard_D2as_v6** (2 vCPU, 8 GiB); auth SSH public key, username `aspen`, key source *Generate new key pair* named `vm-aspen-valhalla_key`; public inbound ports: SSH (22) only.
- Disks: OS disk 64 GiB, Premium SSD (LRS), delete with VM.
- Networking: new VNet (became `vnet-westus3-1`, `172.16.0.0/16`), new subnet (`snetwestus3-1`, `172.16.0.0/24`), new public IP; delete public IP and NIC with VM.
- Management: **auto-shutdown off** (always-on decision); backup off.
- Tags: `client=valhalla`.
- Review + create showed **$0.0908/hr**. Created; private key downloaded.

**[Mac]** store the key:
```
mkdir -p ~/.ssh && mv ~/Downloads/vm-aspen-valhalla_key.pem ~/.ssh/ && chmod 600 ~/.ssh/vm-aspen-valhalla_key.pem
```
Result: public IP `20.25.186.24`, VNet/subnet `vnet-westus3-1/snetwestus3-1`.

### 2. Lock SSH to the office IP
**[Mac]** find the office public IP: `curl -s https://api.ipify.org` → `70.89.140.153`.
**[Portal]** VM → Networking → Network settings → inbound rule **SSH** → Source: IP Addresses, `70.89.140.153` → Save.
**[Mac]** test login (adds the host key):
```
ssh -i ~/.ssh/vm-aspen-valhalla_key.pem -o StrictHostKeyChecking=accept-new aspen@20.25.186.24 \
  'hostname; lsb_release -ds; nproc; free -h | head -2; df -h / | tail -1; timedatectl | grep "Time zone"'
```
Result: `vm-aspen-valhalla`, Ubuntu 24.04.5 LTS, 2 vCPU, 7.7 GiB, 61 GB disk 4% used, time zone UTC.

### 3. Database subnet and Postgres server
**[VM]** confirm the VM subnet: `ip -4 -o addr show eth0` → `172.16.0.4/24`.
**[Portal]** Virtual networks → `vnet-westus3-1` → Subnets → + Subnet: name `snet-db`, start `172.16.1.0`, size `/24`, delegation `Microsoft.DBforPostgreSQL/flexibleServers`. (Address space was `172.16.0.0/16`, not the `10.0.0.0/16` first assumed.)
**[Portal]** Azure Database for PostgreSQL flexible servers → + Create → Flexible server:
- Basics: resource group `rg-aspen-valhalla-prod`; server name `psql-aspen-valhalla`; region West US 3; **PostgreSQL 18** (GA); workload Development → Configure server: **Burstable Standard_B2s** (2 vCore, 4 GiB), Premium SSD **32 GiB**, storage autogrow on, HA disabled, **backup retention 35 days**, **geo-redundant backup enabled**; availability zone no preference; auth PostgreSQL only, admin `aspenadmin` (password generated and saved in the SSS password manager).
- Networking: **Private access (VNet Integration)**, VNet `vnet-westus3-1`, subnet `snet-db`, new private DNS zone.
- Tags: `client=valhalla`. Estimated cost $53.32/mo (compute $49.64 + storage 32 GiB × $0.12); bandwidth section empty (VNet traffic free, 100 GB/mo outbound free).
Result: `psql-aspen-valhalla.postgres.database.azure.com`, PostgreSQL 18.6, B2s, 32 GiB.

**[Mac]** local Postgres switched to 18 to match (`cleanPostgres.sh` `PG_IMAGE=postgres:18`):
```
docker rm -f moqui-postgres && ./cleanPostgres.sh
docker exec moqui-postgres psql -U moqui -d postgres -tAc "show server_version; show max_prepared_transactions;"   # 18.6, 100
```

### 4. Reachability and TLS from the VM
**[VM]**
```
getent hosts psql-aspen-valhalla.postgres.database.azure.com          # 172.16.1.4 (private DNS)
timeout 5 bash -c "</dev/tcp/psql-aspen-valhalla.postgres.database.azure.com/5432" && echo reachable
mkdir -p /tmp/ca && cd /tmp/ca
curl -fsSLO https://cacerts.digicert.com/DigiCertGlobalRootG2.crt.pem
curl -fsSL "https://www.microsoft.com/pkiops/certs/Microsoft%20RSA%20Root%20Certificate%20Authority%202017.crt" | openssl x509 -inform der -out MicrosoftRSARoot2017.crt.pem
curl -fsSLO https://cacerts.digicert.com/DigiCertGlobalRootCA.crt.pem
cat DigiCertGlobalRootG2.crt.pem MicrosoftRSARoot2017.crt.pem DigiCertGlobalRootCA.crt.pem > azure-postgres-root-ca.pem
H=psql-aspen-valhalla.postgres.database.azure.com
echo | openssl s_client -starttls postgres -connect $H:5432 -servername $H -verify_hostname $H -CAfile azure-postgres-root-ca.pem 2>/dev/null | grep "Verify return code"
```
Result: `Verify return code: 0 (ok)`; chain Microsoft TLS G2 RSA CA OCSP 04 → Microsoft TLS RSA Root G2 → DigiCert Global Root G2.

**[VM]** Postgres 18 client tools (Ubuntu's own client is 16, too old for pg_dump against 18):
```
sudo apt-get update && sudo apt-get -y install postgresql-common
sudo /usr/share/postgresql-common/pgdg/apt.postgresql.org.sh -y
sudo apt-get -y install postgresql-client-18
psql --version; pg_dump --version      # 18.6
```

### 5. Server parameters
**[Portal]** `psql-aspen-valhalla` → Settings → Server parameters: `max_prepared_transactions=100` (accepted), `log_min_duration_statement=2000`, `idle_in_transaction_session_timeout=600000`; left `require_secure_transport=ON`, `timezone=UTC` → Save → Overview → Restart.
Result: XA spike closed, no non-XA fallback needed.

### 6. App database and login
**[Mac]** `openssl rand -base64 24` → saved as the `aspen_app` password in the SSS password manager.
**[VM]** (Ian, interactively, admin password typed at the prompt):
```
psql "host=psql-aspen-valhalla.postgres.database.azure.com dbname=postgres user=aspenadmin sslmode=verify-full sslrootcert=/tmp/ca/azure-postgres-root-ca.pem"
CREATE ROLE aspen_app LOGIN;
\password aspen_app
GRANT aspen_app TO aspenadmin;
CREATE DATABASE aspen OWNER aspen_app;
SHOW max_prepared_transactions;      -- 100
\q
psql "host=psql-aspen-valhalla.postgres.database.azure.com dbname=aspen user=aspen_app sslmode=verify-full sslrootcert=/tmp/ca/azure-postgres-root-ca.pem" -c "select current_user, current_database(), version();"
```
Result: `aspen_app | aspen | PostgreSQL 18.6`.

### 7. VM base setup
**[VM]**
```
export DEBIAN_FRONTEND=noninteractive
sudo -E apt-get update && sudo -E apt-get -y upgrade
sudo -E apt-get -y install nginx certbot python3-certbot-nginx unzip fail2ban unattended-upgrades cifs-utils
sudo install -d -m 0755 /etc/apt/keyrings
curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo gpg --dearmor --yes -o /etc/apt/keyrings/adoptium.gpg
echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb noble main" | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo -E apt-get update && sudo -E apt-get -y install temurin-11-jdk
sudo useradd -r -m -d /opt/aspen -s /usr/sbin/nologin aspensvc
sudo install -d -o aspensvc -g aspensvc -m 0750 /opt/aspen/releases /opt/aspen/shared /opt/aspen/shared/conf \
  /opt/aspen/shared/log /opt/aspen/shared/txlog /opt/aspen/shared/sessions
sudo install -o aspensvc -g aspensvc -m 0644 /tmp/ca/azure-postgres-root-ca.pem /opt/aspen/shared/conf/azure-postgres-root-ca.pem
sudo ufw default deny incoming; sudo ufw default allow outgoing
sudo ufw allow 22/tcp; sudo ufw allow 80/tcp; sudo ufw allow 443/tcp
sudo ufw --force enable
```
Result: Temurin 11.0.32.1, nginx 1.24, certbot 2.9, fail2ban active, unattended-upgrades enabled, no reboot required; ufw active (22/80/443 in, everything else denied); only 22 and 80 listening. NSG still allows only SSH from the office.

### 8. First release
**[Mac]** (Ian) build: `./gradlew cleanAllButDb build addRuntime` → `moqui-plus-runtime.war` (98 MB). Checked: WAR confs = `MoquiProductionConfPostgres.xml`, `MoquiProductionConf.xml`; `WEB-INF/lib/postgresql-42.7.13.jar` present; no `runtime/db/h2`, `aspendev`, `txlog`, `log`. Client components `gebbers*` are in the WAR but their data uses custom load types (`seed-gebbersCommon`, `install-gebbersFarms`), so nothing of theirs loads with `seed,seed-initial,install`.
**[Mac]** release manifest + upload:
```
# RELEASE.txt: release name, build time, WAR sha256, and `git rev-parse --short HEAD` + branch for every repo
scp -i ~/.ssh/vm-aspen-valhalla_key.pem moqui-plus-runtime.war RELEASE.txt aspen@20.25.186.24:/tmp/
```
Manifest: release `2026-10-19_06cbcf0d`, WAR sha256 `da60abafb722…`, branch `update`: moqui 06cbcf0d, runtime 8ebd053, SimpleScreens c2e1b5f, aspen 115c2296, basalt 681af2976, expense 81bfbef, gebbers b37ecda, gebberssal c70f79c, gebbersw 99e2a59, mantle-edi 25639b9, mantle-udm 0a78a60, mantle-usl da8e641, moqui-fop 2bd514e, ssscommon acfeec2, ssscrm 528c020.
**[VM]**
```
R=/opt/aspen/releases/2026-10-19_06cbcf0d
sudo -u aspensvc mkdir -p $R
sudo unzip -q -o /tmp/moqui-plus-runtime.war -d $R
sudo install -m 0644 /tmp/RELEASE.txt $R/RELEASE.txt
for d in log txlog sessions; do sudo rm -rf $R/runtime/$d; sudo ln -sfn /opt/aspen/shared/$d $R/runtime/$d; done
sudo chown -R aspensvc:aspensvc $R
sudo -u aspensvc ln -sfn $R /opt/aspen/current
rm -f /tmp/moqui-plus-runtime.war /tmp/RELEASE.txt
```
Result: 173 MB unpacked; `current` → the release.

### 9. systemd service and env file
**[VM]** wrote `/etc/systemd/system/aspen.service` (exact content in the guide §3.4: Temurin 11 path pinned, secrets preflight, CA bundle check, `-Xms2g -Xmx4g`, port 8080, `MoquiProductionConfPostgres.xml`, `Restart=on-failure`, `WantedBy=multi-user.target`) and `/opt/aspen/aspen.env` (template in guide §3.4; host/db/user/TZ filled, password and crypt pass blank, `webapp_https_enabled=false` for the tunnel dry run):
```
sudo chown root:root /opt/aspen/aspen.env && sudo chmod 600 /opt/aspen/aspen.env
sudo systemctl daemon-reload && sudo systemctl enable aspen.service
sudo systemctl start aspen; sudo journalctl -u aspen -n 8 --no-pager     # preflight test
```
Issue found: systemd expands `${...}` in Exec lines, so `${!v}` reached bash empty and the preflight failed every start ("aspen.env missing entity_ds_host"). Fixed by escaping as `$${!v}` / `$$v`:
```
sudo systemctl stop aspen; sudo systemctl reset-failed aspen
sudo sed -i 's/\[ -n "\${!v}" \] || { echo "aspen.env missing \$v"/[ -n "$${!v}" ] || { echo "aspen.env missing $$v"/' /etc/systemd/system/aspen.service
sudo systemctl daemon-reload; sudo systemctl start aspen; sudo journalctl -u aspen -n 4 --no-pager
sudo systemctl stop aspen; sudo systemctl reset-failed aspen
```
Result: preflight correctly reports `aspen.env missing entity_ds_password`; service enabled, inactive.

### 10. Secrets
- **[Mac]** (Ian) `openssl rand -base64 32` → Valhalla crypt pass, saved in the SSS password manager ("NEVER CHANGE").
- **[VM]** (Ian) `sudo nano /opt/aspen/aspen.env` → filled `entity_ds_password` (aspen_app) and `entity_ds_crypt_pass`.
- **[VM]** checked without printing values:
```
for v in entity_ds_password entity_ds_crypt_pass; do sudo grep -qE "^$v=.+" /opt/aspen/aspen.env && echo "$v: set"; done
sudo grep -cE '^entity_ds_(password|crypt_pass)=["\' ]|= ' /opt/aspen/aspen.env      # 0 lines with quotes/spaces
```

### 11. First start (schema + seed/install data load)
**[VM]** 23:25:41 UTC:
```
sudo systemctl start aspen
sudo journalctl -u aspen -f        # watched until initialized
```
- Preflight passed; Bitronix generated and saved server ID `aspen-vm-aspen-valhalla-3873d455`.
- **Failed**: Jetty started on 8080 but webapp init failed (`relation "public.localized_message" does not exist`). Root cause in `/opt/aspen/shared/log/moqui.log`: 644 × `permission denied for schema public`. `aspen_app` owns the `aspen` database but cannot create objects in its `public` schema on Azure Flexible Server, so every `CREATE TABLE` failed; no tables, no data. (The guide's claim that the DB owner gets `public` on PG 15+ was wrong for Azure.)
```
sudo systemctl stop aspen
sudo grep -oE "PSQLException: ERROR: [^\(]{0,120}" /opt/aspen/shared/log/moqui.log | sort | uniq -c | sort -rn | head
```
- Admin psql can't use `/opt/aspen/shared/conf/...` (0750, service account only): first attempt failed with `root certificate file ... does not exist`; used `/tmp/ca/azure-postgres-root-ca.pem`.
- **[VM]** (Ian) fix, as `aspenadmin` on DB `aspen`:
```
psql "host=psql-aspen-valhalla.postgres.database.azure.com dbname=aspen user=aspenadmin sslmode=verify-full sslrootcert=/tmp/ca/azure-postgres-root-ca.pem"
\dn+ public          -- owner azure_pg_admin; privileges azure_pg_admin=UC, PUBLIC=U (no CREATE for anyone else)
ALTER SCHEMA public OWNER TO aspen_app;
\dn+ public          -- owner aspen_app, aspen_app=UC
```
- **[VM]** admin-readable CA copy for future admin psql sessions:
```
sudo install -o root -g root -m 0644 /tmp/ca/azure-postgres-root-ca.pem /etc/ssl/certs/azure-postgres-root-ca.pem
```
- **[VM]** second start, 23:29:38 UTC (old log kept as `moqui-firststart-failed.log`):
```
sudo systemctl reset-failed aspen
sudo -u aspensvc mv /opt/aspen/shared/log/moqui.log /opt/aspen/shared/log/moqui-firststart-failed.log
sudo systemctl start aspen
```
  After 20 s: 0 `permission denied`, 644 `Created table`.
- Result (Moqui log times are Pacific): tables checked/created 24 s, empty-DB data load (`seed,seed-initial,install`, framework default `entity_empty_db_load`) **22,133 records** from 133 files in ~68 s, re-init, **Moqui Framework initialized in 105.5 s**, Jetty started on 8080. Datasource `postgres`, db `aspen`, `stringType:unspecified`, READ_COMMITTED, max pool 90. No demo/test data.
- 2 ERROR lines, pre-existing app bug (not Azure): `aspen/screen/aspen/Sales/Planning/NewAvailableForSale.xml:22,52` reference `component://aspen/.../RunProducts/ConsumeAsset.xml`, deleted in aspen `89d4b650`. **New Available For Sale is a deprecated screen**: no fix; the 2 startup errors are expected until it's removed.
- **[VM]** health:
```
sudo ss -tlnp                                   # only 22 (sshd), 80 (nginx), 8080 (java); no H2 port
curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8080/status    # 200
free -h; ps -o rss=,etime= -C java              # java ~2.2 GB RSS, 5 GB available
```

### 12. Smoke test through an SSH tunnel (no ports opened)
**[Mac]**
```
ssh -i ~/.ssh/vm-aspen-valhalla_key.pem -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -N -L 18080:127.0.0.1:8080 aspen@20.25.186.24
curl -s -o /dev/null -w "%{http_code}" http://localhost:18080/status   # 200
open http://localhost:18080/Login                                       # "Create Initial Admin Account" form (no users yet)
```
- The initial-admin form is claimable by whoever reaches the site first, so 80/443 stay closed in the NSG until the admin account exists.
- **[Browser via tunnel]** (Ian) created the initial admin account (SSS admin login, password in the SSS password manager); main menu, Accounting, Sales, Inventory, System → Service Jobs load. Skipped Sales → Planning → New Available For Sale (deprecated screen, known broken reference).

### 13. Verify Aspen writes to Azure Postgres, not the VM
**[VM]**
```
dpkg -l | grep -E "^ii +(postgresql-[0-9]+ |postgresql |mysql-server|mariadb-server)"   # none: only postgresql-client-18
pgrep -a -f "postgres:|mysqld|h2.*Server"                                               # none
sudo find /opt/aspen -name "*.mv.db" -o -name "*.h2.db"                                 # none; runtime/db = 60 KB Derby/OrientDB config templates
sudo ss -tnp | grep java                     # 5 connections 172.16.0.4 -> 172.16.1.4:5432 (psql-aspen-valhalla in snet-db)
sudo grep -E "Initializing DataSource" /opt/aspen/shared/log/moqui.log   # transactional_DS (postgres), serverName psql-aspen-valhalla..., databaseName aspen
```
- The log's `entity_ds_url=jdbc:h2:...` lines are the framework default property, only read by the H2 database conf; unused with the Postgres datasource.
- DB-side check (as `aspen_app`): `select user_id, username, created_stamp from user_account;` lists the new admin.

### 14. Domain
- Decision: `valhalla.spokanesoftware.com` (SSS domain).
**[Mac]**
```
dig +short NS spokanesoftware.com                 # abdullah/daphne.ns.cloudflare.com (registrar GoDaddy)
dig +short A valhalla.spokanesoftware.com         # Cloudflare IPs: proxied wildcard *.spokanesoftware.com
curl -sI https://valhalla.spokanesoftware.com     # 301 -> https://sssonline.com/ (wildcard redirect)
```
- Plan: Cloudflare `A valhalla 20.25.186.24`, **DNS only** (grey) to start: certbot HTTP-01 straight to the VM, real client IPs for nginx/fail2ban/Moqui lockout, no Cloudflare 100 s request timeout. Proxied (orange) can come later with real-IP header handling.

### 15. DNS record, web ports, nginx :80
- **[Cloudflare]** (Ian) DNS → Add record: `A valhalla 20.25.186.24`, **DNS only** (grey), TTL Auto.
**[Mac]** `dig +short A valhalla.spokanesoftware.com @1.1.1.1` / `@8.8.8.8` → `20.25.186.24` (the Mac's own resolver kept the cached wildcard answer for a few minutes).
- **[Portal]** (Ian) VM → Networking → Network settings → inbound port rule `allow-web`: source Any, TCP `80,443`, Allow, priority 310. Why 80: Let's Encrypt HTTP-01 validation at issue and every renewal, plus http→https redirect; port 80 serves nothing else. (Alternative considered: DNS-01 via a Cloudflare API token on the VM, so 443 only; rejected because that token could edit the whole spokanesoftware.com zone.)
**[VM]** port 80 = ACME challenges + redirect only:
```
sudo install -d -m 0755 /var/www/letsencrypt
sudo tee /etc/nginx/sites-available/aspen     # server :80 for valhalla.spokanesoftware.com:
                                              #   location ^~ /.well-known/acme-challenge/ { root /var/www/letsencrypt; }
                                              #   location / { return 301 https://$host$request_uri; }
sudo rm -f /etc/nginx/sites-enabled/default
sudo ln -sfn /etc/nginx/sites-available/aspen /etc/nginx/sites-enabled/aspen
sudo nginx -t && sudo systemctl reload nginx
```

### 16. Let's Encrypt certificate (in progress)
**[VM]**
```
sudo certbot certonly --webroot -w /var/www/letsencrypt -d valhalla.spokanesoftware.com \
  --agree-tos --register-unsafely-without-email --non-interactive --deploy-hook "systemctl reload nginx"
```
(No account email: Let's Encrypt stopped sending expiry emails in 2025; expiry is watched by monitoring instead.)
- 3 attempts (≈23:43, 23:45, 23:58 UTC) failed identically: `During secondary validation: DNS problem: networking error looking up A/AAAA` (first one: `... looking up CAA`). Primary validation succeeded (resolved and fetched the challenge from `20.25.186.24`).
- Ruled out: CAA (none, NOERROR from Cloudflare NS, 1.1.1.1, 8.8.8.8, 9.9.9.9); DNSSEC (not enabled: no DS at .com, no DNSKEY); challenge path reachable from outside; Let's Debug (letsdebug.net, http-01) 0 problems; LE status all operational. Community threads describe this as an LE-side transient that clears on a later retry.
- Stopped retrying to stay under LE's 5 failed validations/hour/hostname; one retry scheduled for 00:25 UTC.
- 4th attempt (00:40 UTC, 2026-10-20) failed identically. `certbot certonly --dry-run` against **LE staging** fails the same way, so it's systematic, not a blip.
- SSH tunnel dropped once (`Connection reset by peer`); reopened with `-o ServerAliveInterval=30 -o ServerAliveCountMax=6`. Aspen and nginx were unaffected.
- **[VM]** staged the https server block at `/etc/nginx/sites-available/aspen-https.staged` (443 ssl http2, Let's Encrypt cert paths, TLS 1.2/1.3, HSTS, `client_max_body_size 50m`, 600 s proxy timeouts, proxy to `127.0.0.1:8080` with `Host`, `X-Real-IP`, `X-Forwarded-For`, `X-Forwarded-Proto https`, websocket upgrade for `/notws`). Enabled once the certificate exists.

#### 2026-10-20 — certificate diagnosis
- Rechecked: DNS → `20.25.186.24` from 1.1.1.1, 8.8.8.8 and Cloudflare's NS; port 80 301-redirects from the internet; a test file in the challenge folder is fetchable. unboundtest.com (LE's resolver emulator) was down (Cloudflare 525).
- **[VM]** nginx access log shows which LE validators reached the VM:
```
sudo grep -h "acme-challenge" /var/log/nginx/access.log* | awk '{print $4, $1, $9}'
```
  23:45:58 primary + 4 remote perspectives (200); 23:46:23 primary + 2; 23:50:41 primary + 2; 00:40 primary only. So HTTP and port 80 work from all over; **some remote perspectives time out resolving the name at Cloudflare's nameservers** and never reach the VM, and LE's multi-perspective rule needs all but one to succeed. No other ports are involved (HTTP-01 uses inbound 80 only).
- Self-serve test (staging, doesn't count toward production limits):
```
sudo tail -f /var/log/nginx/access.log | grep --line-buffered acme-challenge      # terminal 1
sudo certbot certonly --dry-run -v --webroot -w /var/www/letsencrypt -d valhalla.spokanesoftware.com --agree-tos --register-unsafely-without-email   # terminal 2
sudo grep -E '"(detail|status|hostname|addressUsed|url)"' /var/log/letsencrypt/letsencrypt.log | tail -20
```

#### 2026-10-20 — certificate issued
- **[VM]** (Ian) staging dry run with the access log tailed: all remote perspectives fetched the token (`200` from 66.133.109.36 + 4 AWS IPs at 16:16:31; 23.178.112.104 + 4 at 16:16:53 UTC). Then the production run succeeded: Let's Encrypt `YE1`, `CN=valhalla.spokanesoftware.com`, valid 2026-10-20 15:18 → **2027-01-18**. The previous evening's failures were on the LE/Cloudflare side and cleared overnight.
- The production run was done without `--deploy-hook`, so a global deploy hook was added instead (applies to every renewal regardless of how the cert was requested):
```
printf '#!/bin/sh\nsystemctl reload nginx\n' | sudo tee /etc/letsencrypt/renewal-hooks/deploy/reload-nginx
sudo chmod 755 /etc/letsencrypt/renewal-hooks/deploy/reload-nginx
sudo certbot renew --dry-run          # "all simulated renewals succeeded"; certbot.timer runs twice daily
```

### 17. HTTPS switchover
**[VM]**
```
cat /etc/nginx/sites-available/aspen-https.staged | sudo tee -a /etc/nginx/sites-available/aspen   # enable the 443 block
sudo nginx -t && sudo systemctl reload nginx
sudo rm -f /etc/nginx/sites-available/aspen-https.staged
# /opt/aspen/aspen.env (sed, no secrets printed):
#   webapp_https_enabled=true, webapp_http_host=valhalla.spokanesoftware.com,
#   webapp_http_port=80, webapp_https_port=443, webapp_client_ip_header=X-Real-IP
sudo systemctl restart aspen          # Moqui initialized in 78 s, Jetty up at 82 s
sudo sed -i "/add_header Strict-Transport-Security/d" /etc/nginx/sites-available/aspen && sudo systemctl reload nginx
```
- HSTS was sent twice (nginx and Moqui both add it); removed nginx's copy, Moqui's `max-age=31536000` remains.
**[Mac]** from the internet:
```
curl -s -o /dev/null -w "%{http_code} %{ssl_verify_result}" https://valhalla.spokanesoftware.com/status    # 200 0
curl -s -o /dev/null -w "%{http_code} %{redirect_url}" http://valhalla.spokanesoftware.com/Login          # 301 -> https
echo | openssl s_client -connect valhalla.spokanesoftware.com:443 -servername valhalla.spokanesoftware.com | grep "Verify return code"   # 0 (ok)
```
- Result: **https://valhalla.spokanesoftware.com live** with a valid certificate; login page served (admin already exists). The SSH tunnel is no longer needed.

### 18. SSH shortcut on Ian's Mac
**[Mac]** `~/.ssh/config` (backup `~/.ssh/config.bak-20261020-092509`): added
```
# Valhalla production Aspen VM (Azure West US 3, vm-aspen-valhalla)
Host valhalla
  HostName 20.25.186.24
  User aspen
  IdentityFile ~/.ssh/vm-aspen-valhalla_key.pem
  IdentitiesOnly yes
  ServerAliveInterval 30
  ServerAliveCountMax 6
```
and renamed the existing local alias `aspen-valhalla` (SSS LAN host `172.16.1.40`, user `spokane`) to `valhalla-dev`. `ssh valhalla 'hostname; whoami'` → `vm-aspen-valhalla`, `aspen`.
