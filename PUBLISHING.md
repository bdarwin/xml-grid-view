# Publishing

Releases are tag-driven. When you push `vX.Y.Z`, the
[release workflow](.github/workflows/release.yml) does the following:

1. Checks that the tag matches both `packages/vscode/package.json` and
   `packages/intellij/gradle.properties`.
2. Runs lint, vitest, the golden-fixture check, the VS Code smoke test, JUnit
   and the JetBrains Plugin Verifier.
3. Signs the IntelliJ plugin.
4. Creates a GitHub release with the `.vsix` and the signed plugin zip.
5. Publishes to each marketplace whose token secret is set. Marketplaces
   without a token are skipped.

## Cutting a release

```sh
# bump versions (keep them in sync) and add a CHANGELOG.md section "## X.Y.Z"
#   packages/vscode/package.json        "version"
#   packages/intellij/gradle.properties pluginVersion
#   package.json                        "version"
git commit -am "Release X.Y.Z"
git tag vX.Y.Z && git push origin main vX.Y.Z
```

## One-time setup

| Secret | Status | Used for |
| --- | --- | --- |
| `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD` | ✅ set | Plugin signing (self-signed certificate, valid to 2036) |
| `VSCE_PAT` | needs you | VS Code Marketplace |
| `OVSX_PAT` | needs you | Open VSX |
| `JETBRAINS_PUBLISH_TOKEN` | needs you | JetBrains Marketplace |

To set a secret, run `gh secret set NAME -R bdarwin/xml-grid-view` and paste
the value.

The signing key's only copies are the repository secrets and
`~/.config/xml-grid-view/signing/` on the machine that generated it. Back that
folder up somewhere safe: JetBrains Marketplace expects later releases to be
signed with the same certificate.

### VS Code Marketplace (`VSCE_PAT`)

1. Sign in at <https://marketplace.visualstudio.com/manage> and create the
   publisher. The ID must be exactly **`bdarwin`** (it's the `publisher` in
   `packages/vscode/package.json`). Set the display name to **Darwin Baisa**.
2. Create a personal access token at <https://dev.azure.com>: **User settings →
   Personal access tokens → New token**.
   - Organization: **All accessible organizations**.
   - Scopes: **Custom defined → Marketplace → Manage**.
3. Run `gh secret set VSCE_PAT -R bdarwin/xml-grid-view`.

### Open VSX (`OVSX_PAT`)

1. Sign in at <https://open-vsx.org> with GitHub.
2. Link an Eclipse account and sign the Publisher Agreement under **Settings →
   Profile**.
3. Create an access token under **Settings → Access Tokens**.
4. Create the namespace once:
   `npx ovsx create-namespace bdarwin -p <token>`.
5. Run `gh secret set OVSX_PAT -R bdarwin/xml-grid-view`.

### JetBrains Marketplace (`JETBRAINS_PUBLISH_TOKEN`)

The **first upload must be done by hand**. The API can only publish updates to
a plugin that already exists.

1. Sign in at <https://plugins.jetbrains.com> and accept the developer
   agreement.
2. Download `xml-grid-view-intellij-X.Y.Z-signed.zip` from the
   [latest GitHub release](https://github.com/bdarwin/xml-grid-view/releases/latest).
3. Upload it with **Upload plugin**.
   - License: MIT.
   - Source code URL: `https://github.com/bdarwin/xml-grid-view`.
   - JetBrains reviews new plugins, which usually takes 1–2 business days.
4. Create a token under **Profile → My Tokens**.
5. Run `gh secret set JETBRAINS_PUBLISH_TOKEN -R bdarwin/xml-grid-view`.
   Later tags publish updates automatically.

## Screenshots

The marketplace screenshots live in `docs/`. The VS Code README links to them,
and they also go in the JetBrains plugin page's media section.

- `vscode-*.png`: a clean VS Code window, driven over Chrome DevTools Protocol
  (`--remote-debugging-port`).
- `intellij-*.png`: regenerate with `cd packages/intellij && ./gradlew
  runIdeForScreenshots`. This starts a sandbox IDE, and a startup script paints
  the Grid and Flat tabs into `build/screenshots/`.

## Identifiers

| | |
| --- | --- |
| VS Code extension ID | `bdarwin.xml-grid-view` |
| Open VSX | `bdarwin/xml-grid-view` |
| JetBrains plugin ID | `io.github.bdarwin.xmlgridview` |
