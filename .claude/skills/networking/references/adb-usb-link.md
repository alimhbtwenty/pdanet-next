# USB debugging (ADB) as a network transport

When this matters: a PC talks to an app on the phone over the USB cable without tethering.

## Direction
- `adb forward tcp:<pc> tcp:<phone>`: the PC connects to `127.0.0.1:<pc>`, the phone app listens on
  its loopback `<phone>`. This is what a PC client needs.
- `adb reverse tcp:<phone> tcp:<pc>`: the phone connects to its own port and reaches the PC. Using it
  by mistake was the root bug of PdaNet-Next v2 (nothing listened on the PC side).
- The phone server should bind **loopback only**; adb delivers forwarded connections there and
  nothing else on Wi-Fi can reach it.

## Practicalities
- More than one device (or an emulator) makes plain `adb` calls fail: always `adb -s <serial>`;
  parse `adb devices -l` (`device`, `unauthorized`, `offline`, `no permissions (...)` spanning words;
  skip `emulator-*`). Models appear as `model:SM_A525F` (underscores).
- Poll `adb devices` on a background thread; it can block for seconds (daemon start).
- Re-create the forward when the phone app restarts; remove it with `forward --remove tcp:<pc>`.
- Install the APK from the PC (`adb install -r`) if `pm path <pkg>` shows nothing.
- adb only carries **TCP streams**: UDP and raw packets must be framed inside the stream.

## Framing raw packets over the stream (PDNR, used by PdaNext)
```
PC -> phone   "PDNR" version(1)
phone -> PC   "PDNR" version(1) gw4(4) client4(4) prefix4(1) gw6(16) client6(16) prefix6(1) mtu(2)
both ways     u16 big-endian length + one IP packet; length 0 = keep-alive
```
- The phone hands out the addressing so both sides share one source of truth.
- Keep-alive every 15 s, read timeout 45 s; a dead link is otherwise silent.
- Batch writes: write every packet already queued, then one flush. Bounded outbound queue; drop when
  full (TCP recovers), never block the reader.
- Peek the first 4 bytes to multiplex protocols on one port (`PDNR` vs `GET `).
