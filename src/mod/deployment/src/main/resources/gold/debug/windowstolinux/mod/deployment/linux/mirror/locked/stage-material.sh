set -euo pipefail
PATH=/usr/sbin:/usr/bin:/sbin:/bin
umask 077
[ "$(id -u)" = 0 ] || exit 64
wtl_private_root() {
  local path="$1" current=/ component owner
  local -a parts
  IFS=/ read -r -a parts <<<"${path#/}"
  for component in "${parts[@]}"; do
    [ -n "$component" ] || continue
    current="${current%/}/$component"
    [ ! -L "$current" ] || exit 64
    if [ ! -e "$current" ]; then mkdir -- "$current"; chmod 700 -- "$current"; fi
    [ -d "$current" ] && [ "$(stat -c '%u:%g' -- "$current")" = 0:0 ] || exit 64
    [ -z "$(find -P "$current" -maxdepth 0 -perm /022 -print -quit)" ] || exit 64
  done
}
wtl_private_root "$wtl_material_directory"
if [ "$wtl_material_mode" = discard ]; then
  for file in "$wtl_material_directory"/*; do
    [ -e "$file" ] || continue
    [[ "${file##*/}" =~ ^[0-9a-f]{64}$ ]] && [ -f "$file" ] && [ ! -L "$file" ] || exit 64
    [ "$(stat -c '%u:%g:%h' -- "$file")" = 0:0:1 ] || exit 64
    rm -- "$file"
  done
  rmdir -- "$wtl_material_directory"
  printf 'MIRROR_MATERIAL_DISCARDED=1\n'
  exit
fi
target="$wtl_material_directory/$wtl_material_reference"
temporary="$(mktemp "$wtl_material_directory/.receive.XXXXXXXX")"
trap 'rm -f -- "$temporary"' EXIT HUP INT TERM
head -c "$((wtl_material_size + 1))" >"$temporary"
[ "$(stat -c '%s' -- "$temporary")" = "$wtl_material_size" ] || exit 64
[ "$(sha256sum -- "$temporary" | cut -d ' ' -f 1)" = "$wtl_material_sha" ] || exit 64
if [ -e "$target" ] || [ -L "$target" ]; then
  [ -f "$target" ] && [ ! -L "$target" ] && [ "$(stat -c '%u:%g:%h' -- "$target")" = 0:0:1 ] || exit 64
  [ "$(sha256sum -- "$target" | cut -d ' ' -f 1)" = "$wtl_material_sha" ] || exit 64
else
  chmod 400 -- "$temporary"
  mv -T -- "$temporary" "$target"
fi
printf 'MIRROR_MATERIAL_STAGED=1\n'
