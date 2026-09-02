# Native linking — s2n-tls, interned libcrypto, and the self-contained Linux binary

How `index4s` links on each platform and why the flags in `build.sbt`'s env-guarded
`nativeConfig` block look the way they do. Read this before touching linking options,
s2n provisioning, or anything that changes the binary's runtime dependencies.

**Goal state (product invariant):** the Linux binary's only dynamic dependencies are
glibc (`libc.so.6`, `libm.so.6`, `ld-linux`). macOS equivalent: only `libSystem`.
No `libcrypto.so.3`, no `libidn2.so.0`, no `libz.so.1` — everything else is
statically linked into the executable.

## The three pieces

### 1. s2n-tls with interned libcrypto (Linux)

`s2n-tls` is provisioned with `-DS2N_INTERN_LIBCRYPTO=ON` (plus
`-DBUILD_SHARED_LIBS=OFF -DBUILD_TESTING=OFF -DUNSAFE_TREAT_WARNINGS_AS_ERRORS=OFF`).
Interning means:

- A **static** `libcrypto.a` is found at configure time (with `BUILD_SHARED_LIBS=OFF`
  s2n's Findcrypto prefers the static archive — on Ubuntu that's
  `/usr/lib/x86_64-linux-gnu/libcrypto.a` from `libssl-dev`, which is PIC because
  Debian builds OpenSSL `shared`, and modern OpenSSL builds the `.so` *from* the `.a`
  objects).
- Every libcrypto global symbol is renamed to `s2n$<symbol>` (`objcopy
  --redefine-syms`) and the objects are merged **into** `libs2n.a` via `ar -r`.
  Result: `libs2n.a` is self-contained — verify with
  `nm libs2n.a | grep -c 's2n\$'` (≈25k symbols for v1.7.8 + OpenSSL 3.0.x).
- **The final app link must NOT pass `-lcrypto`** — interning resolved all OpenSSL
  references; adding `-lcrypto` would reintroduce a *dynamic* `libcrypto.so.3`
  runtime dep. There is no `-lcrypto` on Linux anywhere in the build.
- Side benefit: because every libcrypto symbol is prefixed, an `LD_PRELOAD`ed
  system libcrypto cannot interfere with the TLS stack (s2n's own CI tests exactly
  this). Nothing in index4s may call unprefixed OpenSSL — ember consumes the
  `s2n_*` API only.

Caveats inherited from upstream: FIPS + interning is unsupported (compile error);
interning + LTO + testing doesn't work (irrelevant: `BUILD_TESTING=OFF`); interning
is **Linux-only** (needs GNU `objcopy`) — macOS uses a different mechanism (below).

### 2. Scala Native's option ordering and the `--whole-archive` trick

Scala Native places custom `linkingOptions` **before** its discovered `-l` flags and
prepends `-Wl,--as-needed`. Two consequences:

- It reads `S2N_LIBDIR` and adds `-ls2n`, but NOT the `-L` search path — that's why
  build.sbt passes `-L$dir` itself.
- A *plain* static archive listed in our custom options would contribute nothing:
  archive members are only extracted to satisfy *already-undefined* symbols, and at
  that position nothing references them yet. Scala Native's discovery adds
  `-lidn2` / `-lz` on Linux regardless (javalib: `java.net.IDN`, `java.util.zip`),
  which would normally resolve to the dynamic `.so`s and become runtime deps.

Fix: force-include those as STATIC archives with GNU ld's `--whole-archive`
(the equivalent of macOS `-force_load`), and close it with `--no-whole-archive`
**before** Scala Native's discovered flags — the later dynamic `-lidn2`/`-lz` then
satisfy nothing and `--as-needed` drops them:

```
-L$S2N_LIBDIR
-Wl,--whole-archive
-l:libidn2.a
-l:libz.a
-Wl,--no-whole-archive
-l:libunistring.a   # SELECTIVE — see §3
```

`-l:<filename>` is GNU ld's exact-filename library resolution (searches `-L` dirs,
then default dirs — `/usr/lib/<triple>` is default on Ubuntu, no `-L` needed).

### 3. Ubuntu's `libidn2.a` PARTIALLY bundles libunistring

Ubuntu/Debian's `libidn2.a` inlines a *subset* of libunistring objects
(`libunistring_la-*.o` — `ar t /usr/lib/x86_64-linux-gnu/libidn2.a | grep
libunistring_la` shows 12) but still references the rest (`uc_script`,
`uc_combining_class`, …) which upstream expects from `libunistring.so` at runtime.
Two failure modes follow:

- Whole-archiving `libidn2.a` alone → undefined `uc_*` references at link.
- Whole-archiving `libidn2.a` **and** `libunistring.a` together → `multiple
  definition of 'locale_charset'` (etc.) — the bundled symbols defined twice.

The working shape is what build.sbt does: `libunistring.a` goes **after** the
closed `--no-whole-archive` group, as a *selective* archive. At that point the
whole-archived idn2 members have created the `uc_*` undefineds, so ld extracts
exactly the members that satisfy them; members whose symbols are already defined
(the bundled 12) are never extracted → no duplicates. On CI the archive comes
from `libunistring-dev` (default dirs); on this repo's no-sudo dev box it is
staged into the s2n prefix's `lib/` (already a `-L` dir).

