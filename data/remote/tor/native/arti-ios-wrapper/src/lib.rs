// The Arti lifecycle ABI is one source shared with the Linux wrapper (arti-linux-wrapper): status
// callback, generation-tagged start/stop, SOCKS listener. Edit arti-lifecycle/lifecycle.rs.
// build-macos.sh builds this same crate for macOS and renames the archive to libarti_macos.a.
#[path = "../../arti-lifecycle/lifecycle.rs"]
mod lifecycle;
pub use lifecycle::*;
