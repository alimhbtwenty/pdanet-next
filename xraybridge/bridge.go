// Package xraybridge embeds Xray-core in the PdaNext phone app (ADR-0001).
//
// Xray runs in-process with outbounds only: no inbound, no listener, no proxy, no TUN. The phone's
// userspace router terminates every PC flow and opens the tunnelled ones here, with DialTCP and
// DialUDP. The API only uses types gomobile can bind (string, bool, int, int64, []byte, error and
// exported structs), so the package builds into an Android AAR.
//
// Configs, share links, UUIDs and keys are secrets: no error returned from this package contains
// them (Xray's own builder errors can quote config values, so they are replaced).
package xraybridge

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/xtls/libxray/share"
	libxray "github.com/xtls/libxray/xray"
	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf"
	confjson "github.com/xtls/xray-core/infra/conf/json"
	_ "github.com/xtls/xray-core/main/distro/all"
)

var (
	mu       sync.Mutex
	instance *core.Instance

	errNotRunning = errors.New("xray is not running")
	errInvalid    = errors.New("invalid Xray config")
	errInbounds   = errors.New("Xray config must not contain inbounds")
	errTimeout    = errors.New("timeout")
	errClosed     = errors.New("closed")
)

// Start starts Xray with configJSON, replacing a running instance. The config may not have inbounds.
func Start(configJSON string) error {
	if err := checkNoInbounds(configJSON); err != nil {
		return err
	}
	config, err := core.LoadConfig("json", strings.NewReader(configJSON))
	if err != nil {
		return errInvalid
	}
	inst, err := core.New(config)
	if err != nil {
		return errInvalid
	}
	if err := inst.Start(); err != nil {
		_ = inst.Close()
		return errors.New("xray failed to start")
	}
	mu.Lock()
	old := instance
	instance = inst
	mu.Unlock()
	if old != nil {
		_ = old.Close()
	}
	return nil
}

// Stop stops Xray. Calling it when nothing runs does nothing.
func Stop() {
	mu.Lock()
	old := instance
	instance = nil
	mu.Unlock()
	if old != nil {
		_ = old.Close()
	}
}

// IsRunning reports whether an Xray instance is running.
func IsRunning() bool {
	inst := current()
	return inst != nil && inst.IsRunning()
}

func current() *core.Instance {
	mu.Lock()
	defer mu.Unlock()
	return instance
}

func checkNoInbounds(configJSON string) error {
	var doc map[string]json.RawMessage
	dec := json.NewDecoder(&confjson.Reader{Reader: strings.NewReader(configJSON)})
	if err := dec.Decode(&doc); err != nil {
		return errInvalid
	}
	if raw, ok := doc["inbounds"]; ok {
		var inbounds []json.RawMessage
		if err := json.Unmarshal(raw, &inbounds); err != nil || len(inbounds) > 0 {
			return errInbounds
		}
	}
	return nil
}

// --- TCP ---

// Conn is a TCP stream through the tunnel.
type Conn struct {
	c net.Conn
}

// DialTCP opens a TCP stream to host:port through the running instance. Xray connects lazily: a
// failure to reach the destination shows up as an error or EOF on the first Read. timeoutMs bounds
// the dispatch only.
func DialTCP(host string, port int, timeoutMs int) (*Conn, error) {
	inst := current()
	if inst == nil {
		return nil, errNotRunning
	}
	dest := xnet.TCPDestination(xnet.ParseAddress(host), xnet.Port(port))
	type result struct {
		conn net.Conn
		err  error
	}
	done := make(chan result, 1)
	go func() {
		c, err := core.Dial(context.Background(), inst, dest)
		done <- result{c, err}
	}()
	select {
	case r := <-done:
		if r.err != nil {
			return nil, errors.New("dial failed")
		}
		return &Conn{c: r.conn}, nil
	case <-time.After(time.Duration(maxInt(timeoutMs, 1)) * time.Millisecond):
		go func() {
			if r := <-done; r.conn != nil {
				_ = r.conn.Close()
			}
		}()
		return nil, errTimeout
	}
}

// Read returns up to max bytes. An empty result with a nil error means EOF. (gomobile copies
// []byte arguments, so the data is returned instead of filling a caller's buffer.)
func (c *Conn) Read(max int) ([]byte, error) {
	buf := make([]byte, maxInt(max, 1))
	for {
		n, err := c.c.Read(buf)
		if n > 0 {
			return buf[:n], nil // a pending error is returned by the next call
		}
		if err == io.EOF {
			return []byte{}, nil
		}
		if err != nil {
			return nil, err
		}
	}
}

// Write sends b and returns the number of bytes written.
func (c *Conn) Write(b []byte) (int, error) { return c.c.Write(b) }

