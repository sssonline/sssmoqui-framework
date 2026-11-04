#!/bin/bash
# Seed parity check for UPDATE.md step 2: list seed/seed-initial/install data files that changed between the
# commits in a client's RELEASE.txt and the local HEAD of each repo, with removed (-) and added (+) line counts.
# Run from the moqui root:
#   ssh <client> "sudo cat /opt/aspen/releases/<release>/RELEASE.txt" | tail -n +4 > /tmp/manifest.txt
#   docs/azure/seedDiff.sh /tmp/manifest.txt
# Use the release the database was FIRST loaded from (or each release since), not only the running one.
# Seed inside entity files (<seed-data>) isn't covered: also check `git diff <sha> HEAD -- entity` for record lines.
while read -r name sha br; do
  case "$name" in
    moqui) d=. ; sub=framework/data ;;
    runtime) d=runtime ; sub=base-component ;;
    *) d=runtime/component/$name ; sub=data ;;
  esac
  [ -d "$d/.git" ] || continue
  for f in $(git -C "$d" diff --name-only "$sha" HEAD -- "$sub" 2>/dev/null | grep '\.xml$'); do
    t=$(grep -o 'type="[^"]*"' "$d/$f" 2>/dev/null | sort -u | tr '\n' ' ')
    case "$t" in *seed*|*install*) echo "$name $sha $f [$t] -$(git -C "$d" diff "$sha" HEAD -- "$f" | grep -c '^-[^-]') +$(git -C "$d" diff "$sha" HEAD -- "$f" | grep -c '^+[^+]')" ;; esac
  done
done < "${1:?usage: seedDiff.sh <manifest-lines-file>}"
