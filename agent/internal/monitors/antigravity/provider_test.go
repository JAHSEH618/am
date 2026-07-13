package antigravity

import (
	"encoding/base64"
	"testing"
	"time"
)

func TestMergeTrajectoryIndex(t *testing.T) {
	id := "abc-123"
	created := protoMessage(protoVarint(1, 100), protoVarint(2, 0))
	updated := protoMessage(protoVarint(1, 200), protoVarint(2, 0))
	workspace := protoMessage(protoBytes(1, []byte("file:///tmp/demo")))
	payload := protoMessage(protoBytes(1, []byte("Demo chat")), protoBytes(3, updated), protoBytes(4, []byte(id)), protoBytes(7, created), protoBytes(9, workspace))
	wrapper := protoMessage(protoBytes(1, []byte(base64.StdEncoding.EncodeToString(payload))))
	entry := protoMessage(protoBytes(1, []byte(id)), protoBytes(2, wrapper))
	outer := protoMessage(protoBytes(1, entry))
	out := map[string]*summary{}
	mergeTrajectoryIndex(out, []byte(base64.StdEncoding.EncodeToString(outer)))
	s := out[id]
	if s == nil {
		t.Fatal("missing summary")
	}
	if s.Title != "Demo chat" || s.CWD != "/tmp/demo" {
		t.Fatalf("summary=%+v", s)
	}
	if !s.CreatedAt.Equal(time.Unix(100, 0)) || !s.UpdatedAt.Equal(time.Unix(200, 0)) {
		t.Fatalf("times=%v/%v", s.CreatedAt, s.UpdatedAt)
	}
}

func protoMessage(parts ...[]byte) []byte {
	var out []byte
	for _, p := range parts {
		out = append(out, p...)
	}
	return out
}
func protoBytes(n int, b []byte) []byte {
	return append(append(protoUvarint(uint64(n<<3|2)), protoUvarint(uint64(len(b)))...), b...)
}
func protoVarint(n int, v uint64) []byte {
	return append(protoUvarint(uint64(n<<3)), protoUvarint(v)...)
}
func protoUvarint(v uint64) []byte {
	var out []byte
	for v >= 0x80 {
		out = append(out, byte(v)|0x80)
		v >>= 7
	}
	return append(out, byte(v))
}
