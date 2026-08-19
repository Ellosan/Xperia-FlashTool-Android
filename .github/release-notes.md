Flash Sony Xperia `.ftf` firmware from another Android phone over USB-OTG, no PC involved.

Install the attached APK on the phone doing the flashing — not on the Xperia being flashed.
It needs Android 7.0+ and USB host (OTG) support.

### ⚠️ Marked pre-release for a reason

The S1 loader protocol is undocumented, and the command table is reconstructed from Androxyde's
Flashtool. Framing, ordering, archive handling and chunking are covered by unit tests, but **none
of it has been run against a physical Xperia.** A wrong opcode sent to a boot ROM can leave a
phone unable to start.

Back up the TA first, use the built-in dry run, and follow the staged validation path in the
README before trusting it with a device you care about.
