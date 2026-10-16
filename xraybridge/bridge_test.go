package xraybridge

import (
	"encoding/json"
	"io"
	"net"
	"strconv"
	"strings"
	"testing"
	"time"
)

// A direct ("freedom") outbound: lets the tests carry real traffic to local servers.
const freedomConfig = `{"log":{"loglevel":"warning","access":"none"},"outbounds":[{"protocol":"freedom","tag":"direct"}]}`

func startFreedom(t *testing.T) {
	t.Helper()
	if err := Start(freedomConfig); err != nil {
		t.Fatalf("start: %v", err)
	}
	t.Cleanup(Stop)
}

func TestStartRejectsInbounds(t *testing.T) {
	cfg := `{"inbounds":[{"protocol":"socks","port":10808,"listen":"127.0.0.1"}],"outbounds":[{"protocol":"freedom"}]}`
	if err := Start(cfg); err == nil || err != errInbounds {
		t.Fatalf("want errInbounds, got %v", err)
	}
	if IsRunning() {
		t.Fatal("must not run")
	}
}

func TestStartRejectsGarbageWithoutEchoingIt(t *testing.T) {
	secret := "11111111-2222-3333-4444-555555555555"
	err := Start(`{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"users":[{"id":"` + secret + `"}]}]}}`)
	if err == nil {
		t.Fatal("want an error for an incomplete config")
	}
	if strings.Contains(err.Error(), secret) {
		t.Fatalf("error leaks the config: %v", err)
	}
}

func TestNotRunning(t *testing.T) {
	Stop()
	if _, err := DialTCP("127.0.0.1", 80, 1000); err != errNotRunning {
		t.Fatalf("tcp: want errNotRunning, got %v", err)
	}
	if _, err := DialUDP("127.0.0.1", 53); err != errNotRunning {
		t.Fatalf("udp: want errNotRunning, got %v", err)
	}
}

func TestTCPSlowResponseAfterCloseWrite(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		buf := make([]byte, 5)
		if _, err := io.ReadFull(c, buf); err != nil {
			return
		}
		time.Sleep(1500 * time.Millisecond) // longer than Xray's default downlink-only timeout
		_, _ = c.Write([]byte("got:" + string(buf)))
	}()
	startFreedom(t)

	port := ln.Addr().(*net.TCPAddr).Port
	c, err := DialTCP("127.0.0.1", port, 3000)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer c.Close()
	if _, err := c.Write([]byte("hello")); err != nil {
		t.Fatalf("write: %v", err)
	}
	if err := c.CloseWrite(); err != nil {
		t.Fatalf("closeWrite: %v", err)
	}
	var got []byte
	for len(got) < len("got:hello") {
		b, err := c.Read(1024)
		if err != nil {
			t.Fatalf("read: %v", err)
		}
		if len(b) == 0 {
			break // EOF
		}
		got = append(got, b...)
	}
	if string(got) != "got:hello" {
		t.Fatalf("want %q, got %q", "got:hello", got)
	}
}

func TestTCPEOF(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		if c, err := ln.Accept(); err == nil {
			_, _ = c.Write([]byte("bye"))
			_ = c.Close()
		}
	}()
	startFreedom(t)
	c, err := DialTCP("127.0.0.1", ln.Addr().(*net.TCPAddr).Port, 3000)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer c.Close()
	var got []byte
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		b, err := c.Read(1024)
		if err != nil {
			t.Fatalf("read: %v", err)
		}
		if len(b) == 0 {
			if string(got) != "bye" {
				t.Fatalf("got %q before EOF", got)
			}
			return
		}
		got = append(got, b...)
	}
	t.Fatal("no EOF")
}

func TestUDPEchoAndTimeout(t *testing.T) {
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()
	go func() {
		buf := make([]byte, 2048)
		for {
			n, from, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			_, _ = pc.WriteTo(append([]byte("echo:"), buf[:n]...), from)
		}
	}()
	startFreedom(t)

	port := pc.LocalAddr().(*net.UDPAddr).Port
	u, err := DialUDP("127.0.0.1", port)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer u.Close()
	if err := u.Send([]byte("ping")); err != nil {
		t.Fatalf("send: %v", err)
	}
	b, err := u.Receive(3000)
	if err != nil || string(b) != "echo:ping" {
		t.Fatalf("receive: %q %v", b, err)
	}
	if _, err := u.Receive(200); err == nil || err.Error() != "timeout" {
		t.Fatalf("want timeout, got %v", err)
	}
	_ = u.Close()
	if err := u.Send([]byte("x")); err == nil {
		t.Fatal("send after close must fail")
	}
}

