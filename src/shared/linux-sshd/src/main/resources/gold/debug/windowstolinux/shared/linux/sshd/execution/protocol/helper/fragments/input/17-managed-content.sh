parse_managed_data_bindings() {
  [ "$#" -ge 3 ] || reject managed-data-arguments
  managed_data_application="$1"; managed_data_component="$2"; shift 2
  require_app "$managed_data_application"; require_app "$managed_data_component"
  require_count "$1"
  local binding_count="$1" binding path mode existing_path
  shift
  managed_data_bindings=()
  while [ "$binding_count" -gt 0 ]; do
    [ "$#" -ge 3 ] || reject managed-data-bindings
    binding="$1"; path="$2"; mode="$3"; shift 3
    [[ "$binding" =~ ^[a-z0-9][a-z0-9._-]{0,63}$ ]] || reject managed-data-binding
    require_relative_path "$path"
    [[ "$path" =~ ^[a-z0-9][a-z0-9._/-]{0,254}$ ]] || reject managed-data-path
    [ "$mode" = ro ] || [ "$mode" = rw ] || reject managed-data-mode
    for existing_path in "${managed_data_bindings[@]}"; do
      existing_path="${existing_path#*:}"; existing_path="${existing_path%:*}"
      case "$path" in "$existing_path"|"$existing_path"/*) reject managed-data-overlap ;; esac
      case "$existing_path" in "$path"/*) reject managed-data-overlap ;; esac
    done
    managed_data_bindings+=("$binding:$path:$mode")
    binding_count=$((binding_count - 1))
  done
  managed_data_remaining_arguments=("$@")
}
managed_data_binding_root() {
  printf '%s/%s/%s/files/%s' "$data_root" "$managed_data_application" "$managed_data_component" "$1"
}
assert_managed_data_links() {
  local source_root="$1" spec binding logical mode link target actual
  [ -d "$source_root" ] && [ ! -L "$source_root" ] || reject managed-data-source
  for spec in "${managed_data_bindings[@]}"; do
    binding="${spec%%:*}"; logical="${spec#*:}"; logical="${logical%:*}"; mode="${spec##*:}"
    link="$source_root/$logical"; target="$(managed_data_binding_root "$binding")"
    [ -L "$link" ] || reject managed-data-link
    actual="$(readlink -f -- "$link")"
    [ "$actual" = "$target" ] || reject managed-data-link-target
    [ -d "$target" ] && [ ! -L "$target" ] || reject managed-data-directory
    [ -z "$(find -P "$target" -xdev -type l -print -quit)" ] || reject managed-data-link-content
    [ -z "$(find -P "$target" -xdev ! -type f ! -type d -print -quit)" ] || reject managed-data-special-file
    [ -z "$(find -P "$target" -xdev -type f -links +1 -print -quit)" ] || reject managed-data-hardlink
    if [ "$mode" = ro ]; then [ -z "$(find -P "$target" -xdev -perm /222 -print -quit)" ] || reject managed-data-readonly; fi
  done
}
prepare_managed_data_bindings() {
  local source_root="$1" spec binding logical mode link target seed parent
  install -d -o root -g root -m 755 -- "$data_root" "$data_root/$managed_data_application" \
    "$data_root/$managed_data_application/$managed_data_component" \
    "$data_root/$managed_data_application/$managed_data_component/files"
  for spec in "${managed_data_bindings[@]}"; do
    binding="${spec%%:*}"; logical="${spec#*:}"; logical="${logical%:*}"; mode="${spec##*:}"
    target="$(managed_data_binding_root "$binding")"; link="$source_root/$logical"; seed=
    if [ ! -e "$target" ] && [ ! -L "$target" ]; then
      if [ "$previous_present" -eq 1 ] && [ -d "$previous_path/source/$logical" ] \
          && [ ! -L "$previous_path/source/$logical" ]; then
        seed="$previous_path/source/$logical"
      elif [ -d "$link" ] && [ ! -L "$link" ]; then
        seed="$link"
      elif [ -e "$link" ] || [ -L "$link" ]; then
        reject managed-data-seed
      fi
      install -d -o "$deployer" -g "$deployer_group" -m 700 -- "$target"
      if [ -n "$seed" ]; then
        [ -z "$(find -P "$seed" -xdev -type l -print -quit)" ] || reject managed-data-seed-link
        [ -z "$(find -P "$seed" -xdev ! -type f ! -type d -print -quit)" ] || reject managed-data-seed-special
        [ -z "$(find -P "$seed" -xdev -type f -links +1 -print -quit)" ] || reject managed-data-seed-hardlink
        cp -a --no-preserve=ownership -- "$seed/." "$target/"
      fi
      chown -R "$deployer:$deployer_group" -- "$target"
    fi
    [ -d "$target" ] && [ ! -L "$target" ] || reject managed-data-directory
    parent="$(dirname -- "$link")"; install -d -o root -g root -m 755 -- "$parent"
    if [ -e "$link" ] || [ -L "$link" ]; then rm -rf --one-file-system -- "$link"; fi
    ln -sT -- "$target" "$link"
    if [ "$mode" = ro ]; then chmod -R a-w,u+rX,go-rwx -- "$target"; else chmod -R u+rwX,go-rwx -- "$target"; fi
  done
  assert_managed_data_links "$source_root"
}
