// The Arti lifecycle ABI is one source shared with the Apple wrapper (arti-ios-wrapper): status
// callback, generation-tagged start/stop, SOCKS listener. Edit arti-lifecycle/lifecycle.rs.
#[path = "../../arti-lifecycle/lifecycle.rs"]
mod lifecycle;
pub use lifecycle::*;
