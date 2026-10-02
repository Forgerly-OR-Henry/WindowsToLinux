"""Configure project-local IDEA inspections and save actions. / 配置项目级 IDEA 检查与保存动作。"""

from copy import deepcopy
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from format import BASE, GENERATED, ROOT, SUFFIXES, idea_installation

PROFILE = "WindowsToLinux Main"
LANGUAGES = {"", "JAVA", "UAST", "JVM", "XML", "JavaScript", "ECMAScript 6", "JSON",
             "TOML", "Properties", "SQL", "RegExp", "HTML", "HtmlCompatible", "CSS"}
EXTRA_CHECKS = {"IncorrectFormatting": "WEAK WARNING", "UNUSED_IMPORT": "WARNING",
                "MISSORTED_IMPORTS": "WARNING", "OnDemandImport": "WARNING"}
FILE_TYPES = ("JAVA", "JavaScript", "XML", "SVG", "JSON", "TOML", "Properties", "SQL")


def scope_pattern():
    # IDEA file patterns are relative to each module's content root, not always the repository root.
    # IDEA 文件范围相对于各模块内容根目录；src/test 必须保留。
    roots = ("file[windowstolinux-*]:src//* || file[windowstolinux-*]:pom.xml || "
             "file[windowstolinux-parent]:.mvn/wtlmod-author-parent/pom.xml || "
             "file[windowstolinux-parent]:.mvn/formatting/idea.xml")
    suffixes = " || ".join("file:*" + suffix for suffix in sorted(SUFFIXES))
    exclusions = " && ".join(
        "!file:" + directory + "//* && !file:*//" + directory + "//*"
        for directory in sorted(GENERATED)
    )
    # The IDEA scope parser rejects whitespace around operators. / 范围表达式的运算符旁不能留空格。
    return (f"({roots}) && ({suffixes}) && {exclusions} && "
            "!file:*//original-codec-goldens.properties && !file:*//package-lock.json").replace(" ", "")


def inspection_defaults(home):
    """Read the installed engine's inspection registrations, keeping its default severities."""
    checks = {}
    available = set()
    for jar in sorted(home.rglob("*.jar")):
        with ZipFile(jar) as archive:
            for name in archive.namelist():
                if not (name.endswith(".xml") and name.startswith(("META-INF/", "intellij."))):
                    continue
                try:
                    root = ET.fromstring(archive.read(name))
                except ET.ParseError:
                    continue
                for entry in root.iter():
                    if entry.tag not in {"localInspection", "globalInspection"}:
                        continue
                    if entry.get("language", "") not in LANGUAGES:
                        continue
                    short = entry.get("shortName")
                    if not short:
                        implementation = entry.get("implementationClass", "").rsplit(".", 1)[-1]
                        short = implementation.removesuffix("Inspection")
                    if not short:
                        continue
                    available.add(short)
                    if entry.get("enabledByDefault") == "true":
                        checks[short] = entry.get("level", "WARNING")
    missing = EXTRA_CHECKS.keys() - available
    if missing:
        raise RuntimeError("IDEA is missing required inspections: " + ", ".join(sorted(missing)))
    checks.update(EXTRA_CHECKS)
    return checks


def xml_bytes(root):
    ET.indent(root, space="  ")
    return ET.tostring(root, encoding="utf-8", xml_declaration=True) + b"\n"


def write_xml(path, element):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(xml_bytes(element))


def merge_component(path, component):
    """Replace only our component, preserving unrelated settings and detecting concurrent edits."""
    before = path.read_bytes() if path.exists() else None
    text = before.decode("utf-8") if before is not None else '<project version="4">\n</project>\n'
    root = ET.fromstring(text)
    existing = root.find(f"component[@name='{component.get('name')}']")
    ET.indent(component, space="  ", level=1)
    replacement = ET.tostring(component, encoding="unicode")
    if existing is not None:
        name = re.escape(component.get("name"))
        pattern = rf'<component\s+name="{name}"(?:\s[^>]*?)?(?:\s*/>|>.*?</component>)'
        text, count = re.subn(pattern, lambda _: replacement, text, count=1, flags=re.DOTALL)
        if count != 1:
            raise RuntimeError(f"Cannot safely update component in {path}")
    else:
        text = text.replace("</project>", "  " + replacement + "\n</project>", 1)
    ET.fromstring(text)
    if (path.read_bytes() if path.exists() else None) != before:
        raise RuntimeError(f"IDEA changed {path}; retry after it finishes saving")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(text.encode("utf-8"))


