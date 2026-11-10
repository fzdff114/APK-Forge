# GitHub remote build setup

APK Forge uses GitHub Actions as its remote build backend. The phone does not need Google Play services or a local Android SDK.

## Privacy requirement

The selected build repository must be **Private**. APK Forge checks repository visibility using the GitHub API and refuses to upload source code to Public repositories. The existing fzdff114/APK-Forge repository is Public, so do not select it as the build target for private source code. Create a separate Private repository and copy the workflow file into its default branch.

Each build uses a temporary branch created from the repository's default branch. The project ZIP is uploaded only to that temporary branch. After the run has completed and the APK artifact has been retrieved, APK Forge deletes the temporary branch. If a run cannot be located or times out, the branch is deliberately retained for troubleshooting.

## One-time setup

1. Add .github/workflows/android-build.yml from this repository to the default branch of your Private build repository.
2. Enable GitHub Actions for that repository.
3. Create a fine-grained GitHub Personal Access Token restricted to that repository:
   - Contents: Read and write
   - Actions: Read and write
4. Enter the repository owner, repository name, and token in APK Forge. The token is encrypted using an AES key stored in Android Keystore.

## Build flow

When the user explicitly taps Remote Build, APK Forge:
1. Exports the selected project while excluding .git, .gradle, build outputs, IDE metadata, and local.properties.
2. Limits the compressed upload to 12 MiB.
3. Checks that the selected repository is Private.
4. Creates a unique temporary branch, uploads the ZIP to that branch, and dispatches android-build.yml.
5. Tracks the workflow, downloads the APK artifact, then deletes the temporary branch.

The source archive is sent to the configured GitHub repository and processed by GitHub Actions. Use only a Private repository you control. GitHub Actions usage and artifact retention remain subject to your GitHub account and repository limits.
