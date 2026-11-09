#!/bin/sh
set -eu
for item in cpu.stat cpu.max cpuset.cpus.effective memory.current memory.peak memory.max memory.events; do
  printf '%s=' "$item"
  if [ -r "/sys/fs/cgroup/$item" ]; then tr '\n' ';' < "/sys/fs/cgroup/$item"; else printf 'UNAVAILABLE'; fi
  printf '\n'
done
while IFS= read -r line; do
  case "$line" in Cpus_allowed_list:*|Cpus_allowed:*) printf '%s\n' "$line";; esac
done < /proc/1/status
for cpu in 0 1; do
  for item in core_id physical_package_id thread_siblings_list; do
    path="/sys/devices/system/cpu/cpu$cpu/topology/$item"
    if [ -r "$path" ]; then printf 'cpu%s.%s=' "$cpu" "$item"; cat "$path"; fi
  done
done
