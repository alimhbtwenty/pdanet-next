# CI and packaging (GitHub Actions)

When this matters: building/testing the Android app, a JVM core, and a Windows app/installer.

- Android without a committed wrapper: `gradle/actions/setup-gradle` with `gradle-version`, then
  `gradle -p android :app:assembleRelease`. Runners have the SDK; signing a sideload build with the
  debug key: `signingConfig = signingConfigs.getByName("debug")`. A missing `proguard-rules.pro` breaks
  minified release builds.
- A plain Kotlin/JVM module can be shared: `include(":core"); project(":core").projectDir = file("../core")`,
  and kept buildable on its own with its own `settings.gradle.kts` (`gradle -p core test`).
- Windows .NET Framework 4.8 apps build with the .NET SDK on any OS (reference assemblies come from
  NuGet); set `BaseIntermediateOutputPath` in `Directory.Build.props`, not the csproj.
- Downloads: `https://dl.google.com/android/repository/platform-tools-latest-windows.zip` (versioned
  names 404), `https://www.wintun.net/builds/wintun-0.14.1.zip` (`wintun/bin/amd64/wintun.dll`).
- Run JVM servers on the Windows runner for integration tests: `Start-Process relay/bin/core.bat`,
  wait with `Test-NetConnection -Port`.
- Linux e2e needing root: `sudo env "PATH=$PATH" "JAVA_HOME=$JAVA_HOME" script.sh`.
- MSI with WiX 5: `dotnet tool install --global wix --version 5.0.2`, add `WixToolset.UI.wixext` and
  `WixToolset.Util.wixext` of the same version; `wix build x.wxs -arch x64 -ext ... -d Version=...`.
  WiX only works on Windows (on Linux it prints "undefined behavior" and fails on paths). Test the MSI
  in CI with `msiexec /i x.msi /qn /l*v log` and `/x`. Cleanup on uninstall via `WixQuietExec`
  (`BinaryRef="Wix4UtilCA_X64"`) with a `SetProperty` command line; condition
  `REMOVE ~= "ALL" AND NOT UPGRADINGPRODUCTCODE` for things an upgrade must keep.
- Only publish releases from `main`; build on every push and PR.
