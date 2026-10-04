// Package logx writes to logcat on Android (tag "DownVid-Go") and to stderr
// elsewhere. Filter on the device with: adb logcat -s DownVid-Go
package logx

import "fmt"

const Tag = "DownVid-Go"

func Debugf(format string, args ...any) { write(prioDebug, fmt.Sprintf(format, args...)) }
func Infof(format string, args ...any)  { write(prioInfo, fmt.Sprintf(format, args...)) }
func Warnf(format string, args ...any)  { write(prioWarn, fmt.Sprintf(format, args...)) }
func Errorf(format string, args ...any) { write(prioError, fmt.Sprintf(format, args...)) }
