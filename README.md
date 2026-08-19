# Xperia Flashtool for Android

An on-device clone of [Androxyde's Flashtool](https://github.com/Androxyde/Flashtool): flash `.ftf`
firmware onto a Sony Xperia in **flash mode (S1)** from another Android phone over USB-OTG, with no
PC involved.

<br>

> ### ⚠️ Read this before you flash anything
>
> The S1 loader protocol is **not documented by Sony**. The command table in
> [`S1Command.kt`](app/src/main/java/dev/flashtool/xperia/s1/S1Command.kt) is reconstructed from
> Flashtool, the de-facto reference implementation. Everything in this repository is verified
> against unit tests and a simulated loader — **none of it has been run against a physical
> Xperia**, because that needs hardware I do not have.
>
> A wrong opcode sent to a boot ROM can leave a phone unable to start. **Take a TA backup first,
> use the built-in dry run, and treat the first real flash as an experiment on a device you can
> afford to lose.** See [Validating against real hardware](#validating-against-real-hardware).

<br>

## What it does

- **Reads `.ftf` bundles in place.** Firmware files run to several gigabytes; the app reads the ZIP
  central directory and streams individual images straight out of storage rather than unpacking a
  copy first. ZIP64 is supported, so bundles over 4 GB work.
- **Parses SIN images** — header length, format version and the embedded partition name — and
  passes the header through to the loader untouched, which is what validates the hashes.
- **Orders the flash correctly**: loader → TA units → partition table → bootloader → modem →
  kernel → system → FOTA → user data. Images are classified by name, and the default selection
  leaves `userdata` and `cache` alone so an upgrade keeps apps and settings.
- **Speaks S1 over USB-OTG**, framing every command, streaming payloads in packets the loader
  agrees to, and logging each packet when you turn on packet tracing.
- **Reads and writes TA units**, so you can back the Trim Area up before you touch anything.
- **Survives the screen turning off.** The flash runs in a foreground service holding a wake lock;
  losing the Activity does not cancel a flash in progress.
- **Dry run mode** walks the entire pipeline — archive, parsing, ordering, progress, timing —
  against a simulated loader, without touching USB.

## Requirements

- An Android 7.0+ phone that supports **USB host mode (OTG)**. Most do; a few budget models do not.
- A USB-OTG adapter or cable. The Xperia draws power from your phone during a flash, so start with
  a healthy battery on both — an interrupted flash is how phones get bricked.
- A `.ftf` firmware bundle containing `loader.sin`.
- No root required.

## Using it

1. **Build and install** (see below), then open the app and grant notification permission.
2. **Back up the TA** — the *TA* tab. TA holds DRM keys and the bootloader-unlock allowance; some of
   it cannot be restored once it is gone.
3. **Open the FTF** on the *Flash* tab. The images appear with their stage and size.
4. **Put the Xperia in flash mode**: power it off completely, hold **volume down**, then plug it in.
   The notification LED turns green. The app shows it as *Flash mode (S1)*; grant USB access when
   prompted.
5. **Pick your images.** *Default* keeps user data; *All* wipes it.
6. **Run a dry run first** to confirm the archive parses and the ordering is what you expect.
7. **Flash.** Do not unplug. The *Log* tab is the record if anything goes wrong.

## Building

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # 27 tests, no device needed
```

Compiles against SDK 35 with AGP 8.7.3 / Kotlin 2.0.21; minimum runtime is API 24.

## How it is put together

```
core/     logging, big-endian helpers, seekable byte sources over SAF documents
s1/       the S1 protocol: command table, packet codec, session, loopback loader
usb/      Sony USB ids, OTG permission handling, bulk transport
ftf/      ZIP64 reader, SIN header parser, TA file parser, FTF classification
flash/    flash plan (what to send, in what order) and the engine that sends it
service/  foreground service and wake lock for the duration of a flash
ui/       Compose screens: Flash, TA, Log
```

The layer worth knowing about is `S1Transport`, the byte pipe to the phone. `UsbS1Transport` is the
real one; `LoopbackS1Transport` is a loader simulated in memory. Every part of the flashing
pipeline above that interface is exercised by the tests, and the dry-run switch in the UI is the
same substitution made at runtime.

### The wire format

Each S1 frame is big-endian:

| offset | field |
|-------:|-------|
| 0 | sequence number, incrementing per command |
| 4 | command id |
| 8 | payload length |
| 12 | flags (bit 0 = more data follows) |
| 16 | payload |
| end | CRC32 over everything before it, or `0xFFFFFFFF` to skip |

Bulk data chunks are sent with the CRC skipped and without waiting for an acknowledgement until the
final packet of a file — asking for an ack per 64 KiB would halve throughput on a link that is
already the bottleneck.

## Validating against real hardware

If you have an Xperia to test with, this is the order that will tell you most, fastest, with the
least at stake:

1. **Enumeration only.** Connect the phone in flash mode and check the *Flash* tab identifies it as
   *Flash mode (S1)*. This exercises the USB ids and OTG plumbing without sending a byte. If the
   phone appears with an unexpected product id, add it to `SonyUsb.FLASHMODE_PRODUCT_IDS`.
2. **Loader only.** Turn on packet tracing in the *Log* tab, open an FTF, deselect every image, and
   flash. The app sends `loader.sin` and asks the loader for its version. A sensible version string
   coming back means the framing, the sequence numbers, the CRC and the `SEND_HEADER`/`SEND_DATA`
   opcodes are all right — which is the bulk of the protocol risk.
3. **TA read.** Read a small unit range on the *TA* tab. This validates `OPEN_TA`, `READ_TA` and
   `CLOSE_TA` without writing anything.
4. **One small image.** Flash a single non-critical image and confirm the phone still boots.
5. **A full flash**, once the steps above are clean.

Failures at step 2 point at `S1Command`; failures at step 3 point at the TA payload layout in
`S1Session.readTaUnit`/`writeTaUnit`. The log with packet tracing on is the evidence — please
include it in any issue.

## Known limitations

- **Not hardware-verified.** See the warning at the top.
- **Fastboot is not implemented.** Flash mode only; the app tells you when a phone is in the wrong
  mode.
- **No wipe-without-image.** Wiping a partition means flashing the matching image from the FTF. If
  the bundle has no `userdata.sin`, this app cannot erase user data.
- **TA unit numbers are not shipped.** They vary by model and Sony never published them, so the TA
  screen asks you which units to read rather than guessing from a table that would be wrong on half
  the devices.
- **No FTF creation** — this flashes bundles, it does not build them.

## Credit

The protocol knowledge this is built on comes from **Androxyde's Flashtool** and the people who
reverse-engineered S1 for it.
