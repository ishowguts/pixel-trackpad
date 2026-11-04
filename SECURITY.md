# Security

Pair only with an invitation shown directly on your Mac. Keep the QR private. The Android custom scheme is validated in the app, and the operating system may offer other handlers for it. Use a trusted local camera or reader, select Pixel Trackpad, and approve only when the phone and Mac show the same six-digit code.

- The invitation contains a private LAN IP literal, listener port, SHA-256 certificate fingerprint, and random token. The receiver accepts one pairing request within two minutes. The Mac must approve that request within 30 seconds and before the invitation expires. Pairing issues a distinct random credential and cannot send input.
- Android validates the invitation and checks the TLS certificate against the exact fingerprint before sending an invitation token or saved credential. Reconnect tries the saved endpoint before optional Bonjour discovery. Discovery matches the remembered instance and still requires the saved pin. Discovery records carry no credentials.
- The receiver listens on the LAN and accepts private or link-local peer addresses. Do not forward its port or expose it through a tunnel. LAN peers can attempt connections and can disrupt availability. A paired phone can control the Mac while live input is enabled.
- The Mac stores its identity and one phone credential in its private application support directory. Android stores its pairing and settings in app-private storage with backup disabled. **Forget Phone** revokes the credential and disconnects the active phone. If the connection drops while saving a new pairing, forget the pairing on the Mac and start again.
- Keep `.local`, private application data, QR images, signing keys, and logs containing credentials out of shared files. The receiver app stores its identity separately from the legacy command-line receiver.

The unsigned Apple Silicon preview bundles CPython and uses an ad hoc signature. Developer ID signing, notarization, Android release signing and configuration, and wider device testing remain release gates.
