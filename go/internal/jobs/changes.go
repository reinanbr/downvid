package jobs

import (
	"sort"
	"sync"
	"time"
)

// Change tracking: every job change gets a new version number, so the app
// can ask "what changed since version N?" and block until something does
// (Await) instead of polling the whole queue.

var (
	verMu   sync.Mutex     // leaf lock: may be taken while holding mu or j.mu
	version int64      = 1 // 0 means "no version" to Await
	notify             = make(chan struct{})
	removed            = map[int64]int64{} // job id -> version of its removal
)

const removedLimit = 1000

// bump starts a new version and wakes every Await.
func bump() int64 {
	verMu.Lock()
	defer verMu.Unlock()
	version++
	close(notify)
	notify = make(chan struct{})
	return version
}

// changed records that j changed (j.mu held).
func (j *job) changed() { j.ver = bump() }

// forgotten records that a job left the registry.
func forgotten(id int64) {
	v := bump()
	verMu.Lock()
	removed[id] = v
	if len(removed) > removedLimit {
		// Drop the oldest half; clients that far behind get a full list.
		var vs []int64
		for _, rv := range removed {
			vs = append(vs, rv)
		}
		sort.Slice(vs, func(a, b int) bool { return vs[a] < vs[b] })
		cut := vs[len(vs)/2]
		for k, rv := range removed {
			if rv <= cut {
				delete(removed, k)
			}
		}
	}
	verMu.Unlock()
}

// Changes is what Await returns.
type Changes struct {
	Version int64 `json:"version"`
	// Full: Jobs is the complete list (the caller replaces its copy);
	// otherwise only the jobs changed since the requested version.
	Full    bool     `json:"full,omitempty"`
	Jobs    []Status `json:"jobs"`
	Removed []int64  `json:"removed,omitempty"`
}

// Await returns the changes after version `since`, waiting up to timeout
// for one when there is none. since 0 (or a version this process never
// issued) returns the full list.
func Await(since int64, timeout time.Duration) Changes {
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	for {
		verMu.Lock()
		v, ch := version, notify
		verMu.Unlock()
		if since <= 0 || since > v {
			return Changes{Version: v, Full: true, Jobs: Snapshot()}
		}
		if v > since {
			return changesSince(since)
		}
		select {
		case <-ch:
		case <-timer.C:
			return Changes{Version: v, Jobs: []Status{}}
		}
	}
}

func changesSince(since int64) Changes {
	verMu.Lock()
	v := version
	minRemoved := v
	var gone []int64
	for id, rv := range removed {
		minRemoved = min(minRemoved, rv)
		if rv > since {
			gone = append(gone, id)
		}
	}
	tooOld := len(removed) >= removedLimit/2 && since < minRemoved
	verMu.Unlock()
	if tooOld {
		return Changes{Version: v, Full: true, Jobs: Snapshot()}
	}

	mu.Lock()
	list := make([]*job, 0, len(jobs))
	for _, j := range jobs {
		list = append(list, j)
	}
	mu.Unlock()
	out := []Status{}
	for _, j := range list {
		j.mu.Lock()
		if j.ver > since {
			out = append(out, j.status())
		}
		j.mu.Unlock()
	}
	sort.Slice(out, func(a, b int) bool { return out[a].ID > out[b].ID })
	return Changes{Version: v, Jobs: out, Removed: gone}
}
