#!/usr/bin/env bash
# From the backend root of a disposable exact checkout:
# bash tests/tf09_loopback_isolation/run.sh /absolute/path/to/approved/python
set -euo pipefail
[[ $# == 1 && "$1" = /* && -x "$1" ]] || exit 64
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
backend=$(cd -- "$here/../.." && pwd -P)
[[ "$(pwd -P)" == "$backend" && ! -e .env && ! -L .env && -f tests/qa_tf09_stop_loopback.py ]] || exit 64
root=$(mktemp -d "$backend/.tf09-loopback.XXXXXXXX")
trap 'rm -rf -- "$root"' EXIT
mkdir "$root/home" "$root/tmp"
env -i PATH=/usr/bin:/bin HOME="$root/home" TMPDIR="$root/tmp" TZ=UTC \
  PYTHONPATH="$here:$backend" PYTHONDONTWRITEBYTECODE=1 PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 \
  YULDASH_TF09_LOOPBACK_QA=1 "$1" -s -c '
from pathlib import Path
import os
import sys
import sitecustomize

expected = Path(os.environ["PYTHONPATH"].split(os.pathsep, 1)[0], "sitecustomize.py")
if Path(sitecustomize.__file__).resolve() != expected.resolve() or not sitecustomize.TF09_LOOPBACK_ISOLATION:
    raise SystemExit("fixture isolation not installed")
import pytest
sys.exit(pytest.main(["-q", "-p", "no:cacheprovider", "--tb=short",
                     "tests/qa_tf09_stop_loopback.py", "tests/test_taxi_stop_control.py"]))
'
