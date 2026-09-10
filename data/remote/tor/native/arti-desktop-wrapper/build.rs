//! Refuses to build onto a filesystem that this build is known to corrupt.
//!
//! `build-desktop.sh` already builds out of tree, but it is not the only way this crate gets
//! built: running `cargo build` from the crate directory bypasses it entirely, and doing so on an
//! ntfs3 volume has twice left `target/release/deps` unreadable ("rm: traversal failed: Invalid
//! argument"), taking the rest of the tree's reliability with it. The guard belongs where the
//! compiler is, not only in the wrapper script.
//!
//! Set BITCHAT_ALLOW_ANY_FS=1 to override, e.g. on a platform where /proc/mounts is absent or the
//! detection is wrong.

use std::path::Path;

const CORRUPTING: &[&str] = &["ntfs", "ntfs3", "exfat", "vfat", "fuseblk"];

fn main() {
    println!("cargo:rerun-if-env-changed=BITCHAT_ALLOW_ANY_FS");

    if std::env::var("BITCHAT_ALLOW_ANY_FS").as_deref() == Ok("1") {
        return;
    }

    let out_dir = match std::env::var("OUT_DIR") {
        Ok(dir) => dir,
        Err(_) => return,
    };

    if let Some(fs_type) = filesystem_for(Path::new(&out_dir)) {
        if CORRUPTING.contains(&fs_type.as_str()) {
            panic!(
                "\n\n\
                 Refusing to build: the target directory is on {fs_type}.\n\
                 \n  {out_dir}\n\n\
                 Building Arti here has corrupted this filesystem before -- directories become\n\
                 unreadable with \"Invalid argument\" and cannot be deleted without a repair.\n\n\
                 Use data/remote/tor/native/build-desktop.sh, which builds out of tree, or set\n\
                 CARGO_TARGET_DIR to a path on ext4/btrfs/xfs.\n\
                 Override with BITCHAT_ALLOW_ANY_FS=1 if this detection is wrong.\n"
            );
        }
    }
}

/// Longest-prefix match of the path against /proc/mounts. Returns None where that is unavailable,
/// which is every non-Linux host -- the guard simply does not apply there.
fn filesystem_for(path: &Path) -> Option<String> {
    let mounts = std::fs::read_to_string("/proc/mounts").ok()?;
    let canonical = path.canonicalize().unwrap_or_else(|_| path.to_path_buf());

    let mut best: Option<(usize, String)> = None;
    for line in mounts.lines() {
        let mut fields = line.split_whitespace();
        let _device = fields.next()?;
        let mount_point = fields.next()?;
        let fs_type = fields.next()?;

        // /proc/mounts escapes spaces as \040; paths containing them are rare enough to skip.
        if mount_point.contains('\\') {
            continue;
        }
        if canonical.starts_with(mount_point) {
            let len = mount_point.len();
            if best.as_ref().map_or(true, |(best_len, _)| len > *best_len) {
                best = Some((len, fs_type.to_string()));
            }
        }
    }
    best.map(|(_, fs_type)| fs_type)
}
