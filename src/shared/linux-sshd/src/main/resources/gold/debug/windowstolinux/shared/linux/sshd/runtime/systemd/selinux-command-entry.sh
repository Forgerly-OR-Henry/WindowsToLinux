  if [ -e /sys/fs/selinux/enforce ]; then
    case "$command" in /usr/bin/env\ *) ;; *) command="/usr/bin/env $command" ;; esac
  fi