// CloseWrite does nothing on purpose. Xray outbounds do not deliver a FIN to the destination, and
// ending the uplink would only start Xray's "downlink only" timer (1 s of idle by default), which
// cuts responses that take longer. The stream ends with Close.
func (c *Conn) CloseWrite() error { return nil }

// Close closes both directions.
func (c *Conn) Close() error { return c.c.Close() }

// --- UDP ---

// PacketConn is a UDP flow to one destination through the tunnel.
type PacketConn struct {
	pc   net.PacketConn
	dest *net.UDPAddr
	in   chan []byte
	done chan struct{}
	once sync.Once
}

// DialUDP opens a UDP flow to host:port (host must be an IP address).
func DialUDP(host string, port int) (*PacketConn, error) {
	inst := current()
	if inst == nil {
		return nil, errNotRunning
	}
	ip := net.ParseIP(host)
	if ip == nil {
		return nil, errors.New("host must be an IP address")
	}
	pc, err := core.DialUDP(context.Background(), inst)
	if err != nil {
		return nil, errors.New("dial failed")
	}
	p := &PacketConn{pc: pc, dest: &net.UDPAddr{IP: ip, Port: port}, in: make(chan []byte, 64), done: make(chan struct{})}
	go p.readLoop()
	return p, nil
}

func (p *PacketConn) readLoop() {
	buf := make([]byte, 65535)
	for {
		n, _, err := p.pc.ReadFrom(buf)
		if err != nil {
			_ = p.Close()
			return
		}
		pkt := make([]byte, n)
		copy(pkt, buf[:n])
		select {
		case p.in <- pkt:
		case <-p.done:
			return
		default: // receiver too slow: drop, as a real socket buffer would
		}
	}
}

// Send sends one datagram.
func (p *PacketConn) Send(b []byte) error {
	select {
	case <-p.done:
		return errClosed
	default:
	}
	_, err := p.pc.WriteTo(b, p.dest)
	return err
}

// Receive waits up to timeoutMs for a datagram. On timeout the error message is "timeout".
func (p *PacketConn) Receive(timeoutMs int) ([]byte, error) {
	timer := time.NewTimer(time.Duration(maxInt(timeoutMs, 1)) * time.Millisecond)
	defer timer.Stop()
	select {
	case pkt := <-p.in:
		return pkt, nil
	case <-p.done:
		return nil, errClosed
	case <-timer.C:
		return nil, errTimeout
	}
}

// Close ends the flow.
func (p *PacketConn) Close() error {
	p.once.Do(func() {
		close(p.done)
		_ = p.pc.Close()
	})
	return nil
}

// --- Links and delay ---

type profile struct {
	Name   string `json:"name"`
	Config string `json:"config"`
}

// ImportLinks turns share links (vless://, vmess://, trojan://, ss://, ... one per line) or a
// base64 subscription into a JSON array of {"name", "config"}; each config is a full Xray JSON
// config with that single outbound. Lines that cannot be parsed are skipped.
func ImportLinks(text string) (string, error) {
	raw, err := share.ConvertShareLinksToXrayJson(text, "")
	if err != nil {
		return "", errors.New("no valid link found")
	}
	var doc struct {
		Outbounds []conf.OutboundDetourConfig `json:"outbounds"`
	}
	if err := json.Unmarshal(raw, &doc); err != nil || len(doc.Outbounds) == 0 {
		return "", errors.New("no valid link found")
	}
	profiles := make([]profile, 0, len(doc.Outbounds))
	for i, ob := range doc.Outbounds {
		name := strings.TrimSpace(ob.Tag)
		if name == "" {
			name = "Server " + strconv.Itoa(i+1)
		}
		ob.Tag = "proxy"
		cfg, err := json.Marshal(map[string]any{
			"log":       map[string]string{"loglevel": "warning", "access": "none"},
			"outbounds": []conf.OutboundDetourConfig{ob},
		})
		if err != nil {
			continue
		}
		profiles = append(profiles, profile{Name: name, Config: string(cfg)})
	}
	out, err := json.Marshal(profiles)
	if err != nil {
		return "", errors.New("no valid link found")
	}
	return string(out), nil
}

// MeasureDelay fetches url through configJSON with a separate, temporary instance (the running one
// is not touched) and returns the delay in milliseconds.
func MeasureDelay(configJSON, url string, timeoutMs int) (int64, error) {
	if err := checkNoInbounds(configJSON); err != nil {
		return 0, err
	}
	seconds := (maxInt(timeoutMs, 1) + 999) / 1000
	results, err := libxray.PingBatch([]libxray.PingBatchItem{{XrayJSON: configJSON}}, seconds, url)
	if err != nil || len(results) != 1 {
		return 0, errors.New("delay test failed")
	}
	if !results[0].Success {
		return 0, errors.New("delay test failed")
	}
	return results[0].Delay, nil
}

// Version returns the embedded Xray-core version.
func Version() string { return core.Version() }

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}
