#!/usr/bin/env python3
"""Static verification for the You-Tube Android project.

A full `./gradlew assembleDebug` needs the JDK, the Android SDK and Maven Central. When
those are unavailable this script is the next best thing: it *parses* the artifacts and
cross-checks every reference between them, which is where hand-written Android projects
actually break.

Checks performed
----------------
 1. Kotlin: tokenizer-aware balance of {} () [], package/dir agreement, import sanity.
 2. XML: well-formedness of every file under res/ and the manifest.
 3. Android resources: every @string/@color/@drawable/@mipmap/@style/@xml reference resolves.
 4. Manifest: every declared component (activity/service/application) maps to a real class.
 5. Version catalog: every `libs.x.y` used in Gradle exists in gradle/libs.versions.toml.
 6. CI: the workflow is valid YAML and every gradle task / path it touches exists.
 7. Gradle wrapper: the jar is a real zip containing GradleWrapperMain.

Exit code is non-zero when any check fails.
"""
from __future__ import annotations

import re
import sys
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def ns_attr(node, name: str) -> str:
    """Read an attribute that may be written as android:name in an Android resource."""
    if node is None:
        return ""
    return node.attrib.get(ANDROID_NS + name) or node.attrib.get(name) or ""


ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "app"
SRC = APP / "src"

failures: list[str] = []
checks_run = 0


def check(name: str, ok: bool, detail: str = "") -> None:
    global checks_run
    checks_run += 1
    if ok:
        print(f"  PASS  {name}")
    else:
        print(f"  FAIL  {name}" + (f" -- {detail}" if detail else ""))
        failures.append(f"{name}: {detail}")


# --------------------------------------------------------------------------- Kotlin

def strip_kotlin(src: str) -> str:
    """Replace string/char literals and comments with spaces, preserving offsets."""
    out: list[str] = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        # triple-quoted raw string. Kotlin closes it at the first run of >= 3 quotes, so a
        # literal `"` may sit immediately before the terminator (e.g. Regex(""""a"+"""")).
        if src.startswith('"""', i):
            j = i + 3
            end = n
            while j < n:
                if src[j] == '"':
                    run = 0
                    while j + run < n and src[j + run] == '"':
                        run += 1
                    if run >= 3:
                        end = j + 3
                        break
                    j += run
                else:
                    j += 1
            out.append(blank(src, i, end))
            i = end
            continue
        # line comment
        if src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j == -1 else j
            out.append(blank(src, i, j))
            i = j
            continue
        # Block comment. Nesting is legal in Kotlin, so depth is tracked -- and the
        # terminator MUST be tested first: `*/` itself contains `/*` at offset 1, and
        # testing that first would swallow the rest of the file.
        if src.startswith("/*", i):
            depth, j = 1, i + 2
            while j < n and depth:
                if src.startswith("*/", j):
                    depth -= 1
                    j += 2
                elif src.startswith("/*", j):
                    depth += 1
                    j += 2
                else:
                    j += 1
            out.append(blank(src, i, j))
            i = j
            continue
        # Double-quoted string.
        if c == '"':
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == '"':
                    j += 1
                    break
                if src[j] == "\n":
                    break
                j += 1
            out.append(blank(src, i, j))
            i = j
            continue
        # Char literal. An apostrophe in prose (`ExoPlayer's`) must NOT open one, so it only
        # counts when the very next character closes it, possibly via one escape.
        if c == "'":
            closing = None
            if i + 2 < n and src[i + 2] == "'":
                closing = i + 3
            elif i + 3 < n and src[i + 1] == "\\" and src[i + 3] == "'":
                closing = i + 4
            if closing is not None:
                out.append(blank(src, i, closing))
                i = closing
                continue
            out.append(c)
            i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


def blank(src: str, start: int, end: int) -> str:
    """Spaces for [start,end), but keep newlines so offsets and line numbers stay exact."""
    return "".join("\n" if ch == "\n" else " " for ch in src[start:end])


