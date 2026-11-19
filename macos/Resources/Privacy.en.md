Revised 19 November 2026 · InkOTP for macOS

## What InkOTP processes

InkOTP generates verification codes on your Mac. It uses the service and account names, secret keys and parameters you enter or import. Your account library and its security settings are encrypted on this Mac. InkOTP has no account registration, advertisements, analytics, crash reporting or remote account sync.

## Camera and documents

InkOTP requests camera access when you choose to scan. It processes camera frames locally to read QR codes and does not save the frames. You can withdraw camera permission in System Settings and continue to add accounts with a key or a selected image.

InkOTP accesses images and backups you choose and saves backups only where you direct it. QR code contents and imported backup entries are saved in the vault after you confirm them. If you choose a synced storage location, your storage provider may handle the exported file under its own terms.

## Authentication and clipboard

InkOTP uses the macOS Keychain and, when enabled, the macOS system authentication dialogue. macOS manages your fingerprint and login credentials. Use of this feature does not give InkOTP access to those credentials.

When you copy a verification code, InkOTP writes it to the system clipboard. It attempts to clear its own most recent copy after the interval selected in Settings. macOS services, other apps and devices with clipboard access may be able to see the value. A shared display or a screen capture may show other information visible in the app; hide codes before sharing your display.

## Backups, retention and removal

The vault and protected settings are stored in the macOS app container. The device key is stored in Keychain. Deleting the app from Applications can leave its container, preferences or Keychain entries on the Mac. Use the macOS tools for app data and Keychain management when you want to remove those items too.

Backups belong to you and remain at the location you choose. Encrypted exports use your current recovery password. A plaintext export includes the keys needed to generate codes. A replacement-style rescue restore may keep an encrypted copy of your previous vault.

## Diagnostics and optional links

Diagnostics reads the Mac clock and can sample application memory and vault file size on request. It never reports the results to the developer. If you choose an external link, macOS opens it in the appropriate app and the destination handles the request under its own privacy terms.

InkOTP uses Apple frameworks for its interface, camera, QR reading, authentication and cryptographic operations. It also uses the bundled open-source cryptography listed under About and Licenses. These components perform their work locally.

## Manage your choice and contact

You can remove individual accounts, change your settings, manage your backups and withdraw your acceptance in InkOTP Settings. Withdrawing ends the current session and leaves the vault available on your Mac. The application asks you to review the revised statement if the bundled text changes.

To contact the developer about privacy, use hi@renhongchang.top. Do not send secret keys, codes, recovery passwords, backup files or screenshots that contain them. InkOTP is intended for a general audience. Children who need a parent’s or guardian’s consent should use it with that consent and guidance.
