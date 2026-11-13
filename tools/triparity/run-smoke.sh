#!/usr/bin/env bash
# Synthetic provider contract smoke: no Alpaca requests and no source mutations.
set -euo pipefail

if [[ $# -ne 2 ]]; then
  printf 'Usage: %s /path/to/kirke /path/to/medea\n' "$0" >&2
  exit 2
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
atalanta="$(cd "$script_dir/../.." && pwd)"
pack="$atalanta/contracts/alpaca-trading/v1"
kirke="$(cd "$1" && pwd)"
medea="$(cd "$2" && pwd)"

for tool in git python3 stack cargo sha256sum; do
  command -v "$tool" > /dev/null || { echo "Missing $tool" >&2; exit 2; }
done
git -C "$kirke" rev-parse --is-inside-work-tree > /dev/null
git -C "$medea" rev-parse --is-inside-work-tree > /dev/null

( cd "$pack"; sha256sum -c SHA256SUMS > /dev/null )

scratch="$(mktemp -d)"
created_kirke=false
created_medea=false
cleanup() {
  if [[ "$created_kirke" == true ]]; then
    git -C "$kirke" worktree remove --force "$scratch/kirke" > /dev/null 2>&1 || true
  fi
  if [[ "$created_medea" == true ]]; then
    git -C "$medea" worktree remove --force "$scratch/medea" > /dev/null 2>&1 || true
  fi
  rm -rf -- "$scratch"
}
trap cleanup EXIT

# Do not fetch or modify existing worktrees. Pin locally fetched origin/main.
git -C "$kirke" worktree add --detach "$scratch/kirke" origin/main > /dev/null
created_kirke=true
git -C "$medea" worktree add --detach "$scratch/medea" origin/main > /dev/null
created_medea=true

cp "$script_dir/KirkePortableFixtureSmokeSpec.hs" \
  "$scratch/kirke/test/Alpaca/PortableFixtureSmokeSpec.hs"
cp "$script_dir/MedeaPortableFixtureSmoke.rs" \
  "$scratch/medea/src/portable_contract_smoke.rs"

python3 - "$scratch/medea/src/main.rs" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
text = p.read_text()
marker = 'mod order_lookup;'
assert text.count(marker) == 1, "Medea main module declaration changed"
p.write_text(text.replace(marker, marker + '\n#[cfg(test)]\nmod portable_contract_smoke;', 1))
PY

printf 'Fixture revision: 1.0.0 (SHA-256 verified)\n'
printf 'Kirke snapshot: %s\n' "$(git -C "$scratch/kirke" rev-parse --short HEAD)"
printf 'Medea snapshot: %s\n' "$(git -C "$scratch/medea" rev-parse --short HEAD)"
printf 'Running 26 synthetic provider cases per language.\n'

set +e
( cd "$scratch/kirke"; env -u APCA_API_KEY_ID -u APCA_API_SECRET_KEY \
  ATALANTA_CONTRACT_ROOT="$pack" stack test ) > "$scratch/kirke.log" 2>&1
kirke_status=$?
( cd "$scratch/medea"; env -u APCA_API_KEY_ID -u APCA_API_SECRET_KEY \
  ATALANTA_CONTRACT_ROOT="$pack" \
  cargo test --locked --bin medea portable_contract_smoke -- --nocapture ) \
  > "$scratch/medea.log" 2>&1
medea_status=$?
set -e

if [[ -n "${TRIPARITY_LOG_DIR:-}" ]]; then
  mkdir -p -- "$TRIPARITY_LOG_DIR"
  cp "$scratch/kirke.log" "$TRIPARITY_LOG_DIR/kirke.log"
  cp "$scratch/medea.log" "$TRIPARITY_LOG_DIR/medea.log"
fi

python3 "$script_dir/assess.py" \
  "$pack/manifest.json" "$script_dir/known-gaps.json" \
  "$scratch/kirke.log" "$kirke_status" \
  "$scratch/medea.log" "$medea_status"
