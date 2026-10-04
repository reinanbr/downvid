Vendored from github.com/yapingcat/gomedia@v0.0.0-20240906162731-17feea57090c
(MIT, see LICENSE). Only go-mp4, go-mpeg2 and go-codec are kept.

Patches:
- go-mp4/trun-box.go: advance the track's startDts after each trun, so
  fragments with more than one trun get monotonic timestamps (Apple CMAF
  streams use 2 truns per traf; without this every second run restarted at
  the tfdt time, doubling durations and breaking playback).
