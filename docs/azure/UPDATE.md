# Updating a client's Aspen — Release Runbook

How to ship new Aspen code to a client VM that [INSTALL.md](INSTALL.md) set up. A human or an agent can follow it top to bottom. Record every step and its output in the client's install log (`clients/<client>-install-log.md`).

## At a glance

1. **Pull and check** (Mac): get every repo up to date on `update` with no local changes, and read what's running on the client from its `RELEASE.txt`.
2. **Review the changes**: since the client's release, look for new indexes, changed seed data, one-time `upgrade#` services and new scheduled jobs. Write down what will need doing after the switch.
3. **Test** (Mac): run the Postgres test suite. It should pass; if you ship anyway, write down why.
4. **Build** (Mac): build one WAR file, check its contents, and write a `RELEASE.txt` manifest naming every repo's commit.
5. **Send and stage** (Mac → VM): copy the WAR to the VM and unpack it into a new release folder beside the running one. Users notice nothing.
6. **Switch** (VM, about 3–5 minutes down): note the time, stop Aspen, point `current` at the new release, start Aspen, wait for the site, check the startup log.
7. **One-time steps** (Aspen): run the release's upgrade services under Tools → Service Run (skip any whose work is already done), remove seed records the release retired so the database matches a clean install, and pause any new jobs the client doesn't use.
8. **Check**: log in, try the main screens and whatever changed, and confirm the release name.
9. **Record**: write it all in the client's install log, and remove old releases so at most three remain.
10. **If it goes wrong**: point `current` back at the previous release and restart (see Rollback).

How it works: the previous release stays on disk next to the new one, and `current` is a symlink to whichever one runs, so switching and rolling back each take one command and a restart. The database is shared, and Aspen only adds to it on an update.

| Placeholder | Meaning (Valhalla) |
|---|---|
| `<client>` | SSH alias for the VM (`valhalla`) |
| `<site>` | public URL (`https://valhalla.spokanesoftware.com`) |
| `<OLD>` / `<NEW>` | release names, `<date>_<moqui-root-short-sha>` (`2026-10-29_4dea19a9`) |

Run the Mac commands from the moqui root. VM commands assume you're logged in over SSH with the `<client>` alias from INSTALL.md (`ssh <client>`), and the Aspen steps assume the SSS admin login.

Expect about an hour of work and **3–5 minutes of downtime** (stop, start, ~80 s Moqui startup).

---

## 1. Preflight (Mac)

All repos on `update`, clean, and current:
```bash
for r in . runtime runtime/component/*/; do [ -d "$r/.git" ] || continue; git -C $r fetch -q
  printf "%-24s %s dirty:%s behind:%s\n" "$(basename $(cd $r; pwd))" "$(git -C $r rev-parse --short HEAD)" \
    "$(git -C $r status --porcelain | wc -l | tr -d ' ')" "$(git -C $r rev-list --count HEAD..@{u})"; done
```
Every line must show `dirty:0 behind:0`.

What's running on the client now (each repo's commit is in the manifest):
```bash
ssh <client> "sudo cat /opt/aspen/current/RELEASE.txt"
```

## 2. Review what the release changes

