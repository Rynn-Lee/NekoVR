# NekoVR Dataset Contract v1

The operational collection, consent, reset, acceptance, and dataset-ready gate
procedure is defined in [the collection protocol](collection-protocol.md).

The canonical archive extension is `.nvrdata`. It is a ZIP container stored
without recompressing its entries and contains exactly `manifest.json` and
`telemetry.fbs.zst`. The telemetry entry is a Zstandard level-3 stream of
little-endian, unsigned-32-bit-length-prefixed FlatBuffer `DatasetRecord`
messages with file identifier `NVRD`.

## Coordinate and time conventions

- World coordinates are right-handed: +X right, +Y up, +Z backward. Sensor
  axes are retained only in explicitly named sensor-frame channels.
- Quaternions are serialized in `(x, y, z, w)` order, are finite and
  normalized, and represent active rotations. Composition `a * b` applies
  `b` first, then `a`. A correction target maps pre-reset to post-reset as
  `q_target = normalize(q_post * inverse(q_pre))`.
- Positions are metres, velocity is m/s, linear acceleration excludes gravity
  and is m/s², raw acceleration includes gravity where firmware reports it,
  angular velocity is rad/s, temperature is °C, magnetic field is microtesla,
  voltage is V, and radio strength is dBm.
- Session timestamps and deltas are unsigned monotonic nanoseconds relative to
  the session's monotonic origin. UTC ISO-8601 is metadata only and is never
  used to order samples.

## Numerical rules

Integer identifiers, timestamps, counters, reset transforms and manifest
values are never FP16. FP16 is permitted only for bounded feature vectors.
Writers reject NaN, infinity, zero-norm quaternions, and values outside a
channel's declared range before encoding. Quaternion components use `[-1, 1]`;
linear acceleration uses `[-128, 128] m/s²`; angular velocity uses
`[-64, 64] rad/s`; magnetic vectors use `[-4096, 4096] µT`; positions use
`[-1000, 1000] m`. Round-to-nearest-even IEEE-754 binary16 is used. Readers
preserve subnormals and identify binary16 NaN/infinity as invalid input rather
than silently treating them as measurements.

## Validity, provenance, and missingness

Every optional channel carries both `Validity` and `Provenance`. Missing data
is `UNAVAILABLE`; zero is a measurement only when marked valid. Provenance is
one of measured, firmware-reported, server-derived, model-derived,
user-annotated, legacy-estimated, reset-derived, or unavailable. Readers may
skip unknown optional channel IDs but reject unknown required semantics or an
unsupported major schema version.

## Profiles

- `MINIMUM`: monotonic time, valid HMD reference, roster, raw and calibrated
  orientation, final orientation, acceleration, status, and explicit masks.
- `STANDARD`: minimum plus angular velocity (measured or derived), packet/link
  quality, battery, temperature/calibration when available, topology events,
  and correction-stage separation.
- `FULL_FIDELITY`: standard plus native-rate raw gyro/accelerometer/magnetic
  streams, device clocks/sequences, all delivery counters, power/environment
  state, controllers/skeleton/floor context, and model diagnostics.

Legacy trackers may participate in `MINIMUM` and `STANDARD` when optional
channels are explicitly unavailable. A profile never fabricates missing
hardware fields. `FULL_FIDELITY` rejects a roster that cannot provide all
channels declared required by that collection policy.

## Privacy

The manifest stores consent, an optional subject pseudonym, and a random
session ID. Raw IP addresses, MAC addresses, account identifiers, and stable
hardware identifiers are omitted by default. When correlation is explicitly
enabled, identifiers are HMAC-SHA-256 hashed with a random per-session salt;
the salt remains local to that archive.
