#!/usr/bin/env python3
"""
Tests for the licence classifier (SB-2204).

    python3 scripts/license_gate/test_policy.py

Plain unittest so CI needs no pip install, matching the gate itself.

The cases below are the real spellings seen in Maven POMs, CocoaPods
podspecs and the GitHub licences API -- not invented ones. Each was observed
while building the gate against this repo's actual dependency tree.
"""

from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import policy  # noqa: E402
from policy import (  # noqa: E402
    BUILD_ONLY, LIC_NONE, LIC_UNKNOWN, SHIPPED, Component, normalise, verdict,
)


class TestNormalise(unittest.TestCase):
    def check(self, cases):
        for raw, expected in cases:
            with self.subTest(raw=raw):
                self.assertEqual(normalise(raw), expected)

    def test_spdx_identifiers(self):
        self.check([
            ("MIT", "MIT"), ("Apache-2.0", "Apache-2.0"), ("ISC", "ISC"),
            ("MPL-2.0", "MPL-2.0"), ("GPL-3.0", "GPL-3.0"), ("Zlib", "Zlib"),
            ("0BSD", "0BSD"), ("CC-BY-SA-4.0", "CC-BY-SA-4.0"),
            ("Apache-2.0-or-later", "Apache-2.0"), ("GPL-3.0-only", "GPL-3.0"),
        ])

    def test_maven_pom_names(self):
        """Free text as it actually appears in <licenses><license><name>."""
        self.check([
            ("The Apache Software License, Version 2.0", "Apache-2.0"),
            ("Apache License, Version 2.0", "Apache-2.0"),
            ("Apache 2.0", "Apache-2.0"),
            ("Eclipse Public License - Version 2.0", "EPL-2.0"),
            ("Eclipse Public License 1.0", "EPL-1.0"),
            ("MIT license", "MIT"),
            ("Common Development and Distribution License 1.0", "CDDL-1.0"),
        ])

    def test_podspec_names(self):
        self.check([
            ("Apache 2.0", "Apache-2.0"),
            ("public domain", "Unlicense"),
            ("Mixed", LIC_UNKNOWN),
            ("Proprietary", "PROPRIETARY-SENSORBIO"),
        ])

    def test_copyleft_shorthand(self):
        self.check([
            ("GPLv3", "GPL-3.0"), ("AGPLv3", "AGPL-3.0"),
            ("LGPL v2.1", "LGPL-2.1"), ("LGPLv3", "LGPL-3.0"),
            ("GNU General Public License", "GPL"),
            ("GNU Lesser General Public License", "LGPL"),
            ("GNU General Public License, version 2", "GPL-2.0"),
        ])

    def test_google_proprietary_sdk_terms(self):
        """Play Services, Firebase and Play Core declare Google's own terms.

        Not open source and not copyleft: they impose no source-disclosure duty
        and no restriction on what a customer charges, which is what 6.6B
        warrants. Classified by name rather than left as UNKNOWN.
        """
        self.check([
            ("Android Software Development Kit License", "Android-SDK-ToS"),
            ("Play Core Software Development Kit Terms of Service", "Play-Core-ToS"),
        ])
        self.assertEqual(
            verdict(Component("com.google.android.gms:play-services-location",
                              declared="Android Software Development Kit License")),
            "allowed")

    def test_classpath_exception_is_not_plain_gpl(self):
        """The exception is what stops the copyleft reaching a linking work.

        A name carrying it must not collapse to GPL-2.0, which is forbidden.
        """
        self.assertEqual(normalise("CDDL + GPLv2 with classpath exception"),
                         "GPL-2.0-with-classpath-exception")
        self.assertEqual(
            verdict(Component("javax.annotation:javax.annotation-api",
                              declared="CDDL + GPLv2 with classpath exception")),
            "allowed")
        # ... while plain GPL-2.0 still fails.
        self.assertEqual(verdict(Component("x", declared="GPL-2.0")), "FORBIDDEN")

    def test_missing_and_unrecognised(self):
        self.check([
            (None, LIC_NONE), ("", LIC_NONE), ("NOASSERTION", LIC_NONE),
            ("   ", LIC_NONE), ("Some Vendor EULA", LIC_UNKNOWN),
            ("Apache License 1.1", LIC_UNKNOWN),
        ])

    def test_spdx_expressions(self):
        """OR is a choice we may elect; AND binds every branch."""
        self.check([
            ("MIT OR Apache-2.0", "MIT"),
            ("GPL-3.0 OR MIT", "MIT"),
            ("MIT AND GPL-3.0", "GPL-3.0"),
            ("Apache-2.0 AND MIT", "Apache-2.0"),
        ])

    def test_and_in_a_licence_name_is_not_an_operator(self):
        """A licence NAME containing 'and' must not be split as an expression.

        "Common Development and Distribution License" is one licence. Splitting
        on the word destroys the name and it classifies as UNKNOWN instead of
        the copyleft licence it actually is.
        """
        self.assertEqual(
            normalise("Common Development and Distribution License 1.0"), "CDDL-1.0")

    def test_no_body_text_matching(self):
        """MPL must not be dragged to AGPL by the words inside its own text.

        MPL-2.0 names GPL/LGPL/AGPL in its "Secondary License" definition, which
        is what makes body-text scanners report false copyleft. We classify the
        declared name only.
        """
        self.assertEqual(normalise("Mozilla Public License 2.0"), "MPL-2.0")
        self.assertEqual(normalise("MPL-2.0"), "MPL-2.0")


