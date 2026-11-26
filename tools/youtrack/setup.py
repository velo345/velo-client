"""
Sets up the VELO project on velo-client.youtrack.cloud for tester / player reports (safe to run again: it only adds what
is missing). Everything in English.

  python3 tools/youtrack/setup.py
"""
import yt

PROJECT = "VELO"
ADMIN = None   # the token's own user (looked up)

TYPES = [("Bug", "20"), ("Crash", "19"), ("Performance", "13"), ("Visual / UI", "11"), ("Feature Request", "6"),
         ("Suggestion", "8"), ("Task", "0")]
# (name, resolved, colour id)
STATES = [("New", False, "2"), ("Confirmed", False, "13"), ("In Progress", False, "6"), ("Needs Info", False, "8"),
          ("Fixed", True, "4"), ("Verified", True, "3"), ("Can't Reproduce", True, "0"), ("Duplicate", True, "0"),
          ("Won't Fix", True, "0")]
AREAS = ["Launcher", "Game", "Title Screen", "HUD & Modules", "Cosmetics", "Velo+", "Performance", "Clips & Rewind",
         "Friends & Party", "Store", "Velo World", "Installer & Updates", "Server", "Website"]
PLATFORMS = ["Windows", "macOS", "Linux"]
MINECRAFT = ["1.8.9", "1.21.1", "1.21.10", "1.21.11", "26.1", "26.2", "26.3"]
SOURCES = ["Tester Window", "Test Plan", "In-Game", "Launcher", "Crash Telemetry", "Discord"]
# Tags, in four families (colour per family): where it came from, what's attached, the player's setup (set by the
# server from logs / mod lists) and triage (set by hand).
TAGS = [
    # origin
    ("tester", "4"), ("player-report", "4"), ("telemetry", "4"), ("discord", "4"), ("discord-bug", "4"), ("discord-suggestion", "4"),
    # evidence
    ("has-video", "24"), ("has-screenshot", "24"), ("has-logs", "24"), ("has-crash-log", "24"),
    # setup
    ("vulkan", "11"), ("opengl", "11"), ("sodium", "11"), ("iris", "11"), ("essential", "11"), ("modded", "11"),
    # triage
    ("needs-repro", "13"), ("regression", "8"), ("quick-win", "3"), ("release-blocker", "8"),
]
RENAMED_TAGS = {"has-game-log": "has-logs"}
DEMO_TAGS = ["produktivität", "Tipp"]


def project():
    for p in yt.get("admin/projects", "id,shortName", "$top=100"):
        if p["shortName"] == PROJECT:
            return p["id"]
    return yt.post("admin/projects", {"name": "Velo Client", "shortName": PROJECT, "leader": {"id": ADMIN}}, "id")["id"]


def bundle(kind, name, values, extra=None):
    """An enum/state/ownedField bundle with these values (created or completed)."""
    found = [b for b in yt.get(f"admin/customFieldSettings/bundles/{kind}", "id,name", "$top=200") if b["name"] == name]
    bid = found[0]["id"] if found else yt.post(f"admin/customFieldSettings/bundles/{kind}", {"name": name}, "id")["id"]
    have = {v["name"]: v["id"] for v in yt.get(f"admin/customFieldSettings/bundles/{kind}/{bid}/values", "id,name", "$top=200")}
    for i, value in enumerate(values):
        name_ = value[0] if isinstance(value, tuple) else value
        body = {"name": name_, "ordinal": i}
        if kind == "state":
            body["isResolved"] = value[1]
            body["color"] = {"id": value[2]}
        elif kind == "enum" and isinstance(value, tuple):
            body["color"] = {"id": value[1]}
        if extra:
            body.update(extra)
        if name_ in have:
            yt.post(f"admin/customFieldSettings/bundles/{kind}/{bid}/values/{have[name_]}", body, "id")
        else:
            yt.post(f"admin/customFieldSettings/bundles/{kind}/{bid}/values", body, "id")
    return bid


def prototype(name, field_type):
    for f in yt.get("admin/customFieldSettings/customFields", "id,name", "$top=200"):
        if f["name"] == name:
            return f["id"]
    return yt.post("admin/customFieldSettings/customFields", {"name": name, "fieldType": {"id": field_type}}, "id")["id"]