func TestDialUDPNeedsIP(t *testing.T) {
	startFreedom(t)
	if _, err := DialUDP("example.com", 53); err == nil {
		t.Fatal("want an error for a host name")
	}
}

func TestImportLinks(t *testing.T) {
	// Obviously fake values only.
	uuid := "00000000-0000-4000-8000-000000000001"
	links := strings.Join([]string{
		"vless://" + uuid + "@vless.example.invalid:443?encryption=none&security=tls&type=ws&path=%2Fws&sni=vless.example.invalid#My%20VLESS",
		"trojan://fake-password@trojan.example.invalid:443?security=tls&sni=trojan.example.invalid#Trojan",
		"# a comment line",
		"not a link",
		"trojan://fake-password@noname.example.invalid:443?security=tls",
	}, "\n")
	out, err := ImportLinks(links)
	if err != nil {
		t.Fatalf("import: %v", err)
	}
	var profiles []profile
	if err := json.Unmarshal([]byte(out), &profiles); err != nil {
		t.Fatal(err)
	}
	if len(profiles) != 3 {
		t.Fatalf("want 3 profiles, got %d", len(profiles))
	}
	if profiles[0].Name != "My VLESS" || profiles[1].Name != "Trojan" || profiles[2].Name != "Server 3" {
		t.Fatalf("names: %q %q %q", profiles[0].Name, profiles[1].Name, profiles[2].Name)
	}
	for _, p := range profiles {
		if err := checkNoInbounds(p.Config); err != nil {
			t.Fatalf("%s: %v", p.Name, err)
		}
		var doc struct {
			Outbounds []map[string]any `json:"outbounds"`
		}
		if err := json.Unmarshal([]byte(p.Config), &doc); err != nil || len(doc.Outbounds) != 1 {
			t.Fatalf("%s: want one outbound", p.Name)
		}
	}
	if !strings.Contains(profiles[0].Config, uuid) {
		t.Fatal("the vless config must carry the user id")
	}
}

func TestImportLinksBase64Subscription(t *testing.T) {
	// "trojan://fake-password@sub.example.invalid:443?security=tls#Sub" in base64
	body := "dHJvamFuOi8vZmFrZS1wYXNzd29yZEBzdWIuZXhhbXBsZS5pbnZhbGlkOjQ0Mz9zZWN1cml0eT10bHMjU3Vi"
	out, err := ImportLinks(body)
	if err != nil {
		t.Fatalf("import: %v", err)
	}
	if !strings.Contains(out, `"name":"Sub"`) {
		t.Fatalf("subscription not decoded: %s", out)
	}
}

func TestImportLinksNothingValid(t *testing.T) {
	if _, err := ImportLinks("hello\nworld"); err == nil {
		t.Fatal("want an error")
	}
}

func TestMeasureDelayRejectsInbounds(t *testing.T) {
	cfg := `{"inbounds":[{"protocol":"socks","port":1}],"outbounds":[{"protocol":"freedom"}]}`
	if _, err := MeasureDelay(cfg, "http://127.0.0.1/", 1000); err != errInbounds {
		t.Fatalf("want errInbounds, got %v", err)
	}
}

func TestMeasureDelayThroughFreedom(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				buf := make([]byte, 4096)
				_, _ = c.Read(buf)
				_, _ = c.Write([]byte("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n"))
			}(c)
		}
	}()
	url := "http://127.0.0.1:" + strconv.Itoa(ln.Addr().(*net.TCPAddr).Port) + "/generate_204"
	ms, err := MeasureDelay(freedomConfig, url, 5000)
	if err != nil {
		t.Fatalf("delay: %v", err)
	}
	if ms < 0 {
		t.Fatalf("delay %d", ms)
	}
}
