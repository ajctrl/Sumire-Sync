from pathlib import Path
import tempfile
import unittest

from verify_gradle_distribution import read_properties


class WrapperPropertiesTests(unittest.TestCase):
    def read(self, data):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "gradle-wrapper.properties"
            path.write_bytes(data)
            return read_properties(path)

    def test_standard_wrapper_url_and_java_whitespace(self):
        properties = self.read(b"! comment\r\n distributionUrl \t= https\\://services.gradle.org/release.zip\r\n")
        self.assertEqual(properties, {"distributionUrl": "https://services.gradle.org/release.zip"})

    def test_rejects_duplicate_keys_with_different_spacing(self):
        for key in (b"distributionUrl", b"distributionSha256Sum"):
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "Duplicate"):
                self.read(key + b"=first\n\t" + key + b" = second\n")

    def test_rejects_escaped_keys_and_continuation_lines(self):
        for declaration in (br"distribution\u0055rl=second", b"distribution\\\n Url=second"):
            with self.subTest(declaration=declaration), self.assertRaisesRegex(ValueError, "Unsupported"):
                self.read(b"distributionUrl=first\n" + declaration + b"\n")
