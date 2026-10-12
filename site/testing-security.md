# Site verification and publication security

The site is static. Playwright and Chromium are test tools, not shipped dependencies. Local browser checks are not a security certification or evidence of physical LCD rendering.

## Browser baseline disposition

Playwright 1.63.0 was selected from actual PyPI release metadata (September 15, 2026) and its Linux wheel hash was independently matched. Its upstream `browsers.json` pins Chromium 153.0.8010.12, so upgrading Playwright alone would not select the current Stable browser. On October 11, 2026, [Chrome for Testing release metadata](https://googlechromelabs.github.io/chrome-for-testing/last-known-good-versions-with-downloads.json) and [Chromium Dash Linux Stable metadata](https://chromiumdash.appspot.com/fetch_releases?channel=Stable&platform=Linux&num=3) independently matched 155.0.8059.39, revision 1697595. The exact Linux headless-shell archive was downloaded over verified HTTPS, SHA-256/size pinned in `browser-baseline.json`, installed with the checked-in bounded installer, and exercised by the complete local harness. Playwright controls this executable explicitly; bundled Chromium is never a fallback.

This is a maintained-channel selection and observed local compatibility, not an exhaustive advisory/backport audit or security certification. Before release, independently recheck current Stable/security advisories and pin freshness, rerun all checks, and review hosted workflow protection. Never update automatically to a moving `latest` URL. No browser or test dependency ships in the site artifact.

Historical Playwright 1.55.0 / Chromium 140.0.7339.16 results remain behavioral history only. Google's [August 13, 2025 beta announcement](https://chromereleases.googleblog.com/2025/08/chrome-beta-for-desktop-update_13.html) identifies that version as beta. The [September 17, 2025 stable update](https://chromereleases.googleblog.com/2025/09/stable-channel-update-for-desktop_17.html) documents later security fixes including exploited CVE-2025-10585. Do not use that old browser for current gates.

All browser checks remain limited to test-owned loopback previews with authored synthetic content. Do not use the harness for normal browsing, private relay/admin pages, credentials, provider consent or arbitrary remote content. It blocks service workers and routes every context through a preview-origin allowlist. Non-preview and `/api/` requests are aborted before connection; executable negative checks exercise both cases. Unexpected attempts fail the run. This is application-request containment, not an OS-level egress sandbox or proof that browser-internal traffic cannot occur.

## Workflow boundary

The artifact workflow has read-only repository permission, does not publish, and does not use secrets or `pull_request_target`. Its paths include site files, public documentation/license inputs and both Pages workflow files so reader-journey changes rerun site checks.

Publication is a separate manual workflow. Both `publish` confirmation and `refs/heads/main` are required. Checkout is pinned to the dispatch SHA, credentials are not persisted, and build/browser/artifact gates run before the deploy job. Only that job obtains Pages/OIDC write permission and enters `github-pages`.

An environment name in YAML does not create approval protection. Before authorized publication, an authorized maintainer must verify GitHub's actual environment configuration: required reviewers, prevention of self-approval where available, protected deployment branch `main`, and least-privilege repository/workflow settings. Protect changes to the workflow and public claim ledger through repository review. These remote settings and hosted Actions execution have not been verified by local source tests. Do not enable Pages or dispatch publication during ordinary implementation.

## Public content boundary

Only the allowlisted original site files enter the artifact. The feature ledger cannot silently promote a planned feature to existing. Repository CTA files and linked Markdown heading anchors must exist locally; this does not prove uncommitted changes exist on GitHub or that the deployment target is published. Final publication review must verify actual remote docs/links and the served base path.

The root original-contribution license map exists. Third-party artwork, photograph, CAD and binary-dependency clearance remains separate. The site bundles only its authored illustrations; no existing photo, Twitch logo, downloaded font, browser cache, local evidence or relay configuration is copied. Keep production desk, desktop/service, mobile OAuth, physical performance and product-completion claims pending until independently accepted evidence exists.
