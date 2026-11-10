# GitHub remote build setup

APK Forge uses a GitHub Actions workflow as its first remote build backend. The phone does not need Google Play services or a local Android SDK.

## One-time setup

1. Keep the android-build.yml workflow in the default branch of the selected repository.
2. Enable GitHub Actions for that repository.
3. Create a fine-grained GitHub Personal Access Token limited to that repository:
   - Contents: Read and write
   - Actions: Read and write
4. Enter the repository owner, repository name, and token in APK Forge. The token is encrypted using an AES key stored in Android Keystore.

## Build flow

When the user explicitly taps Remote Build, APK Forge:
1. Exports the selected project while excluding .git, .gradle, build outputs, IDE metadata, and local.properties.
2. Limits the compressed upload to 12 MiB.
3. Uploads a temporary ZIP to .apkforge/uploads/ in the selected repository.
4. Dispatches android-build.yml and tracks the resulting run.
5. Downloads the generated APK artifact and removes the temporary source ZIP from the repository.

The source archive is sent to the configured GitHub repository and processed by GitHub Actions. Do not build sensitive projects with a repository or token you do not control.
