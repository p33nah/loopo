"""Check local links and image assets referenced by the GitHub Pages tour.

Run from any directory with: python tools/check_pages.py
"""

from html.parser import HTMLParser
from pathlib import Path
import re
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parents[1]
SITE = ROOT / "site"
INDEX = SITE / "index.html"
TOUR = SITE / "tour.js"
WORKFLOW = ROOT / ".github" / "workflows" / "pages.yml"
APK_URL = "https://github.com/p33nah/loopo/releases/download/v0.0.1/loopo-v0.0.1.apk"
RELEASE_URL = "https://github.com/p33nah/loopo/releases/tag/v0.0.1"


class LocalReferences(HTMLParser):
    def __init__(self):
        super().__init__()
        self.references = []

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        for name in ("href", "src"):
            value = attributes.get(name)
            if value:
                self.references.append(value)


def main():
    errors = []
    for required in (INDEX, TOUR, WORKFLOW):
        if not required.is_file():
            errors.append(f"Missing required file: {required.relative_to(ROOT)}")

    if errors:
        raise SystemExit("\n".join(errors))

    html = INDEX.read_text(encoding="utf-8")
    parser = LocalReferences()
    parser.feed(html)

    for reference in parser.references:
        parsed = urlsplit(reference)
        if parsed.scheme or parsed.netloc or reference.startswith("#"):
            continue

        relative_path = unquote(parsed.path)
        target = (SITE / relative_path).resolve() if relative_path else INDEX.resolve()
        try:
            target.relative_to(SITE.resolve())
        except ValueError:
            errors.append(f"Reference escapes site/: {reference}")
            continue
        if target.is_dir():
            target /= "index.html"
        if not target.is_file():
            errors.append(f"Missing local reference: {reference}")

    for asset in re.findall(r'image\s*:\s*["\']([^"\']+\.(?:png|jpe?g|webp))["\']', TOUR.read_text(encoding="utf-8"), re.I):
        if not (SITE / "assets" / asset).is_file():
            errors.append(f"Missing tutorial image: assets/{asset}")

    if APK_URL not in html:
        errors.append("The official v0.0.1 APK link is missing or changed.")
    if RELEASE_URL not in html:
        errors.append("The official v0.0.1 release link is missing or changed.")
    if "path: site" not in WORKFLOW.read_text(encoding="utf-8"):
        errors.append("The Pages workflow must publish the site/ directory.")

    if errors:
        raise SystemExit("\n".join(errors))

    print("Pages tour links, screenshots, and deployment path are present.")


if __name__ == "__main__":
    main()