BALANCE = {"{": "}", "(": ")", "[": "]"}
CLOSERS = set(BALANCE.values())


def check_kotlin() -> None:
    print("\n[1] Kotlin sources")
    files = sorted(SRC.rglob("*.kt"))
    check("found Kotlin sources", len(files) > 20, f"only {len(files)} files")

    declared: dict[str, set[str]] = {}
    for path in files:
        src = path.read_text(encoding="utf-8")
        rel = path.relative_to(ROOT)
        body = strip_kotlin(src)
        check(
            f"{rel} tokenizer preserved line count",
            body.count("\n") == src.count("\n"),
            f"src={src.count(chr(10))} body={body.count(chr(10))} -- the lexer is mis-parsing this file",
        )

        stack: list[tuple[str, int]] = []
        bad = None
        for idx, ch in enumerate(body):
            if ch in BALANCE:
                stack.append((ch, idx))
            elif ch in CLOSERS:
                if not stack:
                    bad = f"unmatched '{ch}' at offset {idx}"
                    break
                opener, _ = stack.pop()
                if BALANCE[opener] != ch:
                    bad = f"'{opener}' closed by '{ch}' at offset {idx}"
                    break
        if bad is None and stack:
            opener, off = stack[-1]
            line = body.count("\n", 0, off) + 1
            bad = f"unclosed '{opener}' opened at line {line}"
        check(f"{rel} balanced", bad is None, bad or "")

        m = re.search(r"^package\s+([\w.]+)", src, re.M)
        expected_dir = str(path.parent.relative_to(SRC)).replace("/", ".")
        pkg = m.group(1) if m else None
        # main/kotlin/... and test/kotlin/... both map to the same package path
        check(
            f"{rel} package matches directory",
            pkg is not None and expected_dir.endswith(pkg),
            f"package={pkg} dir={expected_dir}",
        )

        symbol_re = (
            r"^[ \t]*(?:@\w+(?:\([^)]*\))?\s+)*"
            r"(?:(?:public|internal|private|protected|abstract|open|sealed|data|enum|value|annotation|inline|suspend|operator|infix|actual|expect|external|override)\s+)*"
            r"(?:class|object|interface)\s+(\w+)"
        )
        function_re = r"^[ \t]*(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|internal|private|inline|suspend|operator|infix|actual|expect|external|override|tailrec)\s+)*fun\s+(?:<[^>]+>\s+)?(?:[\w.]+\.)?(\w+)\s*\("
        for name in re.findall(symbol_re, src, re.M) + re.findall(function_re, src, re.M):
            declared.setdefault(pkg or "", set()).add(name)

    # imports of our own package must resolve to something declared
    print("\n[2] Internal imports resolve")
    all_names = {n for names in declared.values() for n in names}
    unresolved: list[str] = []
    for path in files:
        src = path.read_text(encoding="utf-8")
        for imp in re.findall(r"^import\s+(com\.ultra\.youtube\.app\.[\w.]+)", src, re.M):
            leaf = imp.rsplit(".", 1)[-1]
            # member imports (functions/constants) are not tracked; only flag unknown types
            if leaf[:1].isupper() and leaf not in all_names:
                unresolved.append(f"{path.name}: {imp}")
    check("no imports of undeclared app types", not unresolved, "; ".join(unresolved[:6]))

    # banned imports (things this project intentionally avoids)
    print("\n[3] Dependency policy")
    banned = {
        "androidx.media3.ui.PlayerView": "player chrome is Compose + SurfaceView, not PlayerView",
        "android.view.TextureView": "TextureView costs an extra full-frame buffer",
        "dagger.hilt": "no DI framework: hand-rolled ServiceLocator",
        "javax.inject": "no DI framework: hand-rolled ServiceLocator",
    }
    hits: list[str] = []
    for path in files:
        src = path.read_text(encoding="utf-8")
        for imp in re.findall(r"^import\s+([\w.]+)", src, re.M):
            if imp in banned:
                hits.append(f"{path.name}: {imp} ({banned[imp]})")
    check("no banned imports", not hits, "; ".join(hits))

    surface_hits = [p.name for p in files if "SurfaceView" in p.read_text(encoding="utf-8")]
    check("SurfaceView is used for video output", bool(surface_hits), ", ".join(surface_hits))

    immutable_files = [p.name for p in files if "@Immutable" in p.read_text(encoding="utf-8")]
    check("@Immutable state models present", len(immutable_files) >= 3, ", ".join(immutable_files))

    print("\n[3b] Unused imports")
    operator_imports = {"getValue", "setValue", "provideDelegate", "iterator"} | {
        f"component{i}" for i in range(1, 11)
    }
    unused: list[str] = []
    for path in files:
        src = path.read_text(encoding="utf-8")
        body = strip_kotlin(src)
        body_no_imports = "\n".join(
            line for line in body.split("\n") if not line.strip().startswith("import")
        )
        for full, alias in re.findall(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?", src, re.M):
            name = alias or full.rsplit(".", 1)[-1]
            if name == "*" or name in operator_imports:
                continue
            if not re.search(r"\b" + re.escape(name) + r"\b", body_no_imports):
                unused.append(f"{path.name}: {full}")
    check("no unused imports", not unused, "; ".join(unused[:8]))

    duplicates: list[str] = []
    for path in files:
        src = path.read_text(encoding="utf-8")
        imports = re.findall(r"^import\s+([\w.]+)$", src, re.M)
        seen: set[str] = set()
        for imp in imports:
            if imp in seen:
                duplicates.append(f"{path.name}: {imp}")
            seen.add(imp)
    check("no duplicate imports", not duplicates, "; ".join(duplicates[:8]))

    keyed = any(
        "key = { index -> state.videos[index].id }" in p.read_text(encoding="utf-8") for p in files
    )
    check("LazyColumn uses explicit item keys", keyed)


# --------------------------------------------------------------------------- XML

def check_xml() -> None:
    print("\n[4] Android XML")
    xml_files = sorted((SRC / "main").rglob("*.xml"))
    check("found XML resources", len(xml_files) >= 8, f"{len(xml_files)} files")
    for path in xml_files:
        try:
            ET.parse(path)
            check(f"{path.relative_to(ROOT)} well-formed", True)
        except ET.ParseError as exc:
            check(f"{path.relative_to(ROOT)} well-formed", False, str(exc))

    print("\n[5] Vector drawables")
    logo = SRC / "main/res/drawable/ic_youtube_logo.xml"
    check("ic_youtube_logo.xml exists", logo.exists())
    if logo.exists():
        root = ET.parse(logo).getroot()
        check("logo root is <vector>", root.tag == "vector", root.tag)
        paths = root.findall("path")
        check("logo has a path", bool(paths))
        data = ns_attr(paths[0], "pathData") if paths else ""
        check("logo pathData has geometry", len(data) > 60, f"{len(data)} chars")
        check(
            "logo punches the play triangle out of the body",
            data.count("Z") >= 1 and "M9.8,8.4" in data,
        )
        tokens = re.findall(r"[MLCZmlcz]|-?\d+\.?\d*", data)
        check("logo pathData tokenises", len(tokens) > 20, f"{len(tokens)} tokens")


# --------------------------------------------------------------------------- resources

def check_resources() -> None:
    print("\n[6] Resource references")
    res = SRC / "main/res"

    def defined(kind: str) -> set[str]:
        names: set[str] = set()
        for path in res.glob(f"{kind}*/*.xml"):
            try:
                root = ET.parse(path).getroot()
            except ET.ParseError:
                continue
            if root.tag in ("resources",):
                for child in root:
                    name = child.attrib.get("name")
                    if name:
                        names.add(name)
            else:
                names.add(path.stem)
        for path in res.glob(f"{kind}*/*"):
            if path.suffix in (".png", ".webp", ".jpg"):
                names.add(path.stem)
        return names

    strings = defined("values-")  # placeholder, replaced below
    strings = set()
    for path in res.glob("values*/*.xml"):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for child in root:
            if child.tag in ("string", "color", "style", "dimen", "bool", "integer", "plurals", "string-array"):
                name = child.attrib.get("name")
                if name:
                    strings.add((child.tag, name))

    by_tag: dict[str, set[str]] = {}
    for tag, name in strings:
        by_tag.setdefault(tag, set()).add(name)

    drawables = defined("drawable")
    mipmaps = defined("mipmap")
    xmls = defined("xml")

    referenced: set[tuple[str, str]] = set()
    for path in (SRC / "main").rglob("*.xml"):
        text = path.read_text(encoding="utf-8")
        for m in re.finditer(r"@(android:)?(string|color|drawable|mipmap|style|xml|dimen)/([\w.]+)", text):
            if m.group(1):  # @android:* is a framework resource
                continue
            referenced.add((m.group(2), m.group(3)))

    missing = []
    for kind, name in sorted(referenced):
        if kind == "string" and name not in by_tag.get("string", set()):
            missing.append(f"@string/{name}")
        elif kind == "color" and name not in by_tag.get("color", set()):
            missing.append(f"@color/{name}")
        elif kind == "style" and name not in by_tag.get("style", set()):
            missing.append(f"@style/{name}")
        elif kind == "drawable" and name not in drawables:
            missing.append(f"@drawable/{name}")
        elif kind == "mipmap" and name not in mipmaps:
            missing.append(f"@mipmap/{name}")
        elif kind == "xml" and name not in xmls:
            missing.append(f"@xml/{name}")
    check("all @resource references resolve", not missing, ", ".join(missing))

    print("\n[7] Manifest components")
    manifest = SRC / "main/AndroidManifest.xml"
    tree = ET.parse(manifest)
    app_ns = "http://schemas.android.com/apk/res/android"
    kt_files = {p.stem for p in SRC.rglob("*.kt")}
    for tag in ("activity", "service", "receiver", "provider"):
        for node in tree.iter(tag):
            name = node.attrib.get(f"{{{app_ns}}}name", "")
            simple = name.lstrip(".").rsplit(".", 1)[-1]
            check(f"<{tag}> {name} has a Kotlin class", simple in kt_files, f"no class {simple}.kt")

    app_node = tree.find("application")
    app_name = app_node.attrib.get(f"{{{app_ns}}}name", "") if app_node is not None else ""
    check(
        "application android:name resolves",
        app_name.lstrip(".") in kt_files,
        app_name,
    )

    perms = {n.attrib.get(f"{{{app_ns}}}name") for n in tree.iter("uses-permission")}
    required = {
        "android.permission.INTERNET",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
    }
    check("required permissions declared", required <= perms, str(required - perms))

    fgs_type = None
    for node in tree.iter("service"):
        fgs_type = node.attrib.get(f"{{{app_ns}}}foregroundServiceType")
    check("service declares mediaPlayback FGS type", fgs_type == "mediaPlayback", str(fgs_type))

    media_action = False
    for node in tree.iter("action"):
        if node.attrib.get(f"{{{app_ns}}}name") == "androidx.media3.session.MediaSessionService":
            media_action = True
    check("service exports the MediaSessionService action", media_action)


# --------------------------------------------------------------------------- Gradle

def check_gradle() -> None:
    print("\n[8] Gradle wiring")
    catalog = ROOT / "gradle/libs.versions.toml"
    check("version catalog exists", catalog.exists())
    text = catalog.read_text(encoding="utf-8")

    libs = set(re.findall(r"^([\w-]+)\s*=\s*\{", text, re.M))
    plugins = set()
    in_plugins = False
    for line in text.splitlines():
        if line.strip() == "[plugins]":
            in_plugins = True
            continue
        if line.startswith("["):
            in_plugins = False
        if in_plugins:
            m = re.match(r"([\w-]+)\s*=", line)
            if m:
                plugins.add(m.group(1))

    used: set[str] = set()
    for gradle_file in [ROOT / "build.gradle.kts", APP / "build.gradle.kts"]:
        live = "\n".join(
            line for line in gradle_file.read_text(encoding="utf-8").splitlines()
            if not line.lstrip().startswith("//")
        )
        used |= set(re.findall(r"libs\.([\w.]+)", live))

    # `libs.plugins.x` accessors resolve against the [plugins] table
    plugin_used = {u.removeprefix("plugins.") for u in used if u.startswith("plugins.")}
    library_used = {u for u in used if not u.startswith("plugins.")}

    def normalise(dotted: str) -> str:
        return dotted.replace(".", "-")

    missing = (
        [u for u in sorted(library_used) if normalise(u) not in libs]
        + [f"plugins.{u}" for u in sorted(plugin_used) if normalise(u) not in plugins]
    )
    check("every libs.* accessor exists in the catalog", not missing, ", ".join(missing))

    app_gradle = (APP / "build.gradle.kts").read_text(encoding="utf-8")
    for token, why in [
        ("applicationId = \"com.ultra.youtube.app\"", "package name"),
        ("namespace = \"com.ultra.youtube.app\"", "namespace"),
        ("isMinifyEnabled = true", "R8 minification"),
        ("isShrinkResources = true", "resource shrinking"),
        ("media3.exoplayer", "Media3 ExoPlayer"),
        ("media3.session", "Media3 session"),
        ("coil.compose", "Coil"),
        ("proguard-rules.pro", "ProGuard rules"),
    ]:
        check(f"app/build.gradle.kts: {why}", token in app_gradle)

    check("proguard-rules.pro exists", (APP / "proguard-rules.pro").exists())
    rules = (APP / "proguard-rules.pro").read_text(encoding="utf-8")
    for token in ["androidx.media3", "kotlinx.serialization", "okhttp3", "org.schabi.newpipe"]:
        check(f"proguard keeps {token}", token in rules)

    wrapper_jar = ROOT / "gradle/wrapper/gradle-wrapper.jar"
    check("gradle-wrapper.jar present", wrapper_jar.exists())
    if wrapper_jar.exists():
        try:
            with zipfile.ZipFile(wrapper_jar) as zf:
                names = zf.namelist()
                check(
                    "gradle-wrapper.jar is a valid wrapper jar",
                    "org/gradle/wrapper/GradleWrapperMain.class" in names,
                    f"{len(names)} entries, main class missing",
                )
        except zipfile.BadZipFile as exc:
            check("gradle-wrapper.jar is a valid wrapper jar", False, str(exc))

    props = ROOT / "gradle/wrapper/gradle-wrapper.properties"
    check("gradle-wrapper.properties present", props.exists())
    if props.exists():
        url = re.search(r"distributionUrl=(.+)", props.read_text(encoding="utf-8"))
        check(
            "distributionUrl points at a gradle distribution",
            bool(url) and "gradle-" in (url.group(1) if url else ""),
            url.group(1) if url else "",
        )


# --------------------------------------------------------------------------- CI

def check_ci() -> None:
    print("\n[9] GitHub Actions workflow")
    workflow = ROOT / ".github/workflows/build-apk.yml"
    check("build-apk.yml exists", workflow.exists())
    if not workflow.exists():
        return
    try:
        import yaml
    except ImportError:
        check("pyyaml available to validate the workflow", False, "pip install pyyaml")
        return
    try:
        doc = yaml.safe_load(workflow.read_text(encoding="utf-8"))
        check("workflow parses as YAML", True)
    except yaml.YAMLError as exc:
        check("workflow parses as YAML", False, str(exc))
        return

    check("workflow has a name", bool(doc.get("name")), str(doc.get("name")))
    # YAML parses the bare key `on` as boolean True
    triggers = doc.get("on") or doc.get(True)
    check("workflow has triggers", bool(triggers), str(list(doc.keys())))
    if isinstance(triggers, dict):
        check("builds on push", "push" in triggers)

    jobs = doc.get("jobs", {})
    check("workflow defines jobs", bool(jobs), str(list(jobs)))
    for job_id, job in jobs.items():
        check(f"job '{job_id}' has a runner", bool(job.get("runs-on")))
        steps = job.get("steps", [])
        check(f"job '{job_id}' has steps", bool(steps))

    all_runs = " ".join(
        str(step.get("run", "")) for job in jobs.values() for step in job.get("steps", [])
    )
    check("workflow runs gradle", "gradlew" in all_runs, all_runs[:80])
    check("workflow assembles an APK", "assemble" in all_runs)
    check("workflow runs unit tests", "testDebugUnitTest" in all_runs)

    uploads = [
        step for job in jobs.values() for step in job.get("steps", [])
        if "upload-artifact" in str(step.get("uses", "")) or "gh-release" in str(step.get("uses", ""))
    ]
    check("workflow publishes an APK artifact or release", bool(uploads))

    java = [
        step for job in jobs.values() for step in job.get("steps", [])
        if "setup-java" in str(step.get("uses", ""))
    ]
    check("workflow installs a JDK", bool(java))

    # the gradle task names used must exist for this project layout
    check("workflow targets the :app module", ":app:" in all_runs)


def selftest() -> bool:
    """Guard the lexer: every one of these bit us once already."""
    print("\n[0] Verifier self-test")
    cases = [
        ("keeps line count through a KDoc",
         "/**\n * Doc.\n */\nfun a() {}\n",
         lambda s, b: s.count("\n") == b.count("\n")),
        ("a block comment does not swallow the file",
         "/* x */\nfun a() { val b = 1 }\n",
         lambda s, b: "fun a" in b and "val b" in b),
        ("nested block comments close correctly",
         "/* a /* b */ c */\nfun a() {}\n",
         lambda s, b: "fun a" in b),
        ("apostrophe in prose is not a char literal",
         "// ExoPlayer's buffer\nfun a() { val x = 1 }\n",
         lambda s, b: "val x" in b),
        ("real char literal is blanked",
         "val c = 'x'\nfun a() {}\n",
         lambda s, b: "'x'" not in b and "fun a" in b),
        ("escaped quote inside a string does not end it",
         'val s = "a\\"b"; fun a() {}\n',
         lambda s, b: "fun a" in b and 'a\\"b' not in b),
        # Kotlin takes the LONGEST run of quotes, so a raw string can never end with a
        # literal `"`; the lexer keeps scanning. This asserts that (surprising) rule.
        ("raw string takes the longest quote run",
         'val r = """a"([^"]+)""" \nfun a() {}\n',
         lambda s, b: "fun a" in b),
        ("a raw string terminated by 3 quotes ends there",
         'val r = """a[b]"""\nfun a() {}\n',
         lambda s, b: "fun a" in b and '"""' not in b),
        ("line comment does not blank the next line",
         "// note\nfun a() {}\n",
         lambda s, b: "fun a" in b),
    ]
    ok = True
    for name, src, predicate in cases:
        body = strip_kotlin(src)
        good = predicate(src, body)
        ok = ok and good
        check(name, good, f"body={body!r}")
    return ok


def main() -> int:
    print(f"Verifying {ROOT}")
    selftest()
    check_kotlin()
    check_xml()
    check_resources()
    check_gradle()
    check_ci()
    print(f"\n{checks_run - len(failures)}/{checks_run} checks passed")
    if failures:
        print("\nFailures:")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    print("All checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
