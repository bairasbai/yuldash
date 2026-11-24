#!/bin/sh
# Direct pytest is not supported for the browser witness; fail before loading conftest.
set -eu
[ "$#" = 2 ] || { printf '%s\n' 'TF09 PWA browser: HOLD'; exit 2; }
case $1 in /*) ;; *) exit 2;; esac
case $2 in /*) ;; *) exit 2;; esac
script_dir=$(CDPATH='' cd -- "$(/usr/bin/dirname -- "$0")" && pwd)
exec /usr/bin/env -i PATH=/usr/bin:/bin LANG=C.UTF-8 /usr/bin/python3 -I -S "$script_dir/launch.py" "$1" "$2"
