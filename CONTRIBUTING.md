# Contributing

Pixel Trackpad connects an Android touch surface to a Mac receiver over a local network. Changes should preserve the existing input behavior, private pairing, bounded queues, and release of held input when a session ends.

## Build and check

Follow the platform requirements and commands in README.md. Run the receiver and onboarding checks in dry mode before testing real input. Use the on-device motion check after changes to Android touch capture or queuing.

For gesture changes, describe the macOS and Android versions, the target application, finger transitions, and expected visible behavior. Event construction, successful transport, and visible application response are separate checks. Include reversal, cancellation, and disconnect behavior when they apply.

Keep changes focused. Prefer the standard library and native APIs where practical. Document deliberate limitations and test meaningful state transitions rather than duplicating implementation details.

## Private data

Never commit pairing records, certificate private keys, signing keys, QR invitations, application support data, or screenshots containing credentials. Use disposable identities in tests. Redact device-specific details from bug reports when they are not needed to reproduce the issue.

## Pull requests

Explain the problem, the resulting behavior, and the checks performed. List any remaining platform or hardware validation. Contributions are provided under the repository's MIT license; retain the third-party notices in NOTICE.
