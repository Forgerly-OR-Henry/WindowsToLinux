# Copy only the current reviewed task binding; project code never chooses a root input path.
prepare_build_mirror_material() {
    local candidate="$1" uid="$2" gid="$3" script="${4:-.build-script}" marker binding task source destination file path
    marker="$(sed -n 's/^# WTL_MIRROR_BINDING=//p' "$candidate/$script")"
    [ -n "$marker" ] || return 0
    [[ "$marker" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{64}/[0-9a-f]{64}$ ]] || reject mirror-material-binding
    binding="$marker"; task="${binding%%/*}"
    if [ -e "$candidate/.agent-task" ]; then
        assert_root_owned_regular "$candidate/.agent-task"
        [ "$(cat -- "$candidate/.agent-task")" = "$task" ] || reject mirror-material-task
    fi
    source="$base_root/mirror-material/$binding"
    path="$source"
    while [ "$path" != "$base_root" ]; do
        assert_root_owned_directory "$path"
        [ "$(stat -c '%a' -- "$path")" = 700 ] || reject mirror-material-private
        path="${path%/*}"
    done
    destination="$candidate/mutable/tmp/wtl-mirror-material"
    [ ! -e "$destination" ] && [ ! -L "$destination" ] || reject mirror-material-copy-exists
    install -d -o "$uid" -g "$gid" -m 700 -- "$destination" "$destination/$task" \
        "$destination/${binding%/*}" "$destination/$binding"
    for file in "$source"/*; do
        [[ "${file##*/}" =~ ^[0-9a-f]{64}$ ]] || reject mirror-material-reference
        assert_root_owned_regular "$file"
        [ "$(stat -c '%h:%a' -- "$file")" = 1:400 ] || reject mirror-material-file
        install -o "$uid" -g "$gid" -m 400 -- "$file" "$destination/$binding/${file##*/}"
    done
}
cleanup_build_mirror_material() {
    local candidate="$1" directory="$1/mutable/tmp/wtl-mirror-material"
    if [ -e "$directory" ] || [ -L "$directory" ]; then
        [ -d "$directory" ] && [ ! -L "$directory" ] || reject mirror-material-cleanup
        # The build's cgroup has already stopped. Only this task-private directory is removed.
        rm -rf --one-file-system -- "$directory"
        [ ! -e "$directory" ] && [ ! -L "$directory" ] || reject mirror-material-cleanup
    fi
}
