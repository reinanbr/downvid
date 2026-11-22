// Package main is the DownVid native core, built as a c-shared library
// (libdvcore.so) and loaded by the Kotlin app through JNI (jni_android.go).
// Its whole API is call(method, JSON) in api.go.
package main

// Version is overridden at build time via -ldflags "-X main.Version=...".
var Version = "0.1.0-dev"

func main() {}
