# Third-Party Notices

NEO-P2P is licensed under the MIT License (see `LICENSE`). The following
third-party components are distributed with this software and carry their
own licenses. This file satisfies the notice requirements of those licenses.

## Reticulum (rns-core, rns-interfaces) — MPL-2.0

- Source: https://github.com/torlando-tech/reticulum-kt (fork of
  https://github.com/markqvist/Reticulum)
- Used as: `com.github.torlando-tech.reticulum-kt:rns-core`,
  `com.github.torlando-tech.reticulum-kt:rns-interfaces` (version `0.1.0-1a7f6193`,
  resolved from the checked-in `android/thirdparty-repo` file-based Maven repo)
- License: Mozilla Public License 2.0 (MPL-2.0)
- MPL-2.0 requires that the source code of the covered files be made
  available under MPL-2.0. The fork source is available at the URL above.
- Full license text: https://www.mozilla.org/MPL/2.0/

## LXMF (lxmf-core) — MPL-2.0

- Source: https://github.com/torlando-tech/LXMF-kt (fork of
  https://github.com/markqvist/LXMF)
- Used as: `com.github.torlando-tech.LXMF-kt:lxmf-core` (version `0.1.0-74d343a0`,
  resolved from the checked-in `android/thirdparty-repo` file-based Maven repo)
- License: Mozilla Public License 2.0 (MPL-2.0)
- The fork source is available at the URL above.
- Full license text: https://www.mozilla.org/MPL/2.0/

## bitcoinj — Apache-2.0

- Source: https://github.com/bitcoinj/bitcoinj
- Used as: `org.bitcoinj:bitcoinj-core:0.17.1` (bumped from 0.16.2 on 2026-09-13 for `CVE-2026-44714` / `GHSA-hfcf-v2f8-x9pc`)
- License: Apache License 2.0
- Full license text: https://www.apache.org/licenses/LICENSE-2.0

## Bouncy Castle — MIT-style (public domain / Bouncy Castle License)

- Source: https://www.bouncycastle.org/
- Used as: `org.bouncycastle:bcprov-jdk18on:1.86`
- License: Bouncy Castle License (MIT-style, permissive)

## SQLCipher — BSD-style

- Source: https://www.zetetic.net/sqlcipher/
- Used as: `net.zetetic:sqlcipher-android:4.19.0` (the actively-maintained,
  16 KB-aligned artifact — not the frozen `android-database-sqlcipher`)
- License: BSD-style (SQLCipher is a fork of SQLite, public domain)

## Other dependencies

All other dependencies are used under their respective permissive licenses
(Apache-2.0, MIT, BSD); see the individual project websites for details. The
significant ones, with the versions pinned in
`android/gradle/libs.versions.toml`:

- AndroidX / Jetpack Compose (Compose BOM 2026.09.00), Navigation 2.10.2,
  DataStore 1.2.1, WorkManager 2.12.0, Lifecycle 2.11.0 — Apache-2.0
- Hilt (Dagger) 2.60.1 — Apache-2.0
- Room 2.8.5 — Apache-2.0
- Ktor client 3.6.0 (app) and Ktor server 3.6.0 (`:admind` loopback console) — Apache-2.0
- OkHttp (Ktor engine; `CertificatePinner` pins explorer hosts in `ExplorerPins`) — Apache-2.0
- kotlinx.serialization 1.11.0 and kotlinx-coroutines 1.11.0 — Apache-2.0
- SLF4J Simple 2.0.20 — MIT
- ZXing core 3.5.4 / zxing-android-embedded 4.3.0 — Apache-2.0
- Android desugaring (`desugar_jdk_libs`) 2.1.5 — Apache-2.0
- SQLite JDBC 3.53.4.0 (`:admind` daemon, no Android/Room on desktop JVM) — Apache-2.0

> The `novacrypto` BIP39/BIP32 entries were removed on 2026-09-24 — BIP-39
> wordlist handling is implemented in `:core` `data/p2p/Bip39.kt` (loads the
> bundled `/bip39_english.txt`), with BIP-32/SLIP-10 derivation on
> Bouncy Castle + bitcoinj.

---

## MPL-2.0 Compliance Note

The MPL-2.0 license applies to the covered files of the Reticulum and LXMF
forks (and any modifications made to them). NEO-P2P's own application code
is MIT-licensed and is not covered by MPL-2.0. If you modify the fork
files, you must make those modifications available under MPL-2.0.
