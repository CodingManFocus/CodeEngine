#!/usr/bin/env bash
set -euo pipefail

: "${GH_REPO:?}"
: "${GH_TOKEN:?}"
: "${GITHUB_SHA:?}"
: "${RELEASE_TAG:?}"
: "${RUNNER_TEMP:?}"

fetchOptional() {
  local resource="$1"
  local outputPath="$2"
  local errorPath="$RUNNER_TEMP/codeengine-release-api-error.log"

  if gh api "$resource" > "$outputPath" 2> "$errorPath"; then
    return 0
  fi
  if grep -q 'HTTP 404' "$errorPath"; then
    printf 'null\n' > "$outputPath"
    return 0
  fi
  cat "$errorPath" >&2
  return 1
}

cd "${1:?Build artifact directory is required}"
assetPaths=(codeengine-plugin-*.jar codeengine-api-*.jar codeengine-source.zip
  LICENSE.md THIRD_PARTY_NOTICES.md README.md SHA256SUMS)
for assetPath in "${assetPaths[@]}"; do
  test -f "$assetPath"
done
sha256sum --check SHA256SUMS

tagPath="$RUNNER_TEMP/codeengine-release-tag.json"
fetchOptional "repos/$GH_REPO/git/ref/tags/$RELEASE_TAG" "$tagPath"
if jq -e '. != null' "$tagPath" > /dev/null; then
  tagCommit=$(gh api "repos/$GH_REPO/commits/$RELEASE_TAG" --jq '.sha')
  if [[ "$tagCommit" != "$GITHUB_SHA" ]]; then
    printf 'Tag %s already points to another commit.\n' "$RELEASE_TAG" >&2
    exit 1
  fi
fi

releasePath="$RUNNER_TEMP/codeengine-release.json"
fetchOptional "repos/$GH_REPO/releases/tags/$RELEASE_TAG" "$releasePath"
if [[ "$(jq -r '. == null' "$releasePath")" == true ]]; then
  # The tag endpoint only finds published releases; authenticated listing includes drafts.
  gh api --paginate "repos/$GH_REPO/releases?per_page=100" |
    jq -s --arg releaseTag "$RELEASE_TAG" \
      '[.[][] | select(.tag_name == $releaseTag)] |
       if length <= 1 then (.[0] // null) else error("Duplicate release tags") end' \
      > "$releasePath"
fi
if jq -e '. != null' "$releasePath" > /dev/null; then
  if [[ "$(jq -r '.draft' "$releasePath")" == false ]]; then
    if ! jq -e '. != null' "$tagPath" > /dev/null; then
      printf 'Published release %s has no verifiable tag.\n' "$RELEASE_TAG" >&2
      exit 1
    fi
    printf 'Release %s is already published; keeping its existing assets.\n' "$RELEASE_TAG"
    exit 0
  fi
  if [[ "$(jq -r '.target_commitish' "$releasePath")" != "$GITHUB_SHA" ]]; then
    printf 'Draft %s targets another commit.\n' "$RELEASE_TAG" >&2
    exit 1
  fi
fi

notesPath="$RUNNER_TEMP/codeengine-release-notes.md"
cat > "$notesPath" <<EOF
Code Engine 자동 빌드입니다.

- 커밋: $GITHUB_SHA
- 태그 날짜: 커밋 시각의 한국 날짜(Asia/Seoul)
- 검증: Java 21 Gradle 빌드 및 단위·컴파일 통합 테스트 통과
- 설치: codeengine-plugin-*.jar만 서버의 plugins/에 넣으세요.
- API JAR는 개발용이며 전체 소스와 GPL-3.0 라이선스를 함께 제공합니다.
- 파일 무결성: SHA256SUMS
EOF

if [[ "$(jq -r '. == null' "$releasePath")" == true ]]; then
  gh release create "$RELEASE_TAG" --target "$GITHUB_SHA" \
    --title "$RELEASE_TAG" --notes-file "$notesPath" --draft
fi
gh release upload "$RELEASE_TAG" "${assetPaths[@]}" --clobber

mainCommit=$(gh api "repos/$GH_REPO/commits/main" --jq '.sha')
isLatest=false
if [[ "$mainCommit" == "$GITHUB_SHA" ]]; then
  isLatest=true
fi
gh release edit "$RELEASE_TAG" --draft=false --latest="$isLatest" \
  --title "$RELEASE_TAG" --notes-file "$notesPath"
gh release view "$RELEASE_TAG" --json url --jq '.url'
