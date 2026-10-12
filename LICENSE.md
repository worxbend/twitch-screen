# Licensing map

This map applies the project's selected licensing policy only to original contributions whose rights the contributors hold. It does not relicense third-party material or establish missing provenance. Preserve existing upstream notices. Copyright: Twitch Screen contributors, 2026, except where an existing notice names another holder.

| Scope | SPDX identifier | License text |
|---|---|---|
| Original relay, firmware, companion, site, admin, test, CI and tooling code; code examples in documentation | MIT | [MIT](LICENSES/MIT.txt) |
| Original authored documentation and original site illustrations | CC-BY-4.0 | [CC BY 4.0](LICENSES/CC-BY-4.0.txt) |
| Original enclosure design sources (`parameters.json`, `TwitchScreen.FCMacro`, design geometry) and derived original hardware exports | CERN-OHL-P-2.0 | [CERN OHL permissive](LICENSES/CERN-OHL-P-2.0.txt) |

CAD automation scripts are MIT; the hardware designs they produce are CERN-OHL-P-2.0. Documentation code examples are MIT, not CC BY. Attribute original documentation/illustrations to “Twitch Screen contributors”, link to this repository and CC BY 4.0, and identify modifications. The site illustrations are authored vectors, not LCD evidence.

## Exclusions and unresolved rights

The following are explicitly outside the original-contribution license assertion:

- `twitch-screen-firmware/assets/twitch_glitch*`, `src/assets/twitch_glitch.h`, and the logo geometry in `tools/make_glitch.py`: Twitch/third-party artwork and trademarks. Repository history and the generator identify the official Glitch/Font Awesome geometry but do not establish its exact acquisition/version/license. Do not distribute a firmware release containing it until provenance/notice requirements are resolved or artwork is replaced.
- `docs/images/` and `twitch-screen-cad-design/refs/hardware/`: existing photographs lack an independently established photographer grant. Repository inclusion is not permission.
- `twitch-screen-cad-design/refs/concepts/`: imported screenshots lack established authorship/redistribution rights. Any derivative-design rights must be reviewed before releasing CAD exports.
- `twitch-screen-cad-design/refs/datasheets/`: manufacturer images remain subject to their owners' terms. Source URLs are recorded in the CAD reference analysis; redistribution permission is not established.
- CAD renders containing third-party marks/reference imagery and generated bundles containing the above inherit these restrictions.
- Dependencies, generated third-party fonts, cached tools, build products and vendored agent skills retain upstream terms, not this map.

See [third-party notices and release blockers](THIRD_PARTY_NOTICES.md). No repository-wide cleared-rights claim is made. The public site deliberately bundles only original vector illustrations and local original code; existing photos/logos/CAD media are excluded from its artifact.

License texts for CC BY 4.0 and CERN OHL P 2.0 were retrieved from the SPDX license-list-data text repository during integration. These texts do not establish ownership of any asset. No license change grants Twitch trademark rights or implies affiliation.