def save_action(name, file_types):
    component = ET.Element("component", name=name)
    ET.SubElement(component, "option", name="myRunOnSave", value="true")
    ET.SubElement(component, "option", name="myAllFileTypesSelected", value="false")
    selected = ET.SubElement(ET.SubElement(component, "option", name="mySelectedFileTypes"), "set")
    for file_type in file_types:
        ET.SubElement(selected, "option", value=file_type)
    return component


def configure(root, checks):
    idea = root / ".idea"
    scope = scope_pattern()
    component = ET.Element("component", name="DependencyValidationManager")
    ET.SubElement(component, "scope", name=PROFILE, pattern=scope)
    scope_file = idea / "scopes" / "WindowsToLinux_Main.xml"
    write_xml(scope_file, component)

    component = ET.Element("component", name="InspectionProjectProfileManager")
    profile = ET.SubElement(component, "profile", version="1.0", is_locked="true")
    ET.SubElement(profile, "option", name="myName", value=PROFILE)
    for name, level in sorted(checks.items()):
        tool = ET.SubElement(profile, "inspection_tool", {"class": name, "enabled": "true",
                             "level": level, "enabled_by_default": "false"})
        ET.SubElement(tool, "scope", name=PROFILE, level=level, enabled="true")
    profile_file = idea / "inspectionProfiles" / "WindowsToLinux_Main.xml"
    write_xml(profile_file, component)
    component = ET.Element("component", name="InspectionProjectProfileManager")
    settings = ET.SubElement(component, "settings")
    ET.SubElement(settings, "option", name="PROJECT_PROFILE", value=PROFILE)
    ET.SubElement(settings, "option", name="USE_PROJECT_PROFILE", value="true")
    ET.SubElement(settings, "version", value="1.0")
    write_xml(idea / "inspectionProfiles" / "profiles_settings.xml", component)

    component = ET.Element("component", name="ProjectCodeStyleConfiguration")
    style = deepcopy(ET.parse(BASE / "idea.xml").getroot())
    style.set("name", "Project")
    excluded = ET.SubElement(ET.SubElement(style, "option", name="DO_NOT_FORMAT"), "list")
    ET.SubElement(excluded, "fileSet", type="namedScope", name="WindowsToLinux Outside Main",
                  pattern=f"!({scope})")
    component.append(style)
    write_xml(idea / "codeStyles" / "Project.xml", component)
    component = ET.Element("component", name="ProjectCodeStyleConfiguration")
    ET.SubElement(component, "state")
    ET.SubElement(component[0], "option", name="USE_PER_PROJECT_SETTINGS", value="true")
    write_xml(idea / "codeStyles" / "codeStyleConfig.xml", component)
    merge_component(idea / "workspace.xml", save_action("FormatOnSaveOptions", FILE_TYPES))
    merge_component(idea / "workspace.xml", save_action("OptimizeOnSaveOptions", ("JAVA", "JavaScript")))


def main():
    home, info, _ = idea_installation()
    checks = inspection_defaults(home)
    configure(ROOT, checks)
    print(f"Configured {len(checks)} inspection registrations for IntelliJ IDEA {info['version']}.")
    print("Project profile: " + PROFILE)
    print("Save actions: reformat supported main-project files; optimize Java/JavaScript imports.")
    print("Reopen the project if IDEA was open, then select this scope in Code > Inspect Code.")


if __name__ == "__main__":
    main()
