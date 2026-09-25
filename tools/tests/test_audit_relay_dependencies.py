"""Offline regression tests for completeness and exception boundaries."""

from datetime import date
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("audit", Path(__file__).parents[1] / "audit_relay_dependencies.py")
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class DependencyAuditTests(unittest.TestCase):
    def test_coordinates_include_classifiers_without_duplicate_queries(self):
        base = "qref:v1:abcdef:/cache/https/repo1.maven.org/maven2/io/netty/transport/4.2.18/transport-4.2.18"
        self.assertEqual(audit.coordinates([base + ".jar", base + "-linux-x86_64.jar"]), [("io.netty:transport", "4.2.18")])

    def test_unknown_or_empty_artifacts_never_produce_a_clean_scan(self):
        for references in ([], ["/local/library.jar"], [None], ["qref:v1:abc:/cache/https/unknown/maven2/g/a/1/a-1.jar"],
                           ["qref:v1:abc:/cache/https/repo1.maven.org/maven2/g/a/1/wrong-1.jar"]):
            with self.subTest(references=references), self.assertRaises(audit.AuditError):
                audit.coordinates(references)

    def test_pagination_retains_package_identity_and_later_advisories(self):
        calls = []

        def request(queries):
            calls.append(queries)
            if len(calls) == 1:
                return {"results": [{"vulns": [{"id": "GHSA-first"}], "next_page_token": "a2"}, {}]}
            self.assertEqual(queries, [{"package": {"ecosystem": "Maven", "name": "g:a"}, "version": "1", "page_token": "a2"}])
            return {"results": [{"vulns": [{"id": "GHSA-second"}]}]}

        self.assertEqual(audit.scan([("g:a", "1"), ("g:b", "2")], request),
                         [("g:a", "1", "GHSA-first"), ("g:a", "1", "GHSA-second")])

    def test_malformed_and_incomplete_responses_fail_closed(self):
        for response in ({}, {"results": []}, {"results": [{"error": "unavailable"}]},
                         {"results": [{"vulns": None}]}, {"results": [{"vulns": [{}]}]}):
            with self.subTest(response=response), self.assertRaises(audit.AuditError):
                audit.scan([("g:a", "1")], lambda _: response)

    def test_repeated_tokens_and_unbounded_pages_fail(self):
        count = 0

        def changing_token(_):
            nonlocal count
            count += 1
            return {"results": [{"next_page_token": str(count)}]}

        with self.assertRaises(audit.AuditError):
            audit.scan([("g:a", "1")], changing_token)
        self.assertEqual(count, 20)
        with self.assertRaises(audit.AuditError):
            audit.scan([("g:a", "1")], lambda _: {"results": [{"next_page_token": "same"}]})

    def test_batches_are_bounded_and_every_package_is_scanned(self):
        sizes = []

        def request(queries):
            sizes.append(len(queries))
            return {"results": [{} for _ in queries]}

        self.assertEqual(audit.scan([(f"g:a{i}", "1") for i in range(205)], request), [])
        self.assertEqual(sizes, [100, 100, 5])

    def test_exception_requires_exact_coordinate_advisory_and_unexpired_date(self):
        allowed = {"package": "g:a", "version": "1", "advisory": "GHSA-old", "reviewed": "2026-09-25", "expires": "2026-10-25",
                   "owner": "maintainers", "reason": "Assessed configuration-only reachability"}
        findings = [("g:a", "1", "GHSA-old"), ("g:a", "1", "GHSA-new"), ("g:a", "2", "GHSA-old")]
        self.assertEqual(audit.apply_exceptions(findings, [allowed], date(2026, 9, 25)), findings[1:])
        with self.assertRaises(audit.AuditError):
            audit.apply_exceptions(findings, [allowed], date(2026, 10, 25))
        with self.assertRaises(audit.AuditError):
            audit.apply_exceptions([], [allowed], date(2026, 9, 25))


if __name__ == "__main__":
    unittest.main()
