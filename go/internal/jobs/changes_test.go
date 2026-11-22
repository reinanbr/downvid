package jobs

import (
	"crypto/rand"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"
)

func TestAwaitReturnsOnlyChangesAndBlocksWhenIdle(t *testing.T) {
	dir := reset(t)
	data := make([]byte, 1<<20)
	rand.Read(data)
	var served atomic.Int64
	srv := slowServer(data, &served)
	defer srv.Close()

	first := Await(0, time.Second)
	if !first.Full {
		t.Fatal("since=0 must return the full list")
	}

	id, err := Start(Request{Kind: "mp4", URL: srv.URL, OutPath: filepath.Join(dir, "a.mp4")}, Meta{Title: "a"}, nil)
	if err != nil {
		t.Fatal(err)
	}
	c := Await(first.Version, time.Second)
	if c.Full || len(c.Jobs) != 1 || c.Jobs[0].ID != id {
		t.Fatalf("expected one changed job, got %+v", c)
	}

	// Follow the job to the end through Await alone.
	since := c.Version
	deadline := time.Now().Add(20 * time.Second)
	state := ""
	for state != StateDone && time.Now().Before(deadline) {
		c = Await(since, 5*time.Second)
		since = c.Version
		for _, s := range c.Jobs {
			if s.ID == id {
				state = s.State
			}
		}
	}
	if state != StateDone {
		t.Fatalf("job did not finish through Await (state %q)", state)
	}

	// Nothing running: Await must block until the timeout.
	t0 := time.Now()
	c = Await(since, 300*time.Millisecond)
	if el := time.Since(t0); el < 250*time.Millisecond || len(c.Jobs) != 0 {
		t.Fatalf("idle Await returned after %s with %d jobs", el, len(c.Jobs))
	}

	// A change wakes a waiting Await right away.
	go func() {
		time.Sleep(100 * time.Millisecond)
		Remove(id)
	}()
	t0 = time.Now()
	c = Await(since, 5*time.Second)
	if el := time.Since(t0); el > 2*time.Second {
		t.Fatalf("Await woke after %s", el)
	}
	if len(c.Removed) != 1 || c.Removed[0] != id {
		t.Fatalf("removal not reported: %+v", c)
	}
}
