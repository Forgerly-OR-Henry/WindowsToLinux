# Validate the immutable rootless export before passing it to the publishing engine.
candidate_image_name() {
  case "$1" in
    docker) printf 'windowstolinux-candidate:%s' "$2" ;;
    podman) printf 'localhost/windowstolinux-candidate:%s' "$2" ;;
    *) reject container-engine ;;
  esac
}
import_candidate_image() {
  local app="$1" candidate_id="$2" engine="$3" candidate archive image
  candidate="$(candidate_root "$candidate_id")"; archive="$candidate/mutable/candidate-image.tar"
  image="$(candidate_image_name "$engine" "$candidate_id")"
  assert_sealed_build "$app" "$candidate_id"
  [ -f "$archive" ] && [ ! -L "$archive" ] && [ "$(stat -c %h "$archive")" = 1 ] || reject container-image-archive
  (cd /; /usr/bin/python3 -I - "$archive" "$app" "$candidate_id" "$image" <<'WTL_IMAGE_INPUT'
import hashlib, json, re, sys, tarfile
archive, app, candidate, tag = sys.argv[1:]
def reject(reason):
    raise SystemExit('container-image-' + reason)
def safe_name(name):
    return bool(name) and not name.startswith('/') and all(p not in ('', '.', '..') for p in name.rstrip('/').split('/'))
with tarfile.open(archive, 'r:') as source:
    members = {}
    for member in source:
        if len(members) >= 10000 or not safe_name(member.name) or member.name in members:
            reject('member-name')
        if not (member.isfile() or member.isdir()) or member.size < 0:
            print('CONTAINER_IMAGE_MEMBER=' + json.dumps({'name': member.name[:128],
                  'type': repr(member.type), 'link': member.linkname[:128]}, ensure_ascii=True))
            reject('member-type')
        members[member.name] = member
    def document(name):
        item = members.get(name)
        if item is None or not item.isfile() or item.size > 524288:
            reject('metadata')
        with source.extractfile(item) as stream:
            data = stream.read(524289)
        if len(data) > 524288:
            reject('metadata-limit')
        return data, json.loads(data)
    _, manifest = document('manifest.json')
    if not isinstance(manifest, list) or len(manifest) != 1 or manifest[0].get('RepoTags') != [tag]:
        reject('tags')
    item = manifest[0]
    config_name = item.get('Config', '')
    raw, config = document(config_name)
    digest = hashlib.sha256(raw).hexdigest()
    if config_name not in (digest + '.json', 'blobs/sha256/' + digest):
        reject('config-digest')
    labels = config.get('config', {}).get('Labels') or {}
    if labels.get('io.windowstolinux.application') != app or labels.get('io.windowstolinux.candidate') != candidate:
        reject('owner')
    user = config.get('config', {}).get('User', '')
    if not re.fullmatch(r'[1-9][0-9]{0,9}(?::[1-9][0-9]{0,9})?', user):
        reject('requires-explicit-nonroot-numeric-user')
    if any(int(value) > 2147483647 for value in user.split(':')):
        reject('user-range')
    layers = item.get('Layers')
    if not isinstance(layers, list) or len(layers) > 2048 or len(layers) != len(set(layers)):
        reject('layers')
    if any(name not in members or not members[name].isfile() for name in layers):
        reject('layer-file')
    if 'repositories' in members:
        _, repositories = document('repositories')
        repository = tag.rsplit(':', 1)[0]
        if list(repositories) != [repository] or list(repositories[repository]) != [candidate]:
            reject('repository-tags')
WTL_IMAGE_INPUT
  ) || reject container-image-validation
  printf '%s\n' "$engine" > "$candidate/.image-engine"
  chmod 400 -- "$candidate/.image-engine"
  "$engine" load --input "$archive" >/dev/null || reject container-image-import
}
container_nonroot_user() {
  local engine="$1" image="$2" user
  user="$("$engine" image inspect --format '{{.Config.User}}' "$image")"
  [[ "$user" =~ ^[1-9][0-9]{0,9}(:[1-9][0-9]{0,9})?$ ]] || reject container-requires-explicit-nonroot-user
  [ "${user%%:*}" -le 2147483647 ] || reject container-user-range
  if [[ "$user" != *:* ]]; then user="$user:$user"; fi
  [ "${user#*:}" -le 2147483647 ] || reject container-group-range
  printf '%s' "$user"
}

