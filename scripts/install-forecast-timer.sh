#!/usr/bin/env bash
set -euo pipefail
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
unit_dir="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
case ${1:-status} in
  install|render)
    env_file=$(realpath "${2:?Supply a private weekly environment file}")
    if [[ "$1" == render ]]; then unit_dir=${3:?Supply the staging directory for unit verification}; fi
    [[ $(stat -c %a "$env_file") == 600 ]] || { echo 'Use chmod 600 on the weekly environment file' >&2; exit 2; }
    if [[ "$1" == install ]]; then bash "$repo/scripts/forecast-weekly-preflight.sh" "$env_file"; fi
    set -a; source "$env_file"; set +a
    : "${WATTLY_ML_HOME:?Set the runtime directory in the environment file}"
    mkdir -p "$unit_dir" "$WATTLY_ML_HOME"
    python3 - "$unit_dir" "$repo" "$env_file" "$WATTLY_ML_HOME" <<'PY'
import json,os,sys
from pathlib import Path
directory,repo,env_file,runtime=map(Path,sys.argv[1:])
if any('\n' in str(p) or '\r' in str(p) for p in (directory,repo,env_file,runtime)):
    raise ValueError('Unit paths cannot contain newlines')
quote=lambda p:json.dumps(str(p.resolve()).replace('%','%%'))
service='''[Unit]
Description=Wattly weekly forecasting candidate (local only)
After=network.target
[Service]
Type=oneshot
ExecStart=/usr/bin/bash %s %s
WorkingDirectory=%s
TimeoutStartSec=47min
CPUQuota=100%%
MemoryMax=2G
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ReadWritePaths=%s
'''%(quote(repo/'scripts/forecast-weekly-scheduled.sh'),quote(env_file),str(repo.resolve()).replace('%','%%'),quote(runtime))
calendar=os.environ.get('WATTLY_TIMER_CALENDAR','Sun *-*-* 03:00:00 Asia/Singapore')
if '\n' in calendar: raise ValueError('Invalid calendar')
(directory/'wattly-forecast-weekly.service').write_text(service)
(directory/'wattly-forecast-weekly.timer').write_text('[Unit]\nDescription=Weekly Wattly model validation\n[Timer]\nOnCalendar='+calendar+'\nPersistent=true\nUnit=wattly-forecast-weekly.service\n[Install]\nWantedBy=timers.target\n')
PY
    systemd-analyze --user verify "$unit_dir/wattly-forecast-weekly.service" "$unit_dir/wattly-forecast-weekly.timer"
    if [[ "$1" == render ]]; then echo "Verified units in $unit_dir"; exit 0; fi
    systemctl --user daemon-reload
    systemctl --user enable --now wattly-forecast-weekly.timer
    systemctl --user list-timers wattly-forecast-weekly.timer
    ;;
  status) systemctl --user status wattly-forecast-weekly.timer wattly-forecast-weekly.service ;;
  uninstall)
    systemctl --user disable --now wattly-forecast-weekly.timer
    # Retain unit files and every model/audit record for rollback and inspection.
    systemctl --user daemon-reload
    echo 'Timer disabled. Artifacts and unit files retained.'
    ;;
  *) echo 'Use install ENV_FILE, render ENV_FILE DIRECTORY, status, or uninstall' >&2; exit 2 ;;
esac
