#!/usr/bin/env bash
# One-time, manifest-driven cleanup authorized for the six releases below.
set -euo pipefail

fail() { printf 'Cleanup refused: %s\n' "$*" >&2; exit 1; }
MANIFEST=${1:-.github/release-cleanup.publish}
REPO=${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}
[[ "$REPO" == xgl34222220-ops/BaiZe ]] || fail 'unexpected repository'
[[ ${GITHUB_EVENT_NAME:-} == push && ${GITHUB_REF:-} == refs/heads/main ]] || fail 'requires a main push'
test -s "$MANIFEST"
TMP=$(mktemp -d "${RUNNER_TEMP:-/tmp}/baize-release-cleanup.XXXXXX")

# Retain the reviewed identities independently of the trigger manifest. A future
# cleanup request must be reviewed separately rather than widening this batch.
cat > "$TMP/expected.json" <<'JSON'
[
  {"tag":"v2.9.3","id":387519622,"sha":"303242ec934111d6723b6b7f6dcc14b7db61cd39","published_at":"2026-09-12T09:29:54Z","mirror":"12b34d9604fd6112b46ace4a04a2cfbe581bd76c"},
  {"tag":"v2.9.2","id":387501349,"sha":"59a4b66de4736df72ca469669f2aaa368bb4116b","published_at":"2026-09-12T08:11:38Z","mirror":"ba99f9e039bece47e3d0017430524354034cfe43"},
  {"tag":"v2.9.1","id":387486404,"sha":"9ee61de14f2e044dcbf53604635c2da5397d2532","published_at":"2026-09-12T07:08:25Z","mirror":"3f24d8b170c3dc18d1ccbcaa35b03bcbdcdf8b4a"},
  {"tag":"v2.9.0","id":387470771,"sha":"a0bdf8935a21eb6892d9e382c61fad96509b2bed","published_at":"2026-09-12T05:54:14Z","mirror":"5472df4c8638c41d308791f68a0f0636c1bed03f"},
  {"tag":"v2.8.3","id":387127860,"sha":"2af49255b2b274e07ecd185aeb98f5446384080a","published_at":"2026-09-11T15:03:03Z","mirror":"9570c9bc1866775342681d59fbc48cf754fbfd1a"},
  {"tag":"v2.8.2","id":384514836,"sha":"be35eef1b1eb91ce88a1bf44e34b15466ead7a38","published_at":"2026-09-08T07:18:51Z","mirror":"a83d1dc2f4653cfc53626bd2098be78332f2f67e"}
]
JSON
jq -e --slurpfile expected "$TMP/expected.json" '
  (keys | sort) == ["keep_release_id","keep_target_sha","keep_version","releases"] and
  .keep_version == "v3.0.0" and
  (.keep_release_id | type == "number" and . > 0 and floor == .) and
  (.keep_target_sha | type == "string" and test("^[0-9a-f]{40}$")) and
  (.releases | type == "array" and length == 6) and
  (.releases | sort_by(.tag)) == ($expected[0] | map(del(.mirror)) | sort_by(.tag))
' "$MANIFEST" >/dev/null || fail 'manifest does not match the exact approved six releases'
KEEP=$(jq -r .keep_version "$MANIFEST")
KEEP_ID=$(jq -r .keep_release_id "$MANIFEST")
KEEP_SHA=$(jq -r .keep_target_sha "$MANIFEST")
jq -r '.[] | [.tag, .id, .sha, .published_at, .mirror] | @tsv' "$TMP/expected.json" > "$TMP/releases.tsv"

# Only a real HTTP 404 is an idempotent absence. Authentication, rate limits and
# network failures must never be mistaken for an already deleted object.
read_optional() {
  local endpoint=$1 output=$2
  if gh api "$endpoint" > "$output" 2> "$output.err"; then return; fi
  if grep -Eq '\(HTTP 404\)' "$output.err"; then
    printf 'null\n' > "$output"
  else
    cat "$output.err" >&2
    fail "cannot inspect $endpoint"
  fi
}

