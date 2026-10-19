#!/bin/sh
# CPU, scheduler and memory-bus setup: the parts of Android's init.bullhead.power.sh
# that still apply without Android. Cluster 0 = cpu0-3 (A53, 1.44 GHz), cluster 1 =
# cpu4-5 (A57, 1.82 GHz). Thermal protection stays in the kernel (msm_thermal:
# throttle at 60 C, cores off at 80 C, reset at 115 C).
w() { [ -e "$1" ] && echo "$2" > "$1" || echo "skip $1"; }
C=/sys/devices/system/cpu

w $C/cpu4/online 1
w $C/cpu5/online 1

# interactive governor with Android's tunables, per cluster
for c in 0 4; do
	w $C/cpu$c/cpufreq/scaling_governor interactive
	I=$C/cpu$c/cpufreq/interactive
	w $I/use_sched_load 1
	w $I/use_migration_notif 1
	w $I/above_hispeed_delay 19000
	w $I/go_hispeed_load 99
	w $I/timer_rate 20000
	w $I/min_sample_time 40000
	w $I/max_freq_hysteresis 80000
done
w $C/cpu0/cpufreq/interactive/hispeed_freq 960000
w $C/cpu0/cpufreq/interactive/target_loads "65 460800:75 960000:80"
w $C/cpu0/cpufreq/scaling_min_freq 384000
w $C/cpu4/cpufreq/interactive/hispeed_freq 1248000
w $C/cpu4/cpufreq/interactive/target_loads "70 960000:80 1248000:85"
w $C/cpu4/cpufreq/scaling_min_freq 633600
cat $C/cpu4/cpufreq/cpuinfo_max_freq > $C/cpu4/cpufreq/scaling_max_freq

# HMP scheduler: busy threads up-migrate to the A57s
w /proc/sys/kernel/sched_migration_fixup 1
w /proc/sys/kernel/sched_upmigrate 95
w /proc/sys/kernel/sched_downmigrate 85
w /proc/sys/kernel/sched_boost 0

# DDR bandwidth follows CPU memory traffic (token generation is bandwidth bound)
for g in /sys/class/devfreq/qcom,cpubw*/governor; do w "$g" bw_hwmon; done

# Idle power states (the stock cmdline boots with them off). Android first turns
# off CPU and L2 retention (init.bullhead.power.sh), then allows deeper idle at
# boot_completed. Allowing idle with retention still on hung the phone: the
# apps watchdog barked ~11 s into the boot.
for c in /sys/module/lpm_levels/system/a5?/cpu?/retention/idle_enabled \
	/sys/module/lpm_levels/system/a53/a53-l2-retention/idle_enabled \
	/sys/module/lpm_levels/system/a57/a57-l2-retention/idle_enabled; do
	w "$c" 0
done
w /sys/module/lpm_levels/parameters/sleep_disabled 0

grep -H . $C/cpu*/cpufreq/scaling_governor /sys/class/devfreq/*/governor 2>/dev/null
