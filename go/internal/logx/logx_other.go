//go:build !android

package logx

import "log"

const (
	prioDebug = "D"
	prioInfo  = "I"
	prioWarn  = "W"
	prioError = "E"
)

func write(prio string, msg string) { log.Printf("%s/%s: %s", prio, Tag, msg) }
