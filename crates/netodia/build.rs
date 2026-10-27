fn main() {
    #[cfg(target_os = "windows")]
    embed_windows_resources();
}

/// The repo-root icon, reached from this package rather than copied into it, so the MSI's
/// `ProductICO` and the embedded resource cannot drift apart. `wix/main.wxs` spells the same
/// file through its own `$(var.RepoRoot)`.
///
/// Relative to the package root, which is where cargo puts a build script's working directory.
#[cfg(target_os = "windows")]
const ICON: &str = "../../assets/netodia.ico";

/// Embed the application manifest and [`ICON`] as the EXE's primary `ICON` resource.
/// Windows' shell pulls the icon for the titlebar's top-left glyph, the taskbar button,
/// the Alt-Tab thumbnail badge, and the Explorer file icon. Without an embedded resource
/// the running window falls back to a
/// generic placeholder even when the Start-Menu shortcut has its own icon (`WiX`
/// `ProductICO`).
#[cfg(target_os = "windows")]
fn embed_windows_resources() {
    println!("cargo:rerun-if-changed={ICON}");
    println!("cargo:rerun-if-changed=netodia.manifest");
    let mut res = winresource::WindowsResource::new();
    res.set_icon(ICON);
    // GetWindowSubclass is exported by name only in Common Controls v6. Without this
    // manifest the loader fails before main(), including for --version and --logs.
    res.set_manifest_file("netodia.manifest");
    if let Err(e) = res.compile() {
        // `cargo::error=` is the build script's own failure channel — a clearer
        // report than unwinding out of `main` with a Debug-formatted error.
        println!("cargo::error=failed to embed Windows resources: {e}");
        std::process::exit(1);
    }
}