class TestVerdict(unittest.TestCase):
    def test_shipped_forbidden_fails(self):
        c = Component("org.eclipse.paho:client", declared="Eclipse Public License - Version 2.0")
        self.assertEqual(c.licence, "EPL-2.0")
        self.assertEqual(verdict(c), "FORBIDDEN")

    def test_shipped_permissive_passes(self):
        self.assertEqual(verdict(Component("okhttp", declared="Apache-2.0")), "allowed")

    def test_fails_closed_on_unknown(self):
        """An unrecognised licence is a finding, never a silent pass."""
        self.assertEqual(verdict(Component("x", declared="Weird EULA")), "FORBIDDEN")
        self.assertEqual(verdict(Component("y", declared=None)), "FORBIDDEN")

    def test_build_only_is_not_gated(self):
        """Test-scope copyleft never reaches a Customer Application."""
        c = Component("junit:junit", declared="Eclipse Public License 1.0", scope=BUILD_ONLY)
        self.assertEqual(c.licence, "EPL-1.0")
        self.assertNotEqual(verdict(c), "FORBIDDEN")

    def test_shipped_is_the_default_scope(self):
        self.assertEqual(Component("x", declared="MIT").scope, SHIPPED)


class TestExceptions(unittest.TestCase):
    """An exception records WHICH licence applies, verified against source."""

    def setUp(self):
        self._saved = dict(policy.EXCEPTIONS)
        policy.EXCEPTIONS.clear()

    def tearDown(self):
        policy.EXCEPTIONS.clear()
        policy.EXCEPTIONS.update(self._saved)

    def test_election_replaces_the_declared_licence(self):
        """Paho declares EPL-2.0 but is dual EPL-2.0 / EDL-1.0 at source."""
        name = "org.eclipse.paho:org.eclipse.paho.client.mqttv3"
        c = Component(name, declared="Eclipse Public License - Version 2.0")
        self.assertEqual(verdict(c), "FORBIDDEN")

        policy.EXCEPTIONS[name] = {"license": "BSD-3-Clause", "reason": "elects EDL-1.0"}
        self.assertEqual(c.licence, "BSD-3-Clause")
        self.assertEqual(verdict(c), "allowed (exception)")

    def test_an_exception_cannot_smuggle_copyleft_through(self):
        """Electing a forbidden licence still fails: exceptions pick, not waive."""
        policy.EXCEPTIONS["x"] = {"license": "GPL-3.0", "reason": "should not pass"}
        self.assertEqual(verdict(Component("x", declared="MIT")), "FORBIDDEN")

    def test_reason_is_mandatory(self):
        import json
        import tempfile
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as fh:
            json.dump({"exceptions": {"y": {"license": "MIT"}}}, fh)
            path = fh.name
        with self.assertRaises(SystemExit):
            policy.load_exceptions(path)
        os.unlink(path)


if __name__ == "__main__":
    unittest.main(verbosity=2)