verify_keep() {
  gh api "repos/$REPO/releases/$KEEP_ID" > "$TMP/keep.json"
  jq -e --arg tag "$KEEP" --arg sha "$KEEP_SHA" --argjson id "$KEEP_ID" '
    .id == $id and .tag_name == $tag and .target_commitish == $sha and
    .draft == false and .prerelease == false and .published_at != null
  ' "$TMP/keep.json" >/dev/null || fail 'retained release identity or formal status changed'
  gh api "repos/$REPO/releases/tags/$KEEP" --jq .id | grep -Fxq "$KEEP_ID" || fail 'retained release tag moved'
  gh api "repos/$REPO/git/ref/tags/$KEEP" > "$TMP/keep-ref.json"
  jq -e --arg sha "$KEEP_SHA" '.object.type == "commit" and .object.sha == $sha' "$TMP/keep-ref.json" >/dev/null || fail 'retained git tag moved'
  git fetch --no-tags origin main downloads
  git merge-base --is-ancestor "$KEEP_SHA" origin/main
  git show origin/main:.github/release-cleanup.publish > "$TMP/current-manifest.json"
  cmp "$MANIFEST" "$TMP/current-manifest.json" || fail 'cleanup request was superseded'
  test "$(git show origin/main:.github/release.publish | tr -d '[:space:]')" = "$KEEP_SHA" || fail 'a newer release is requested'
  git show origin/main:update.json > "$TMP/ota.json"
  jq -e --arg version "$KEEP" --arg repo "$REPO" '
    .version == $version and .versionCode == 30000 and
    .zipUrl == ("https://raw.githubusercontent.com/" + $repo + "/downloads/releases/" + $version + "/BaiZe-" + $version + "-Module.zip") and
    .changelog == ("https://raw.githubusercontent.com/" + $repo + "/main/RELEASE_NOTES_" + $version + ".md")
  ' "$TMP/ota.json" >/dev/null || fail 'OTA does not point to the retained formal release'
}
verify_keep

ASSETS="$TMP/assets"
mkdir "$ASSETS"
names=("BaiZe-$KEEP-Module.zip" "BaiZe-$KEEP-Module.zip.sha256" "BaiZe-$KEEP.apk" "BaiZe-$KEEP.apk.sha256" "BaiZe-$KEEP-signing-certificate.txt")
for name in "${names[@]}"; do
  jq -e --arg name "$name" '[.assets[] | select(.name == $name)] | length == 1 and .[0].state == "uploaded" and .[0].size > 0' "$TMP/keep.json" >/dev/null || fail "missing or duplicate retained asset: $name"
  gh release download "$KEEP" --repo "$REPO" --pattern "$name" --dir "$ASSETS"
  actual=$(sha256sum "$ASSETS/$name" | awk '{print $1}')
  jq -e --arg name "$name" --arg digest "sha256:$actual" '.assets[] | select(.name == $name) | .digest == $digest' "$TMP/keep.json" >/dev/null || fail "retained asset digest mismatch: $name"
  git show "origin/downloads:releases/$KEEP/$name" > "$TMP/mirror-asset"
  cmp "$ASSETS/$name" "$TMP/mirror-asset" || fail "retained download mirror differs: $name"
done
for name in "BaiZe-$KEEP-Module.zip" "BaiZe-$KEEP.apk"; do
  test "$(wc -l < "$ASSETS/$name.sha256")" -eq 1 || fail "unexpected checksum format: $name"
  read -r digest filename extra < "$ASSETS/$name.sha256"
  [[ "$digest" =~ ^[0-9a-f]{64}$ && "$filename" == "$name" && -z "$extra" ]] || fail "unexpected checksum target: $name"
  test "$digest" = "$(sha256sum "$ASSETS/$name" | awk '{print $1}')" || fail "checksum mismatch: $name"
done

DOWNLOADS_SHA=$(git rev-parse origin/downloads)
DOWNLOADS="$TMP/downloads"
git worktree add --detach "$DOWNLOADS" "$DOWNLOADS_SHA"

