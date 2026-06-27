package collector

import (
	"testing"
	"time"

	"github.com/Aditya1711-tech/cadence/agent/internal/event"
)

// scriptedWatcher / scriptedIdle return one programmed reading per Sample,
// holding the last value once the script is exhausted.
type scripted struct {
	wins  []Window
	idles []float64
	wi    int
	ii    int
}

func (s *scripted) Active() (Window, error) {
	w := s.wins[min(s.wi, len(s.wins)-1)]
	s.wi++
	return w, nil
}

func (s *scripted) IdleSeconds() (float64, error) {
	v := s.idles[min(s.ii, len(s.idles)-1)]
	s.ii++
	return v, nil
}

type capture struct{ events []event.Event }

func (c *capture) sink(batch []event.Event) error {
	c.events = append(c.events, batch...)
	return nil
}

func win(app, title string) Window { return Window{App: app, Title: title, HasTitle: title != ""} }

func TestSegmentationAndBackdatedIdle(t *testing.T) {
	t0 := time.Date(2025, 6, 1, 9, 0, 0, 0, time.UTC)
	scr := &scripted{
		wins: []Window{
			win("Code", "a.go"), // 1
			win("Chrome", "x"),  // 2
			win("Chrome", "x"),  // 3
			win("Chrome", "x"),  // 4
			win("Code", "b.go"), // 5
		},
		idles: []float64{0, 0, 0, 300, 0},
	}
	cap := &capture{}
	c := New(scr, scr, cap.sink, Config{MemberID: "m1", IdleThreshold: 5 * time.Minute})

	c.Sample(t0)                        // open Code
	c.Sample(t0.Add(5 * time.Second))   // -> emit Code[t0,+5s]; open Chrome
	c.Sample(t0.Add(65 * time.Second))  // no change
	c.Sample(t0.Add(365 * time.Second)) // idle 300 -> boundary +65s; emit Chrome active[+5s,+65s]; open Chrome idle
	c.Sample(t0.Add(400 * time.Second)) // window change -> emit Chrome idle[+65s,+400s]; open Code
	c.Close(t0.Add(410 * time.Second))  // emit Code[+400s,+410s]

	got := cap.events
	if len(got) != 4 {
		t.Fatalf("got %d events, want 4: %+v", len(got), got)
	}

	checks := []struct {
		app   string
		idle  bool
		durMs int64
		title string
	}{
		{"Code", false, 5000, "a.go"},
		{"Chrome", false, 60000, "x"},
		{"Chrome", true, 335000, "x"},
		{"Code", false, 10000, "b.go"},
	}
	for i, want := range checks {
		e := got[i]
		if e.Source != event.SourceOS {
			t.Errorf("event %d source = %q, want os", i, e.Source)
		}
		if e.App != want.app {
			t.Errorf("event %d app = %q, want %q", i, e.App, want.app)
		}
		if e.IsIdle != want.idle {
			t.Errorf("event %d is_idle = %v, want %v", i, e.IsIdle, want.idle)
		}
		if e.DurationMs != want.durMs {
			t.Errorf("event %d duration = %d, want %d", i, e.DurationMs, want.durMs)
		}
		if e.Title == nil || *e.Title != want.title {
			t.Errorf("event %d title = %v, want %q", i, e.Title, want.title)
		}
		if e.Category != nil {
			t.Errorf("event %d category should be nil (classified at ingest), got %v", i, *e.Category)
		}
	}
}

func TestMeetingSuppressesIdle(t *testing.T) {
	t0 := time.Date(2025, 6, 1, 10, 0, 0, 0, time.UTC)
	scr := &scripted{
		wins:  []Window{win("zoom.us", "Standup")},
		idles: []float64{600}, // 10 min of no input
	}
	cap := &capture{}
	// 5-min idle threshold, but meeting ceiling is 30 min, so 600s is NOT idle.
	c := New(scr, scr, cap.sink, Config{MemberID: "m1", IdleThreshold: 5 * time.Minute, MeetingCeiling: 30 * time.Minute})
	c.Sample(t0)
	c.Close(t0.Add(time.Minute))
	if len(cap.events) != 1 {
		t.Fatalf("want 1 event, got %d", len(cap.events))
	}
	if cap.events[0].IsIdle {
		t.Error("meeting participant marked idle despite no input; suppression failed")
	}
}

func TestNoTitleProducesNullTitle(t *testing.T) {
	t0 := time.Date(2025, 6, 1, 11, 0, 0, 0, time.UTC)
	scr := &scripted{
		wins:  []Window{{App: "SomeApp", HasTitle: false}},
		idles: []float64{0},
	}
	cap := &capture{}
	c := New(scr, scr, cap.sink, Config{MemberID: "m1"})
	c.Sample(t0)
	c.Close(t0.Add(time.Second))
	if len(cap.events) != 1 {
		t.Fatalf("want 1 event, got %d", len(cap.events))
	}
	if cap.events[0].Title != nil {
		t.Errorf("expected null title when HasTitle=false, got %v", *cap.events[0].Title)
	}
}

func TestEmittedEventsValidate(t *testing.T) {
	t0 := time.Date(2025, 6, 1, 12, 0, 0, 0, time.UTC)
	scr := &scripted{wins: []Window{win("Code", "x"), win("Term", "y")}, idles: []float64{0, 0}}
	cap := &capture{}
	c := New(scr, scr, cap.sink, Config{MemberID: "m1"})
	c.Sample(t0)
	c.Sample(t0.Add(10 * time.Second))
	c.Close(t0.Add(20 * time.Second))
	if len(cap.events) == 0 {
		t.Fatal("no events emitted")
	}
	for i := range cap.events {
		e := cap.events[i]
		if err := e.Validate(); err != nil {
			t.Errorf("emitted event %d fails contract validation: %v", i, err)
		}
	}
}