## Platform matrix

| Platform | Flags | libcrypto source |
|---|---|---|
| Linux (GNU ld) | `-L$dir`, `--whole-archive -l:libidn2.a -l:libz.a --no-whole-archive`, selective `-l:libunistring.a` | interned in `libs2n.a` |
| macOS (ld64) | `-L$dir` + `-Wl,-force_load,$INDEX4S_LIBCRYPTO_A` (release CI) or plain `-lcrypto` (dev fallback) | brew `openssl@3` static `.a`, force-loaded |
| Windows | none (releases disabled) | — |

macOS notes: `--whole-archive`/`--no-as-needed` don't exist on ld64; s2n interning
is not macOS-viable (needs GNU objcopy). `-force_load` of the static brew libcrypto
is position-independent *and* makes the binary self-contained — a dynamic brew
libcrypto would bake its absolute keg-only path into the binary and break on stock
macOS. Without `INDEX4S_LIBCRYPTO_A` set, dev builds fall back to dynamic
`-lcrypto` (acceptable locally, never for releases).

Windows: no s2n provisioning path exists for the MSVC/LLVM toolchain Scala Native
requires (upstream s2n CI is MSYS2/MinGW); GNU-dialect `-Wl` flags would be
rejected anyway. See `.github/workflows/release.yml` for the full blocker analysis.

## Provisioning (Linux dev)

```console
$ sudo apt install libssl-dev libidn2-dev libunistring-dev zlib1g-dev clang cmake   # libcrypto.a, libidn2.a, libunistring.a, libz.a
$ git clone --depth 1 --branch v1.7.8 https://github.com/aws/s2n-tls.git <src>
$ cmake -S <src> -B <build> -DBUILD_SHARED_LIBS=OFF -DBUILD_TESTING=OFF \
    -DUNSAFE_TREAT_WARNINGS_AS_ERRORS=OFF -DS2N_INTERN_LIBCRYPTO=ON \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=<prefix>
$ cmake --build <build> --parallel --target install
```

Then `S2N_LIBDIR=<prefix>/lib sbt ...` on a **fresh** sbt server (the sbt 2 server
captures env at boot — reboot it after changing S2N_LIBDIR or linking will fail).

This repo keeps its local provisioning under gitignored `.native-deps/`
(`s2n-install/`), i.e. entirely inside the workspace.

## Verification recipe

After any linking change, on the produced binary:

```console
$ readelf -d <binary> | grep NEEDED    # expect ONLY libm.so.6, libc.so.6, ld-linux-*.so.2
$ nm <binary> | grep -c 's2n\$'        # > 0 → interned libcrypto actually linked
$ ldd <binary>                         # same three, nothing else
$ objdump -T <binary> | grep -o 'GLIBC_[0-9.]*' | sort -Vu | tail -1   # glibc floor
```

Plus a live HTTPS smoke (`get circe/circe stars`) — interned TLS must actually work.

**Measured glibc floor: 2.38** — a single symbol, `__isoc23_strtol` (2 refs,
from Ubuntu 24.04's `libcrypto.a`, interned into `libs2n.a`), sets it. Supported
distros ≈ glibc ≥ 2.38: Ubuntu 24.04+, Debian 13+, Fedora 39+, RHEL 10, Arch,
NixOS, openSUSE Tumbleweed. **Not** supported: Ubuntu 22.04, Debian 12, RHEL/Alma/
Rocky 9, Amazon Linux 2023 (all ≤ 2.36), RHEL 8 family, and Alpine/musl. Note
this is a floor *regression* vs the old dynamic-libcrypto binary (2.34 — the
libpthread-into-libc boundary) — the price of noble-built static archives. If
reach ever matters again: a tiny shim redirecting `__isoc23_strtol` → `strtol`,
or building the statics against an older toolchain, restores ≤2.34. CI pins this
floor (`Verify link` steps) so a toolchain/image bump can't move it silently —
re-measure and update this number consciously.

## Operational consequences of static-everything

- **OpenSSL CVEs are our release responsibility.** No distro patching of the
  embedded libcrypto; a CVE means cutting a new index4s release (tag → CI). This
  extends the existing freeze model: s2n is already pinned per release, and macOS
  already force-loads static libcrypto. The OpenSSL version is whatever the build
  image's `libssl-dev` ships — bump it by bumping/overriding the build image.
- **License:** OpenSSL 3.x is Apache-2.0 (as are s2n and index4s) — compatible.
  The binary embeds OpenSSL + s2n object code; keep their license notices
  referenced in release material.
- **Size:** interning adds ~+6 MB vs the dynamic-libcrypto link (release-fast:
  23.9 MB → 30.2 MB; extraction from the interned archive is selective, the
  whole-archive'd idn2/z add ~0.5 MB).

## History

- 2026-09-01 (T10): macOS went self-contained via `-force_load`; Linux kept dynamic
  `libcrypto.so.3` ("exactly as dev").
- 2026-09-02: user decision — Linux goes fully self-contained too (glibc-only),
  README claim updated to match; measured floor documented. See
  `.sisyphus/notepads/index4s-design/decisions.md`.
