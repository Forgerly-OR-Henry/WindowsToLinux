package files

import (
	"context"
	"io"
	"net/http"
	"time"
)

type chunkPolicy struct{ body, idle, post time.Duration }

var uploadPolicy = chunkPolicy{240 * time.Second, 30 * time.Second, 30 * time.Second}

type chunkBudgetKey struct{}

func chunkContextError(ctx context.Context) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if deadline, ok := ctx.Deadline(); ok && !time.Now().Before(deadline) {
		return context.DeadlineExceeded
	}
	return nil
}

// Body and post deadlines have distinct parents. Neither a positive read nor a late lock
// acquisition can restart the absolute body allowance.
type chunkBudget struct {
	parent                     context.Context
	body                       context.Context
	cancelBody                 context.CancelFunc
	post                       context.Context
	cancelPost                 context.CancelFunc
	controller                 *http.ResponseController
	policy                     chunkPolicy
	bodyDeadline, lastPositive time.Time
	postDeadline               time.Time
	bodyComplete               bool
	readStarted                bool
}

func startChunkBudget(w http.ResponseWriter, r *http.Request, policy chunkPolicy) (*chunkBudget, error) {
	now := time.Now()
	b := &chunkBudget{parent: r.Context(), controller: http.NewResponseController(w), policy: policy,
		bodyDeadline: now.Add(policy.body), lastPositive: now}
	if err := b.controller.EnableFullDuplex(); err != nil {
		return nil, err
	}
	if err := b.controller.SetReadDeadline(minTime(b.bodyDeadline, now.Add(policy.idle))); err != nil {
		return nil, err
	}
	if err := b.controller.SetWriteDeadline(b.bodyDeadline.Add(policy.post)); err != nil {
		return nil, err
	}
	b.body, b.cancelBody = context.WithDeadline(b.parent, b.bodyDeadline)
	return b, nil
}
func minTime(a, b time.Time) time.Time {
	if a.Before(b) {
		return a
	}
	return b
}
func (b *chunkBudget) close() {
	b.cancelBody()
	if b.cancelPost != nil {
		b.cancelPost()
	}
}
func (b *chunkBudget) bodyError() error {
	if err := b.body.Err(); err != nil {
		return err
	}
	if !time.Now().Before(b.bodyDeadline) {
		return context.DeadlineExceeded
	}
	return nil
}
func (b *chunkBudget) beginPost() (context.Context, error) {
	if err := b.bodyError(); err != nil {
		return nil, err
	}
	b.postDeadline = time.Now().Add(b.policy.post)
	if err := b.controller.SetWriteDeadline(b.postDeadline); err != nil {
		return nil, err
	}
	b.post, b.cancelPost = context.WithDeadline(b.parent, b.postDeadline)
	b.bodyComplete = true
	return b.post, nil
}
func (b *chunkBudget) postError() error {
	if !b.bodyComplete {
		return context.Canceled
	}
	if err := b.post.Err(); err != nil {
		return err
	}
	if !time.Now().Before(b.postDeadline) {
		return context.DeadlineExceeded
	}
	return nil
}
func (b *chunkBudget) errorResponse(w http.ResponseWriter) {
	// An incomplete HTTP/1 body must not trigger net/http's implicit unbounded drain.
	if !b.bodyComplete && b.readStarted {
		w.Header().Set("Connection", "close")
		_ = b.controller.SetReadDeadline(time.Now())
	}
	_ = b.controller.SetWriteDeadline(time.Now().Add(b.policy.post))
}

type chunkBudgetReader struct {
	source io.Reader
	budget *chunkBudget
}

func (r chunkBudgetReader) Read(p []byte) (int, error) {
	b := r.budget
	b.readStarted = true
	if err := b.bodyError(); err != nil {
		return 0, err
	}
	deadline := minTime(b.bodyDeadline, b.lastPositive.Add(b.policy.idle))
	if !time.Now().Before(deadline) {
		return 0, context.DeadlineExceeded
	}
	if err := b.controller.SetReadDeadline(deadline); err != nil {
		return 0, err
	}
	n, err := r.source.Read(p)
	if !time.Now().Before(deadline) {
		return n, context.DeadlineExceeded
	}
	if n > 0 {
		b.lastPositive = time.Now()
	}
	if expired := b.bodyError(); expired != nil {
		return n, expired
	}
	return n, err
}

func chunkRoute(s *Store, policy chunkPolicy) http.HandlerFunc {
	inner := route(func(r *http.Request) (any, error) {
		n, err := chunkNumber(r.PathValue("number"))
		if err != nil {
			return nil, err
		}
		return s.Chunk(r.Context(), r.PathValue("id"), n, r.Body)
	})
	return func(w http.ResponseWriter, r *http.Request) {
		b, err := startChunkBudget(w, r, policy)
		if err != nil {
			reply(w, 500, map[string]string{"error": "上传期限保护不可用"})
			return
		}
		defer b.close()
		r = r.WithContext(context.WithValue(b.body, chunkBudgetKey{}, b))
		r.Body = struct {
			io.Reader
			io.Closer
		}{chunkBudgetReader{r.Body, b}, r.Body}
		inner(w, r)
	}
}