def attach(pid, field_id, ptype, bundle_id=None, empty="Unknown", can_be_empty=True, default=None):
    fields = yt.get(f"admin/projects/{pid}/customFields", "id,field(id),bundle(id)", "$top=100")
    current = [f for f in fields if f["field"]["id"] == field_id]
    if current and bundle_id and current[0].get("bundle", {}).get("id") != bundle_id:
        yt.call("DELETE", f"admin/projects/{pid}/customFields/{current[0]['id']}")
        current = []
    body = {"$type": ptype, "field": {"id": field_id}, "canBeEmpty": can_be_empty, "emptyFieldText": empty}
    if bundle_id:
        body["bundle"] = {"id": bundle_id}
    if default:
        body["defaultValues"] = default
    if current:
        yt.post(f"admin/projects/{pid}/customFields/{current[0]['id']}", body, "id")
    else:
        yt.post(f"admin/projects/{pid}/customFields", body, "id")


def main():
    global ADMIN
    ADMIN = yt.get("users/me", "id")["id"]
    english = {"id": "en_US", "locale": "en_US", "language": "en"}
    yt.post("admin/globalSettings/localeSettings", {"locale": english}, "id")
    yt.post("users/me/profiles/general", {"locale": english}, "id")
    pid = project()
    for f in yt.get(f"admin/projects/{pid}/customFields", "id,field(name),$type", "$top=50"):
        if f["field"]["name"] == "Assignee":
            yt.post(f"admin/projects/{pid}/customFields/{f['id']}", {"$type": f["$type"], "defaultValues": [{"id": ADMIN}],
                                                                    "canBeEmpty": True, "emptyFieldText": "Unassigned"}, "id")
    yt.post(f"admin/projects/{pid}", {
        "name": "Velo Client",
        "description": "Bugs, crashes and ideas from Velo Client testers and players. Reports from the Tester window "
                       "arrive here automatically with screenshots, videos and logs; status and public comments go "
                       "back to the reporter's Tester window.",
    }, "id")
    proto = {f["name"]: f["id"] for f in yt.get("admin/customFieldSettings/customFields", "id,name", "$top=200")}

    # Type, State: own English lists for this project
    type_bundle = bundle("enum", "Velo: Type", TYPES)
    bug = [v for v in yt.get(f"admin/customFieldSettings/bundles/enum/{type_bundle}/values", "id,name") if v["name"] == "Bug"][0]
    attach(pid, proto["Type"], "EnumProjectCustomField", type_bundle, can_be_empty=False, default=[{"id": bug["id"]}])
    state_bundle = bundle("state", "Velo: State", STATES)
    new = [v for v in yt.get(f"admin/customFieldSettings/bundles/state/{state_bundle}/values", "id,name") if v["name"] == "New"][0]
    attach(pid, proto["State"], "StateProjectCustomField", state_bundle, can_be_empty=False, default=[{"id": new["id"]}])

    # the project's own lists get English names; Subsystem holds the areas
    for f in yt.get(f"admin/projects/{pid}/customFields", "id,field(name),bundle(id,$type)", "$top=100"):
        b = f.get("bundle")
        if not b:
            continue
        field = f["field"]["name"]
        kind = {"OwnedBundle": "ownedField", "VersionBundle": "version", "BuildBundle": "build", "UserBundle": "user"}.get(b["$type"])
        names = {"Subsystem": "Velo: Area", "Fix versions": "Velo: Versions", "Affected versions": "Velo: Versions",
                 "Fixed in build": "Velo: Builds", "Assignee": "Velo: Team"}
        if kind and field in names:
            yt.post(f"admin/customFieldSettings/bundles/{kind}/{b['id']}", {"name": names[field]}, "id")
        if field == "Subsystem":
            bundle("ownedField", "Velo: Area", AREAS)

    # extra fields the Tester window fills in
    platform_bundle = bundle("enum", "Velo: Platform", PLATFORMS)
    minecraft_bundle = bundle("enum", "Velo: Minecraft", MINECRAFT)
    source_bundle = bundle("enum", "Velo: Source", SOURCES)
    attach(pid, prototype("Tester", "string"), "SimpleProjectCustomField", empty="Nobody")
    attach(pid, prototype("Tester UUID", "string"), "SimpleProjectCustomField", empty="-")
    attach(pid, prototype("Platform", "enum[1]"), "EnumProjectCustomField", platform_bundle)
    attach(pid, prototype("Minecraft", "enum[1]"), "EnumProjectCustomField", minecraft_bundle)
    attach(pid, prototype("Source", "enum[1]"), "EnumProjectCustomField", source_bundle)
    attach(pid, prototype("Test Item", "string"), "SimpleProjectCustomField", empty="-")
    attach(pid, prototype("Velo Build", "string"), "SimpleProjectCustomField", empty="-")
    attach(pid, prototype("Velo Report", "string"), "SimpleProjectCustomField", empty="-")
    attach(pid, prototype("Player", "string"), "SimpleProjectCustomField", empty="-")
    attach(pid, prototype("Occurrences", "integer"), "SimpleProjectCustomField", empty="1")

    # tags: shared with everyone on the instance, editable by the project team
    everyone = [g for g in yt.get("groups", "id,name", "$top=50") if g["name"] == "All Users"][0]["id"]
    team = [g for g in yt.get("groups", "id,name", "$top=50") if g["name"] == "Velo Client Team"][0]["id"]
    tags = {t["name"]: t["id"] for t in yt.get("tags", "id,name", "$top=200")}
    for old, new in RENAMED_TAGS.items():
        if old in tags and new not in tags:
            yt.post(f"tags/{tags[old]}", {"name": new}, "id")
            tags[new] = tags.pop(old)
    for name in DEMO_TAGS:
        if name in tags:
            yt.call("DELETE", f"tags/{tags.pop(name)}")
    for name, color in TAGS:
        body = {"name": name, "color": {"id": color}, "visibleFor": {"id": everyone}, "updateableBy": {"id": team}}
        if name in tags:
            yt.post(f"tags/{tags[name]}", body, "id")
        else:
            yt.post("tags", body, "id")

    # saved searches
    queries = [
        ("Tester inbox", f"project: {PROJECT} State: New sort by: created desc"),
        ("Open tester reports", f"project: {PROJECT} #Unresolved has: Tester sort by: Priority, updated desc"),
        ("Broken test items", f"project: {PROJECT} Source: {{Test Plan}} #Unresolved"),
        ("Top crashes", f"project: {PROJECT} Type: Crash #Unresolved sort by: Occurrences desc"),
        ("Player bug reports", f"project: {PROJECT} tag: player-report Type: -Crash #Unresolved sort by: created desc"),
        ("Needs info from testers", f"project: {PROJECT} State: {{Needs Info}}"),
        ("Fixed - waiting for verify", f"project: {PROJECT} State: Fixed sort by: updated desc"),
    ]
    have = {q["name"]: q["id"] for q in yt.get("savedQueries", "id,name", "$top=200")}
    for name, query in queries:
        if name in have:
            yt.post(f"savedQueries/{have[name]}", {"query": query}, "id")
        else:
            yt.post("savedQueries", {"name": name, "query": query}, "id")
    # the Velo Testing board: card colour = Type, swimlanes = Priority
    boards = {b["name"]: b["id"] for b in yt.get("agiles", "id,name", "$top=50")}
    if "Velo Testing" not in boards:
        columns = [["New"], ["Confirmed"], ["In Progress"], ["Needs Info"], ["Fixed"], ["Verified"]]
        boards["Velo Testing"] = yt.post("agiles", {
            "name": "Velo Testing", "projects": [{"id": pid}], "sprintsSettings": {"disableSprints": True},
            "columnSettings": {"field": {"id": proto["State"]}, "columns": [
                {"fieldValues": [{"name": n} for n in c], "isResolved": c[0] in ("Fixed", "Verified")} for c in columns]},
        }, "id", query="template=kanban")["id"]
    if "Velo Testing" in boards:
        yt.post(f"agiles/{boards['Velo Testing']}", {
            "colorCoding": {"$type": "FieldBasedColorCoding", "prototype": {"id": proto["Type"]}},
            "swimlaneSettings": {"$type": "AttributeBasedSwimlaneSettings", "enabled": True,
                                 "field": {"$type": "CustomFilterField", "id": proto["Priority"]},
                                 "values": [{"name": n} for n in ["Show-stopper", "Critical", "Major", "Normal", "Minor"]]},
        }, "id")

    print("project", pid, "types", type_bundle, "states", state_bundle)


if __name__ == "__main__":
    main()