For each repo whose commit differs from the manifest (`<deployed>` = the manifest's commit), run these from that repo:
```bash
git log --oneline --no-merges <deployed>..HEAD
git diff --name-status <deployed>..HEAD -- entity data service
```
Look for these four things. They are what makes an update different from a fresh install.

1. **Entity changes.** On start Moqui adds new tables and new columns, but **never adds an index to an existing table**, never changes a column's type, and never drops anything. Releases that need an index or a data fix ship an idempotent `upgrade#...` service (next item).
2. **One-time upgrade services**, which someone must run by hand after the switch:
   ```bash
   git diff <deployed>..HEAD -- service | grep -E '^\+.*verb="(upgrade|migrate)"'
   ```
   Read each one's description, and note the full name (`<package>.<File>.upgrade#<Noun>` or `migrate#<Noun>`). They're written to be idempotent, so running one twice is harmless. Some need input or a dry run first: `migrate#TagOriginalQuantityLineage` (card #1058) needs the date #1058 was deployed to that database and runs as a dry run by default; its description gives the steps.
3. **Seed and install data.** On every start Moqui loads `seed,seed-initial,install` **create-only**: new records are added, but a changed record keeps its old values and a removed record stays in the database. For each changed data file, look at the removed (`-`) lines:
   - A changed key, such as a renamed screen path: the new record gets added and the old one stays. That's normally harmless.
   - The same key with changed values on **seed** data: reload just that file after the switch (step 7). Seed data belongs to Aspen, so overwriting it is fine.
   - Changed **install** or **seed-initial** records: the client may have edited them (GL accounts, settings). Never bulk-reload these. Fix only the rows the release needs, after checking them.
   - **Removed** records, and old keys left behind by a renamed one, stay in the database. A clean install wouldn't have them, so a retired status or enum can still appear in drop-downs. Delete them after the switch, once nothing references them (step 7).

   [seedDiff.sh](seedDiff.sh) lists every changed seed, seed-initial and install file, with counts of removed and added lines. Feed it the manifest of the release the database was **first loaded from**, so changes from every release since are included:
   ```bash
   ssh <client> "sudo cat /opt/aspen/releases/<first-release>/RELEASE.txt" | tail -n +4 > /tmp/manifest.txt
   docs/azure/seedDiff.sh /tmp/manifest.txt
   git -C runtime/component/<name> diff -U0 <sha> HEAD -- data entity | grep -E '^-[[:space:]]*<'   # removed or changed records
   ```
   Seed defined inside entity files (`<seed-data>`) counts too, which is why the last command includes `entity`. Files showing only `+` lines just add records, and the load on start handles those.
4. **New scheduled jobs.** A new `moqui.service.job.ServiceJob` in seed data starts running at the next start unless it has `paused="Y"`:
   ```bash
   git diff <deployed>..HEAD -- data | grep -E '^\+.*ServiceJob '
   ```
   Decide whether each one should run for this client, and pause it after the switch if not (INSTALL.md phase 19).

Write the findings in the install log before going further.

## 3. Test (Mac)

Start Docker Desktop (`open -a Docker`), stop any local Moqui, then:
```bash
gradle cleanPullCompTest -Pconf=conf/MoquiDevConfPostgres.xml     # local Postgres 18, must pass
```
Valhalla's 2026-10 baseline was 290 tests, 0 failures, 0 errors. Don't ship a red suite without a decision: if someone decides to ship anyway, record the real totals, which failures they judged test-only, and who decided. Gradle stops at the first component with failures, so later components didn't run; say so too.

Read totals from the results (the console line covers only the component that failed):
```bash
find runtime/component -path '*/build/test-results/test/*.xml' -newer build.gradle -exec grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' {} + \
  | awk -F'"' '{t+=$2;s+=$4;f+=$6;e+=$8} END {print t" tests, "f" failures, "e" errors, "s" skipped"}'
```
Each run overwrites the previous results, so copy the numbers into the install log right away.

## 4. Build and check the WAR (Mac)

```bash
./gradlew cleanAllButDb build addRuntime                           # -> moqui-plus-runtime.war (~100 MB)
unzip -l moqui-plus-runtime.war | grep -oE "runtime/conf/[^ ]+"              # MoquiProductionConfPostgres.xml, MoquiProductionConf.xml
unzip -l moqui-plus-runtime.war | grep -c "WEB-INF/lib/postgresql-"          # 1
unzip -l moqui-plus-runtime.war | grep -cE "runtime/db/h2/|runtime/component/aspendev/|runtime/txlog/"   # 0
```
Write the manifest and upload both files:
```bash
REL=$(date +%Y-%m-%d)_$(git rev-parse --short HEAD)
{ echo "release: $REL"; echo "built: $(date '+%Y-%m-%d %H:%M %Z') by $(git config user.name) from branch $(git branch --show-current)";
  echo "war: moqui-plus-runtime.war $(shasum -a 256 moqui-plus-runtime.war | cut -c1-16)";
  for r in . runtime runtime/component/*/; do [ -d "$r/.git" ] && printf "%-28s %s %s\n" "$(basename $(cd $r; pwd))" "$(git -C $r rev-parse --short HEAD)" "$(git -C $r branch --show-current)"; done; } > RELEASE.txt
scp moqui-plus-runtime.war RELEASE.txt <client>:/tmp/
```
If two releases are built on the same day from the same root commit, add a suffix (`_b`) so the release names stay unique.

## 5. Stage beside the running release (VM, no downtime)

```bash
REL=<NEW>; R=/opt/aspen/releases/$REL
sudo -u aspensvc mkdir -p $R
sudo unzip -q -o /tmp/moqui-plus-runtime.war -d $R
sudo install -m 0644 /tmp/RELEASE.txt $R/RELEASE.txt
for d in log txlog sessions; do sudo rm -rf $R/runtime/$d; sudo ln -sfn /opt/aspen/shared/$d $R/runtime/$d; done
sudo chown -R aspensvc:aspensvc $R
rm -f /tmp/moqui-plus-runtime.war /tmp/RELEASE.txt
sudo ls -la $R/runtime | grep -E "log|txlog|sessions|conf"; sudo ls $R/runtime/conf
```
`log`, `txlog` and `sessions` must be symlinks into `/opt/aspen/shared`, and `conf` must hold only the two production confs. **Don't touch `current` yet.**

Record the transaction manager ID, which must survive the switch:
```bash
sudo cat /opt/aspen/shared/txlog/server-id
```

## 6. Switch (VM, downtime starts)

1. **Safety point.** Write down the UTC time: `date -u`. Postgres point-in-time restore can return to any second within 35 days, so this time is the "before" if the release damages data. Once the client is live, tell users first.
2. Switch and start:
   ```bash
   sudo systemctl stop aspen
   sudo -u aspensvc ln -sfn /opt/aspen/releases/<NEW> /opt/aspen/current
   sudo systemctl start aspen
   ```
3. Wait for the **public** URL. Don't poll `127.0.0.1:8080/status`, which returns 302 by design. Run this from the Mac:
   ```bash
   until [ "$(curl -s -o /dev/null -w '%{http_code}' <site>/Login)" = 200 ]; do sleep 10; done; echo up
   ```
4. Check the startup log:
   ```bash
   L=/opt/aspen/shared/log/moqui.log
   sudo grep -E "Bitronix transaction manager server ID|Loaded \[[0-9]+\] records|Moqui Framework initialized" $L | tail -4
   sudo grep -nE " ERROR " $L | tail -20
   ```
   - The server ID must equal the one recorded in step 5.
   - "Loaded [n] records (with types from on-start-load-types…)" is the create-only load adding the release's new seed and install records.
   - Compare the ERROR and WARN lines with the expected-noise list in INSTALL.md phase 10. Anything new needs explaining before you continue. The log writes a `--- <time> [thread] LEVEL class` header line, then the message on the next line, so read the line after each match. To see only this boot, start from the shutdown line:
     ```bash
     sudo bash -c 'L=/opt/aspen/shared/log/moqui.log; s=$(grep -n "yShutdownThread" $L | tail -1 | cut -d: -f1); tail -n +$s $L | grep -A1 "\] \(ERROR\|WARN\) " | grep -v "^--"'
     sudo grep -oE "Created table [^ ]+" /opt/aspen/shared/log/moqui.log | tail -20     # tables this release added
     ```

## 7. One-time steps for this release (Aspen, as the SSS admin)

1. **Upgrade services** from step 2. First check what's actually missing: Moqui sometimes creates an index on start anyway (on Valhalla's 2026-11-03 release all ten new indexes already existed). Read-only, on the VM with the psql pattern from INSTALL.md:
   ```sql
   select indexname, tablename from pg_indexes where schemaname='public' and upper(indexname) in ('<INDEX_1>','<INDEX_2>');
   ```
   Skip a service whose work is already done, and record what you skipped and why. Then run the rest from **Tools → Service Run** (`/vapps/tools/Service/ServiceRun`). Enter each full service name, click **Run**, and copy its result into the install log (for example "created [ ... ]" or "nothing to do").
2. **Changed seed records**, only if step 2 found any. From the release directory on the VM, with Aspen stopped:
   ```bash
   sudo systemctl stop aspen
   sudo bash -c 'set -a; . /opt/aspen/aspen.env; set +a; cd /opt/aspen/current; sudo -E -u aspensvc /usr/lib/jvm/temurin-11-jdk-amd64/bin/java -Xmx2g -cp . MoquiStart load location=component://<component>/data/<File>.xml conf=conf/MoquiProductionConfPostgres.xml'
   sudo systemctl start aspen
   ```
   A command-line load creates *or updates*, unlike the create-only load on start. Load only the seed files you reviewed.
3. **Removed or renamed seed records**, only if step 2 found any. Check that nothing references them, then delete them in one transaction with the psql pattern from INSTALL.md. Valhalla's 2026-11-03 release is a worked example (install log, Session 7): a relabeled status (`update status_item set description=...`), a retired enum type and its values, a retired GL account type enum, two help documents at old screen paths, and an index swap (`drop index acct_pool_pseudo`) that the release's upgrade service would otherwise have done. Read the values back afterward.
4. **New scheduled jobs:** pause the ones the client doesn't use, as decided in step 2.
5. If you changed data with SQL, clear the affected caches (**System → Cache**) or restart Aspen (about 90 seconds down). The load on start is create-only, so it never brings back records the release removed.

## 8. Verify

- `<site>` loads, and you can log in.
- `sudo readlink /opt/aspen/current` and `sudo cat /opt/aspen/current/RELEASE.txt` show `<NEW>` (`/opt/aspen` is root-only, so without `sudo` readlink prints nothing).
- Smoke test: open a recent order, a shipment with an attachment (download it), and an invoice (print or preview), plus whatever the release changed.
- An hour later, check `moqui.log` for new ERROR lines and the Service Job runs for errors.

## 9. Record and tidy

- Install log: release name, WAR sha256, repo commits, the step 2 findings, upgrade-service results, switch time, startup time, and verification.
- Client deployment doc: update "current release".
- Keep the previous release for rollback. Keep at most three releases on disk:
  ```bash
  ls -1t /opt/aspen/releases | tail -n +4    # review, then: sudo rm -rf /opt/aspen/releases/<old>
  ```
  Never delete the release `current` points at, or the one before it.
- Tag the release in every repo that changed (`git tag <client>-<NEW>` and push the tags) so the code can be found again. Push only when told to.

---

## Rollback

Use this when the new release misbehaves and the fix isn't quick.
```bash
sudo systemctl stop aspen
sudo -u aspensvc ln -sfn /opt/aspen/releases/<OLD> /opt/aspen/current
sudo systemctl start aspen
```
Wait for `<site>/Login` to return 200, then check that RELEASE.txt and the Bitronix ID are the same as before.

What rollback doesn't undo:
- **Schema and data.** New tables, columns, indexes, and records added by the release or its upgrade services stay. Older code ignores them. This is safe because Moqui and Aspen upgrades only add things.
- **Work done in the new release.** If users have created records with a new feature, the old code may not understand them, so roll back early.
- **Bad data written by the release.** Only a database restore to the step 6 safety point fixes that, and it loses everything entered since. See INSTALL.md phase 21 and its Burstable warning.

Moving forward again after a rollback is the same symlink switch back to `<NEW>`.