verify_old() {
  local tag=$1 id=$2 sha=$3 published=$4 mirror=$5 value
  read_optional "repos/$REPO/releases/$id" "$TMP/release-$tag.json"
  read_optional "repos/$REPO/releases/tags/$tag" "$TMP/release-tag-$tag.json"
  for value in "$TMP/release-$tag.json" "$TMP/release-tag-$tag.json"; do
    jq -e --arg tag "$tag" --arg sha "$sha" --arg date "$published" --argjson id "$id" '
      . == null or (.id == $id and .tag_name == $tag and .target_commitish == $sha and
      .published_at == $date and .draft == false and .prerelease == false and .immutable != true)
    ' "$value" >/dev/null || fail "old release identity changed: $tag"
  done
  cmp "$TMP/release-$tag.json" "$TMP/release-tag-$tag.json" >/dev/null || {
    # Asset download counters may change between reads; compare identity/absence.
    test "$(jq -r '.id // "absent"' "$TMP/release-$tag.json")" = "$(jq -r '.id // "absent"' "$TMP/release-tag-$tag.json")" || fail "inconsistent release lookup: $tag"
  }
  read_optional "repos/$REPO/git/ref/tags/$tag" "$TMP/ref-$tag.json"
  jq -e --arg ref "refs/tags/$tag" --arg sha "$sha" '. == null or (.ref == $ref and .object.type == "commit" and .object.sha == $sha)' "$TMP/ref-$tag.json" >/dev/null || fail "old tag moved: $tag"
  value=$(git -C "$DOWNLOADS" ls-tree HEAD -- "releases/$tag")
  if [[ -n "$value" ]]; then
    [[ "$value" == "040000 tree $mirror"$'\t'"releases/$tag" ]] || fail "old mirror contents changed: $tag"
  fi
}

# Inspect every old release, tag and mirror before the first deletion.
while IFS=$'\t' read -r tag id sha published mirror; do
  verify_old "$tag" "$id" "$sha" "$published" "$mirror"
done < "$TMP/releases.tsv"
verify_keep
test "$(git rev-parse origin/downloads)" = "$DOWNLOADS_SHA" || fail 'downloads advanced during preflight; rerun cleanup'

while IFS=$'\t' read -r tag id sha published mirror; do
  # Recheck each identity immediately before its mutation as well.
  verify_old "$tag" "$id" "$sha" "$published" "$mirror"
  if jq -e '. != null' "$TMP/release-$tag.json" >/dev/null; then
    gh api -X DELETE "repos/$REPO/releases/$id"
  fi
  if jq -e '. != null' "$TMP/ref-$tag.json" >/dev/null; then
    git push --force-with-lease="refs/tags/$tag:$sha" origin ":refs/tags/$tag"
  fi
  git -C "$DOWNLOADS" rm -r --ignore-unmatch -- "releases/$tag"
done < "$TMP/releases.tsv"

if ! git -C "$DOWNLOADS" diff --cached --quiet; then
  git -C "$DOWNLOADS" config user.name 'github-actions[bot]'
  git -C "$DOWNLOADS" config user.email '41898282+github-actions[bot]@users.noreply.github.com'
  git -C "$DOWNLOADS" commit -m 'release: remove the six approved pre-v3.0.0 download mirrors'
  # A concurrent downloads commit makes this ordinary push fail safely.
  git -C "$DOWNLOADS" push origin HEAD:downloads
fi

verify_keep
for name in "${names[@]}"; do
  git show "origin/downloads:releases/$KEEP/$name" > "$TMP/mirror-asset"
  cmp "$ASSETS/$name" "$TMP/mirror-asset" || fail "retained mirror changed after cleanup: $name"
done
while IFS=$'\t' read -r tag id sha published mirror; do
  for endpoint in "releases/$id" "releases/tags/$tag" "git/ref/tags/$tag"; do
    read_optional "repos/$REPO/$endpoint" "$TMP/removed.json"
    jq -e '. == null' "$TMP/removed.json" >/dev/null || fail "deleted object still exists: $endpoint"
  done
  test -z "$(git ls-tree origin/downloads -- "releases/$tag")" || fail "deleted mirror still exists: $tag"
  printf 'Removed release %s (ID %s), its exact tag and download mirror.\n' "$tag" "$id" >> "${GITHUB_STEP_SUMMARY:-$TMP/summary.txt}"
done < "$TMP/releases.tsv"
printf 'Verified %s remains published with matching assets, mirror and OTA.\n' "$KEEP" >> "${GITHUB_STEP_SUMMARY:-$TMP/summary.txt}"
