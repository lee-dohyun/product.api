#!/usr/bin/env bash
# main 의 마이그레이션이 바뀔 때, 열려 있는 PR 중 같은 Flyway 버전을 새로 추가하는 것을 찾아 표시한다 (#68).
#
# 왜 필요한가: PR CI 는 PR 이 갱신된 시점의 base 로만 돈다. PR 이 열려 있는 동안 다른 PR 이 같은 버전을
# 먼저 머지해도 기존 초록불은 그대로 남고, 파일명이 달라 git 충돌도 나지 않는다. 그대로 머지하면 main 에
# 같은 버전이 둘이 되어 Flyway 가 부팅을 거부한다(#63/#64 의 V17 중복, 2026-09-12).
# push 시점 검사(scripts/verify.d/check-flyway-version.sh)는 "내가 push 할 때"만 보므로 이 틈을 못 메운다.
#
# 동작: 충돌하는 PR 의 head 커밋에 실패 상태(flyway-version)를 달고 코멘트를 남긴다. 충돌이 없으면
# 성공 상태로 덮어써 이전 실패를 지운다. 머지를 막지는 않는다 — 브랜치 보호가 없는 저장소라 표시가 전부다.
#
# 환경변수: GH_REPO(owner/repo, 필수), GH_TOKEN, DRY_RUN=1(쓰기 없이 판정만 출력)
set -euo pipefail
MIG_DIR="src/main/resources/db/migration"
REPO="${GH_REPO:?GH_REPO 가 필요하다}"
CONTEXT="flyway-version"
version_of() { basename "$1" | sed -nE 's/^(V[0-9][0-9_.]*)__.*/\1/p'; }

# main 의 버전 → 파일명
declare -A MAIN
while IFS= read -r f; do
  v=$(version_of "$f"); [ -n "$v" ] && MAIN["$v"]=$(basename "$f")
done < <(git ls-tree -r --name-only origin/main -- "$MIG_DIR")

rc=0
while IFS=$'\t' read -r num sha; do
  [ -n "$num" ] || continue
  clash=""
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    v=$(version_of "$f"); [ -n "$v" ] || continue
    # 같은 파일명이면 main 에서 온 것(리베이스 전 diff 잔재)이지 충돌이 아니다.
    if [ -n "${MAIN[$v]:-}" ] && [ "${MAIN[$v]}" != "$(basename "$f")" ]; then
      clash+="- \`$(basename "$f")\` ↔ main 의 \`${MAIN[$v]}\` (둘 다 $v)"$'\n'
    fi
  done < <(gh api "repos/$REPO/pulls/$num/files" --paginate \
             --jq ".[] | select(.status==\"added\" and (.filename|startswith(\"$MIG_DIR/\"))) | .filename")

  if [ -n "$clash" ]; then
    echo "❌ PR #$num: Flyway 버전 충돌"; printf '%s' "$clash"
    [ "${DRY_RUN:-0}" = "1" ] && continue
    gh api "repos/$REPO/statuses/$sha" --method POST -f state=failure -f context="$CONTEXT" \
      -f description="main 에 같은 Flyway 버전이 이미 있다 — 번호를 다시 정할 것" >/dev/null
    marker="<!-- flyway-open-pr-guard $sha -->"
    if ! gh api "repos/$REPO/issues/$num/comments" --paginate --jq '.[].body' | grep -qF "$marker"; then
      gh api "repos/$REPO/issues/$num/comments" --method POST -f body="$marker
⚠️ **이 PR 을 지금 머지하면 main 의 Flyway 버전이 중복된다.** PR 이 열려 있는 동안 main 에 같은 번호가 먼저 들어왔다.

$clash
같은 버전이 둘이면 Flyway 가 부팅을 거부한다. main 을 머지(또는 리베이스)한 뒤 마이그레이션 번호를 다음 빈 번호로 옮길 것. 기존 CI 초록불은 충돌 전 base 기준이라 믿으면 안 된다 (#68)." >/dev/null
    fi
  else
    echo "✅ PR #$num: 충돌 없음"
    [ "${DRY_RUN:-0}" = "1" ] && continue
    # 예전에 실패를 달았던 커밋만 성공으로 덮어쓴다(무관한 PR 에 상태를 늘리지 않는다).
    if gh api "repos/$REPO/commits/$sha/statuses" --jq '.[].context' | grep -qx "$CONTEXT"; then
      gh api "repos/$REPO/statuses/$sha" --method POST -f state=success -f context="$CONTEXT" \
        -f description="main 과 Flyway 버전 충돌 없음" >/dev/null
    fi
  fi
done < <(gh api "repos/$REPO/pulls?state=open&base=main&per_page=100" --paginate --jq '.[] | [.number, .head.sha] | @tsv')
exit $rc
