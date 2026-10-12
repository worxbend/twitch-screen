# SPDX-License-Identifier: MIT
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import urlopen

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import build
import preview


class BuildTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="twitch-screen-site-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.output = self.root / "output"

    def source(self, name="source"):
        source = self.root / name
        for name in build.SOURCE_FILES:
            target = source / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(build.SITE / name, target)
        return source

    def test_deterministic_root_and_project_build(self):
        for base in ("/", "/twitch-screen/"):
            one = self.root / ("root" if base == "/" else "project")
            two = self.root / ("root2" if base == "/" else "project2")
            manifest = build.build(one, base, "https://worxbend.github.io" + base)
            build.build(two, base, "https://worxbend.github.io" + base)
            self.assertLessEqual(manifest["gzipBytes"], build.MAX_GZIP_BYTES)
            self.assertEqual(sorted(p.relative_to(one) for p in one.rglob("*") if p.is_file()),
                             sorted(p.relative_to(two) for p in two.rglob("*") if p.is_file()))
            for path in one.rglob("*"):
                if path.is_file():
                    self.assertEqual(path.read_bytes(), (two / path.relative_to(one)).read_bytes())
            for name, details in manifest["files"].items():
                self.assertEqual(hashlib.sha256((one / name).read_bytes()).hexdigest(), details["sha256"])

    def test_https_evidence_does_not_promote_mobile_or_product_acceptance(self):
        build.build(self.output)
        html = (self.output / 'index.html').read_text()
        self.assertIn('certificate-verifying container smoke test', html)
        self.assertIn('Real mobile trust and provider consent remain untested.', html)
        self.assertIn('No desktop, physical-display or complete-release acceptance is claimed here.', html)
        features = json.loads((build.SITE / 'content/features.json').read_text())
        self.assertEqual({item['id'] for item in features if item['state'] == 'existing'},
                         {'notifications', 'twitch'})

    def test_notification_http_timing_is_not_device_expiry_acceptance(self):
        build.build(self.output)
        page = (self.output / 'index.html').read_text()
        self.assertNotIn('planned <code>expiresInMs</code>', page)
        self.assertIn('<code>ttlMs</code> is screen hold duration', page)
        self.assertIn('<code>expiresInMs</code> is the relay waiting lifetime', page)
        self.assertIn('Legacy devices cannot enforce waiting expiry after transmission.', page)
        self.assertIn('Complete DESK/1 device expiry and recovery acceptance remain pending.', page)
        self.assertIn('docs/reference/http-api.md', page)
        features = json.loads((build.SITE / 'content/features.json').read_text())
        self.assertEqual({item['id'] for item in features if item['state'] == 'existing'},
                         {'notifications', 'twitch'})

    def test_companion_reader_privacy_and_acceptance_scope(self):
        build.build(self.output)
        page = (self.output / 'index.html').read_text()
        for text in (
                'Notification forwarding starts disabled.',
                'After explicit enable, summary-only is the default; bodies need a separate opt-in.',
                'Application-name filters are not authenticated sender identities.',
                'Fresh CPU data does not prove notification capture is healthy.',
                'Supported desktop, installed service and physical display acceptance remain pending.'):
            self.assertIn(text, page)
        for fragment in ('configure-and-run', 'privacy-and-capture-scope', 'measurement-and-delivery-semantics'):
            self.assertIn('twitch-screen-client/README.md#' + fragment, page)
        self.assertNotIn('systemctl --user enable', page)
        self.assertEqual(build.FEATURE_STATES['companion'], 'planned')

    def test_upgrade_reader_preserves_network_and_grant_boundaries(self):
        build.build(self.output)
        page = (self.output / 'index.html').read_text()
        self.assertIn('id="upgrade-guidance"', page)
        for text in (
                'Upgrade an existing deployment deliberately.',
                'Old management credentials do not protect this checkout.',
                'Back up operator configuration, Twitch tokens and runtime intent privately.',
                'Do not delete grants or token volumes.',
                'HTTP hold duration is now 100–30000 ms; waiting expiry is separate.',
                'Complete production DESK/1 and rollback acceptance remain pending.'):
            self.assertIn(text, page)
        for fragment in ('compatibility-today', 'backup-and-rollback'):
            self.assertIn('docs/guides/migration.md#' + fragment, page)
        self.assertEqual(build.FEATURE_STATES['admin'], 'planned')

    def test_verification_reader_separates_installed_software_from_physical_acceptance(self):
        build.build(self.output)
        page = (self.output / 'index.html').read_text()
        self.assertIn('id="verification-guidance"', page)
        for text in (
                'Verify the installed path, not just a codec.',
                'Use a separate test-owned relay and synthetic notification bus; never an existing service.',
                'Native LVGL observations are software evidence, not LCD pixels.',
                'Default ESP32 DESK/1 advertisement and complete product acceptance remain pending.'):
            self.assertIn(text, page)
        for target in (
                'twitch-screen-client/README.md#companion-to-native-application-compose-check',
                'twitch-screen-firmware/docs/runtime-pairing.md#canonical-production-api-and-retained-eight-application-gate',
                'docs/guides/end-to-end-testing.md#physical-and-live-prerequisites'):
            self.assertIn(target, page)
        for feature in ('cpu', 'weather', 'companion', 'orbits', 'admin'):
            self.assertEqual(build.FEATURE_STATES[feature], 'planned')

    def test_finite_matrix_reader_does_not_promote_target_acceptance(self):
        build.build(self.output)
        page = (self.output / 'index.html').read_text()
        for text in (
                'Finite software checks cover baseline/current peer compatibility, retained-eight reconnect and measured CPU/memory application.',
                'The baseline firmware row exercises transport and queue code, not an LCD.',
                'These checks do not accept the default ESP32, complete provider/suspend behavior or the private admin panel.'):
            self.assertIn(text, page)
        self.assertIn('runtime-pairing.md#actual-baselinecurrent-four-peer-compatibility', page)
        self.assertEqual(sum(state == 'planned' for state in build.FEATURE_STATES.values()), 5)

    def test_observation_reader_requires_session_authority_and_unknown_queue_scope(self):
        build.build(self.output)
        page = (self.output / 'index.html').read_text()
        for text in (
                'Correlate application views, numeric values and reports by relay epoch, connection and revision.',
                'Matching connection numbers alone cannot join observations across relay restarts.',
                'An unknown queue is not an empty queue.',
                'Snapshot and pushed-feed evidence do not replace panel, deployment or LCD acceptance.'):
            self.assertIn(text, page)
        self.assertEqual(sum(state == 'planned' for state in build.FEATURE_STATES.values()), 5)

    def test_illustration_reuse_reader_has_scoped_credit_source_and_license(self):
        build.build(self.output)
        page = (self.output / 'index.html').read_text()
        for text in (
                'id="illustration-rights"',
                'Reuse the original site illustrations with attribution.',
                'Credit Twitch Screen contributors, link the illustration source and CC BY 4.0 license, and identify your changes.',
                'Retain supplied notices and the disclaimer of warranties. No trademark rights or endorsement is granted.',
                'This scope does not clear firmware artwork, photographs, imported CAD references or third-party binary obligations.'):
            self.assertIn(text, page)
        for target in ('tree/main/site/assets', 'blob/main/LICENSES/CC-BY-4.0.txt',
                       'blob/main/LICENSE.md', 'blob/main/THIRD_PARTY_NOTICES.md'):
            self.assertIn(build.REPO_URL + target, page)
        self.assertIn('href="{{base}}assets/provenance.json"', (build.SITE / 'index.html').read_text())
        self.assertEqual(build.FEATURE_STATES['orbits'], 'planned')

    def test_card_overflow_alignment_preserves_scroll_origin(self):
        build.build(self.output)
        css = (self.output / 'assets/site.css').read_text()
        self.assertIn('justify-content:safe center;', css)
        self.assertIn('overflow:auto;', css)

    def test_synthetic_card_has_named_accessible_group(self):
        build.build(self.output)
        page = (self.output / 'index.html').read_text()
        self.assertIn('hidden role="group" aria-label="Synthetic generic card illustration"', page)
        self.assertIn('class="demo-card" tabindex="0" hidden role="group"', page)
        self.assertIn('aria-labelledby="demo-title demo-description"', page)
        self.assertEqual(build.FEATURE_STATES['orbits'], 'planned')

    def test_invalid_base_and_canonical(self):
        for base in ("relative", "/../", "//", "/a//", "/a%20b/", "/a?b/", "/a\\b/"):
            with self.subTest(base=base), self.assertRaises(ValueError):
                build.build(self.output, base)
        for canonical in ("http://example.com/twitch-screen/", "https://x:y@example.com/twitch-screen/",
                          "https://example.com/", "https://example.com/twitch-screen/?x=y"):
            with self.subTest(canonical=canonical), self.assertRaises(ValueError):
                build.build(self.output, canonical=canonical)

    def test_only_allowlisted_public_sources(self):
        source = self.source()
        (source / ".env").write_text("must never be bundled")
        (source / "credentials.h").write_text("must never be bundled")
        (source / ".agent-workflow").mkdir()
        build.build(self.output, source=source)
        self.assertEqual({p.relative_to(self.output).as_posix() for p in self.output.rglob("*") if p.is_file()},
                         set(build.ASSETS) | {"index.html", ".nojekyll", "build-manifest.json"})

    def test_symlink_source_and_output_fail_closed(self):
        source = self.source()
        css = source / "assets/site.css"
        css.unlink()
        css.symlink_to(build.SITE / "assets/site.css")
        with self.assertRaisesRegex(ValueError, "symlink"):
            build.build(self.output, source=source)
        self.output.symlink_to(self.root / "elsewhere")
        with self.assertRaisesRegex(ValueError, "symlink"):
            build.build(self.output)

    def test_no_overwrite_unowned_output(self):
        self.output.mkdir()
        (self.output / "keep.txt").write_text("owned by another task")
        with self.assertRaisesRegex(ValueError, "unowned"):
            build.build(self.output)
        self.assertEqual((self.output / "keep.txt").read_text(), "owned by another task")
        with self.assertRaises(ValueError):
            build.build(build.SITE)

    def test_output_cannot_alias_source_through_parent_segments(self):
        source = self.source()
        before = {name: (source / name).read_bytes() for name in build.SOURCE_FILES}
        for output in (source / "assets/..", source / "assets/../content/.."):
            with self.subTest(output=output), self.assertRaisesRegex(ValueError, "overwrite"):
                build.build(output, source=source)
            self.assertEqual(before, {name: (source / name).read_bytes() for name in build.SOURCE_FILES})

    def test_planned_claims_and_unknown_fields_rejected(self):
        features = json.loads((build.SITE / "content/features.json").read_text())
        for key, value in (("description", "Available now"), ("scope", "Complete"),
                           ("state", "software_verified"), ("privateEvidence", "/home/private")):
            changed = copy.deepcopy(features)
            changed[1][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                build.render_features(json.dumps(changed))

    def test_feature_availability_is_locked_by_identity(self):
        features = json.loads((build.SITE / "content/features.json").read_text())
        for index in range(len(features)):
            changed = copy.deepcopy(features)
            changed[index]["state"] = "planned" if changed[index]["state"] == "existing" else "existing"
            with self.subTest(index=index), self.assertRaisesRegex(ValueError, "reviewed"):
                build.render_features(json.dumps(changed))
        features[1]["id"] = "unreviewed"
        with self.assertRaisesRegex(ValueError, "identity"):
            build.render_features(json.dumps(features))

    def test_markdown_anchor_duplicates_markup_and_fenced_examples(self):
        text = '# Hello, `desk`!\n## Hello, desk!\n~~~md\n# Not a heading\n~~~\n'
        text += '## [Reader](README.md) &amp; build\n<a id="explicit"></a>\n'
        self.assertEqual(build.markdown_anchors(text),
                         {"hello-desk", "hello-desk-1", "reader--build", "explicit"})

    def test_repository_cta_anchor_must_exist(self):
        source = self.source()
        path = source / "index.html"
        path.write_text(path.read_text().replace("#first-success-without-twitch", "#missing-reader-journey"))
        with self.assertRaisesRegex(ValueError, "repository fragment"):
            build.build(self.output, source=source)

    def test_feature_items_and_oversized_sources_fail_closed(self):
        features = json.loads((build.SITE / 'content/features.json').read_text())
        for item in (None, [], 1, 'unreviewed'):
            changed = copy.deepcopy(features)
            changed[0] = item
            with self.subTest(item=item), self.assertRaises(ValueError):
                build.render_features(json.dumps(changed))
        source = self.source()
        with (source / 'assets/demo.js').open('r+b') as member:
            member.truncate(build.MAX_RAW_BYTES + 1)
        with self.assertRaisesRegex(ValueError, 'uncompressed'):
            build.build(self.output, source=source)
        self.assertFalse(self.output.exists())

    def test_feature_text_is_escaped(self):
        features = json.loads((build.SITE / "content/features.json").read_text())
        features[0]["title"] = '<img src=x onerror="bad()">'
        result = build.render_features(json.dumps(features))
        self.assertNotIn("<img", result)
        self.assertIn("&lt;img", result)

    def test_missing_links_anchors_remote_assets_and_network_demo_rejected(self):
        mutations = (("index.html", 'href="#uses"', 'href="#missing"'),
                     ("index.html", 'id="uses"', 'id="main"'),
                     ("index.html", '{{base}}assets/site.css', 'https://example.com/site.css'),
                     ("index.html", '{{base}}assets/site.css', '{{base}}assets/missing.css'),
                     ("assets/demo.js", 'let userPaused = false;', 'fetch("/private");'),
                     ("assets/site.css", 'body { margin:0; overflow-wrap:anywhere; }', '@import "https://example.com/x";'))
        for index, (name, old, new) in enumerate(mutations):
            source = self.source(f"mutation-{index}")
            path = source / name
            path.write_text(path.read_text().replace(old, new))
            with self.subTest(name=name, new=new), self.assertRaises(ValueError):
                build.build(self.root / f"result-{index}", source=source)

    def test_original_illustration_provenance_and_no_physical_claim(self):
        source = self.source()
        path = source / "assets/provenance.json"
        data = json.loads(path.read_text())
        data["assets"][0]["physicalEvidence"] = True
        path.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, "physical"):
            build.build(self.output, source=source)

    def test_provenance_shape_and_original_license_fail_closed(self):
        original = json.loads((build.SITE / "assets/provenance.json").read_text())
        cases = [[], {}, {**original, "assets": []}, {**original, "codeLicense": "unreviewed"}]
        for key, value in (("physicalEvidence", "false"), ("license", "unreviewed"),
                           ("path", "assets/missing.svg"), ("creator", "")):
            changed = copy.deepcopy(original)
            changed["assets"][0][key] = value
            cases.append(changed)
        for case in cases:
            with self.subTest(case=case), self.assertRaises(ValueError):
                build.validate_provenance(json.dumps(case))

    def test_payload_budget(self):
        files = {"index.html": b'<html><title>x</title><meta name="description" content="x"><h1>x</h1></html>',
                 "assets/site.css": b"", "assets/demo.js": b"",
                 "assets/orbit.svg": b"<svg/>", "assets/social.svg": b"<svg/>",
                 "assets/provenance.json": (build.SITE / "assets/provenance.json").read_bytes()}
        old = build.MAX_GZIP_BYTES
        try:
            build.MAX_GZIP_BYTES = 1
            with self.assertRaisesRegex(ValueError, "payload"):
                build.validate_output(files, "/")
        finally:
            build.MAX_GZIP_BYTES = old

    def test_example_matches_canonical_a1_fixture(self):
        examples = json.loads((build.REPOSITORY / "protocol/desk-v1-http-examples.json").read_text())["examples"]
        canonical = next(item["value"] for item in examples if item["name"] == "notification_bodyless")
        self.assertEqual(canonical, {"type":"message", "title":"Build finished", "body":""})
        build.build(self.output)
        page = (self.output / "index.html").read_text()
        self.assertIn('Build finished', page)
        self.assertIn('&quot;body&quot;: &quot;&quot;', page)

    def test_duplicate_json_keys(self):
        with self.assertRaisesRegex(ValueError, "duplicate"):
            build.strict_json('{"x":1,"x":2}')

    def test_preview_routes_headers_mime_and_cleanup(self):
        build.build(self.output)
        with preview.server(self.output) as http:
            thread = threading.Thread(target=http.serve_forever, daemon=True)
            thread.start()
            origin = f"http://127.0.0.1:{http.server_port}"
            try:
                for path, mime in (("/twitch-screen/", "text/html"),
                                   ("/twitch-screen/assets/site.css", "text/css"),
                                   ("/twitch-screen/assets/orbit.svg", "image/svg+xml")):
                    with urlopen(origin + path) as response:
                        self.assertIn(mime, response.headers["Content-Type"])
                        self.assertIn("connect-src 'none'", response.headers["Content-Security-Policy"])
                        self.assertEqual(response.headers["X-Content-Type-Options"], "nosniff")
                for path in ("/", "/.env", "/twitch-screen/assets/", "/twitch-screen/%2e%2e/README.md"):
                    with self.subTest(path=path), self.assertRaises(HTTPError) as error:
                        urlopen(origin + path)
                    self.assertEqual(error.exception.code, 404)
                    error.exception.close()
            finally:
                http.shutdown()
                thread.join(timeout=3)
                self.assertFalse(thread.is_alive())


if __name__ == "__main__":
    unittest.main()
