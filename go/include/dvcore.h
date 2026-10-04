// Public C API of libdvcore.so (Go core). Input for ffigen.
// Keep in sync with the //export functions in go/dvcore/*.go.
//
// Every char* returned by the library must be released with DV_Free.
#ifndef DVCORE_H
#define DVCORE_H

#include <stdint.h>

// Event callback: (request id, json). Ownership of `json` passes to the
// receiver, who must release it with DV_Free.
typedef void (*dv_event_cb)(int64_t id, char* json);

void DV_Free(char* p);

// Library version string.
char* DV_Version(void);

// Synchronous round-trip. Returns {"ok":true,"data":{...}} JSON.
char* DV_Ping(char* msg);

// Emits `count` progress events and a final "done" event on `cb` from a
// background goroutine. Returns immediately.
void DV_PingAsync(int64_t id, int32_t count, dv_event_cb cb);

// Scans a page and/or resolves candidate URLs into download options.
// Blocking (network) — call from a background isolate.
// Input:  {"pageUrl","candidates":[{"url","source"}],"headers":{...},"skipPage"}
// Output: {"ok":true,"data":{"title","thumbnail","options":[...],"skipped":[...]}}
char* DV_Probe(char* request_json);

// Starts a background download. Events (progress/done/error/canceled) are
// delivered on `cb` (tagged with the job id) until DV_Detach or the terminal
// event. Returns {"ok":true,"data":{"id":N}} immediately.
// Input: {"kind":"mp4|hls","url","audioUrl","headers":{...},"outPath","tmpDir"}
char* DV_Download(char* request_json, dv_event_cb cb);

// Stops event delivery for a download; it keeps running in the background.
// `cb` is never called for this job after DV_Detach returns.
void DV_Detach(int64_t id);

// Cancels a running download. Returns 1 if it was running.
int32_t DV_Cancel(int64_t id);

// Converts `yt-dlp -J` output into download options.
// Input:  {"json","stderr","exitCode","platform"}
// Output: {"ok":true,"data":{"title","thumbnail","options":[...],"extractor"}}
//         or {"ok":false,"error":"<friendly message>","unsupported":bool}
char* DV_Ytdlp(char* request_json);

// Song metadata for "audio only": {"basic":{...},"override":{...}|null}
// -> {"title","artist","album","albumArtist","year","track","coverUrl",...}
// (Deezer + iTunes lookup; blocking).
char* DV_MusicMeta(char* request_json);

// Spotify/Deezer/Apple Music track link -> {"meta":{...},"searchUrl"}.
char* DV_MusicLink(char* request_json);

// Ranks a flat YouTube Music search: {"searchJson","meta","max"}
// -> {"candidates":[url...]}.
char* DV_MusicPick(char* request_json);

// Instagram (public posts, logged-out query run by the app's WebView):
// DV_InstaQuery {"url"} -> query parameters; DV_InstaParse {"body"} -> options.
char* DV_InstaQuery(char* request_json);
char* DV_InstaParse(char* request_json);

// Persistent queue: pause (keeps partial data), resume (continues it),
// remove (deletes the record and partial data). Return 1 on success.
int32_t DV_Pause(int64_t id);
int32_t DV_Resume(int64_t id);
void DV_Remove(int64_t id);

// New source URLs for an expired job: {"id","url","audioUrl","headers","chunkSize"}.
char* DV_Replace(char* request_json);

// Maximum number of simultaneous downloads.
void DV_SetMaxConcurrent(int32_t n);

// Status of every known job: {"ok":true,"data":[{"id","state",...event}]}.
char* DV_Status(void);

#endif
