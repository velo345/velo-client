# Tester builds

One JSON file per Velo Client Tester build: `tester/builds/<version>.json` (version = next release + build number,
e.g. `1.5.1.1`). Then push the tag `tester-<version>` - CI builds all platforms and uploads them privately to the
Velo server; testers' launchers update by themselves and show these notes.

```json
{
  "title": "Short headline",
  "summary": "One or two sentences.",
  "changes": ["What changed, one line each"],
  "test": [
    {"id": "skybox-picker", "title": "What to test", "how": "Where to find it and what to try", "modules": ["custom-skybox"]}
  ]
}
```

`modules` (optional): Velo module ids - the owner's overview shows "used in game" when the tester had them on.