container_secret_delivery() {
  local app="$1" release="$2" user="$3" source="$4" name="$5" directory target uid
  require_app "$app"; require_digest "$release"
  uid="${user%%:*}"
  [[ "$uid" =~ ^[1-9][0-9]{0,9}$ ]] || reject container-secret-identity
  [[ "$name" =~ ^[A-Z_][A-Z0-9_]{0,127}$ ]] || reject container-secret-name
  assert_root_owned_regular "$source"
  directory="$secrets_root/$app/.container-credentials/$release"
  install -d -o root -g root -m 700 -- "$secrets_root/$app/.container-credentials" "$directory"
  assert_root_owned_directory "$directory"
  target="$directory/$name"
  [ ! -L "$target" ] || reject container-secret-link
  install -o "$uid" -g root -m 400 -- "$source" "$target"
  printf '%s' "$target"
}
prepare_container_volume_access() {
  local engine="$1" volume="$2" user="$3" path uid gid options
  uid="${user%%:*}"; gid="${user#*:}"; [ "$uid" != "$gid" ] || gid="$uid"
  [ "$("$engine" volume inspect --format '{{.Driver}}' "$volume")" = local ] || reject container-volume-driver
  options="$("$engine" volume inspect --format '{{json .Options}}' "$volume")"
  case "$options" in null|'{}') ;; *) reject container-volume-options ;; esac
  path="$("$engine" volume inspect --format '{{.Mountpoint}}' "$volume")"
  case "$engine:$path" in docker:/var/lib/docker/volumes/*/_data|podman:/var/lib/containers/storage/volumes/*/_data) ;; *) reject container-volume-storage-path ;; esac
  [ -d "$path" ] && [ ! -L "$path" ] && [ "$(readlink -f -- "$path")" = "$path" ] || reject container-volume-path
  if [ -z "$(find -P "$path" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
    chown "$uid:$gid" -- "$path"; chmod 700 -- "$path"
  else
    [ "$(stat -c %u "$path")" = "$uid" ] || reject container-volume-user-migration-required
  fi
}

import_restore_image() {
  local engine="$1" archive="$2" image="$3" destination
  assert_root_owned_regular "$archive"
  case "$engine" in docker) destination="docker-daemon:$image" ;; podman) destination="containers-storage:$image" ;; *) reject container-engine ;; esac
  # The explicit destination cannot import arbitrary tags embedded in the archive.
  /usr/bin/skopeo copy "oci-archive:$archive" "$destination" >/dev/null || reject restore-image-import
  container_nonroot_user "$engine" "$image" >/dev/null
}

candidate_image_reference() {
  local engine="$1" image="$2" found
  found="$("$engine" image ls --filter "reference=$image" --format '{{.Repository}}:{{.Tag}}')" || reject candidate-image-query
  # Podman lists every tag on a matching image; match only the requested reference.
  printf '%s\n' "$found" | awk -v tag="$image" '$0 == tag { print; exit }'
}
cleanup_candidate_image() {
  local app="$1" candidate_id="$2" candidate engine image found
  candidate="$(candidate_root "$candidate_id")"
  [ -e "$candidate/.image-engine" ] || return 0
  assert_root_owned_regular "$candidate/.image-engine"
  engine="$(cat -- "$candidate/.image-engine")"
  case "$engine" in docker|podman) ;; *) reject candidate-image-engine ;; esac
  image="$(candidate_image_name "$engine" "$candidate_id")"
  found="$(candidate_image_reference "$engine" "$image")" || reject candidate-image-query
  if [ -n "$found" ]; then
    [ "$found" = "$image" ] || reject candidate-image-query
    [ "$("$engine" image inspect --format '{{ index .Config.Labels "io.windowstolinux.application" }}' "$image")" = "$app" ] \
      && [ "$("$engine" image inspect --format '{{ index .Config.Labels "io.windowstolinux.candidate" }}' "$image")" = "$candidate_id" ] || reject candidate-image-owner
    "$engine" image rm "$image" >/dev/null || reject candidate-image-cleanup
    found="$(candidate_image_reference "$engine" "$image")" || reject candidate-image-query
    [ -z "$found" ] || reject candidate-image-remains
  fi
  rm -f -- "$candidate/.image-engine"
}
remove_container_secret_delivery() {
  local directory="$secrets_root/$1/.container-credentials/$2"
  require_app "$1"; require_digest "$2"
  if [ -e "$directory" ] || [ -L "$directory" ]; then
    assert_root_owned_directory "$directory"
    rm -rf --one-file-system -- "$directory"
  fi
}
