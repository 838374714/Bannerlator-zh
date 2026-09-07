//! Chunk plan — the part of `EpicDownloadManager.install` between "manifest parsed" and "submit
//! to the pool", ported 1:1: install-tag file selection (`resolveInstallFiles` /
//! `getRequiredInstallFiles`), the unique-chunk list for a file set (`uniqueChunksForFiles`),
//! the byte total (`Σ max(fileSize, 1)`), chunk cache paths and CDN URLs.
//!
//! At runtime the Java manager passes the INDICES of its `pendingFiles` (post delta/verify), so
//! the file selection functions here are exercised by the unit tests (and available to a future
//! caller) while the driver only runs `unique_chunks_for_files` on Java's pending set — the same
//! function Java runs, on the same manifest, in the same order.

use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};

use super::manifest::{ChunkInfo, Manifest};

/// `getRequiredInstallFiles`: files with no install tags; if NONE carries a tag, every file.
pub fn required_install_files(m: &Manifest) -> Vec<usize> {
    let required: Vec<usize> = m
        .files
        .iter()
        .enumerate()
        .filter(|(_, f)| f.install_tags.is_empty())
        .map(|(i, _)| i)
        .collect();
    if required.is_empty() {
        (0..m.files.len()).collect()
    } else {
        required
    }
}

/// `resolveInstallFiles(manifest, selectedTags)`:
/// - `None` → every file (legacy / DLC / overlay path);
/// - `Some([])` → required(base) files only;
/// - otherwise → base files + any file carrying at least one selected tag, falling back to
///   required-only when nothing matched.
pub fn resolve_install_files(m: &Manifest, selected_tags: Option<&[String]>) -> Vec<usize> {
    let Some(tags) = selected_tags else {
        return (0..m.files.len()).collect();
    };
    if tags.is_empty() {
        return required_install_files(m);
    }
    let want: HashSet<&str> = tags.iter().map(String::as_str).collect();
    let mut out = Vec::new();
    for (i, f) in m.files.iter().enumerate() {
        if f.install_tags.is_empty() {
            out.push(i);
            continue;
        }
        if f.install_tags.iter().any(|t| want.contains(t.as_str())) {
            out.push(i);
        }
    }
    if out.is_empty() {
        required_install_files(m)
    } else {
        out
    }
}

/// `uniqueChunksForFiles(manifest, files)`: chunk indices (into `unique_chunks`) in first-seen
/// order over `files[*].parts`, each GUID once, GUIDs absent from the chunk list dropped.
pub fn unique_chunks_for_files(m: &Manifest, file_indices: &[usize]) -> Vec<usize> {
    let by_guid: HashMap<String, usize> = m.chunk_index_by_guid();
    let mut seen: HashSet<String> = HashSet::new();
    let mut chunks = Vec::new();
    for &fi in file_indices {
        let Some(f) = m.files.get(fi) else {
            continue;
        };
        for p in &f.parts {
            let g = p.guid_str();
            if seen.insert(g.clone()) {
                if let Some(&ci) = by_guid.get(&g) {
                    chunks.push(ci);
                }
            }
        }
    }
    chunks
}

/// `totalBytes += Math.max(chunk.fileSize, 1)` over the needed chunks.
pub fn total_credit_bytes(m: &Manifest, chunk_indices: &[usize]) -> u64 {
    chunk_indices
        .iter()
        .filter_map(|&i| m.unique_chunks.get(i))
        .map(ChunkInfo::credit_bytes)
        .sum()
}

/// `<installDir>/.chunks`.
pub fn chunk_cache_dir(install_dir: &str) -> PathBuf {
    Path::new(install_dir).join(super::CHUNK_CACHE_DIR)
}

/// Final cache file for a chunk: `new File(chunkCacheDir, chunk.guidStr())`.
pub fn cached_chunk_path(cache_dir: &Path, chunk: &ChunkInfo) -> PathBuf {
    cache_dir.join(chunk.guid_str())
}

/// The Java pool's skip rule: a chunk whose cache file EXISTS (any size) is not fetched.
pub fn is_chunk_cached(cache_dir: &Path, chunk: &ChunkInfo) -> bool {
    cached_chunk_path(cache_dir, chunk).exists()
}

/// One CDN entry as the Java manager passes it: `cdn.baseUrl + cdn.cloudDir` (auth params are
/// never used on chunk URLs). Chunk URL = `prefix + "/" + chunk.getPath(chunkDir)`.
pub fn chunk_url(cdn_prefix: &str, chunk_dir: &str, chunk: &ChunkInfo) -> String {
    format!("{}/{}", cdn_prefix, chunk.path(chunk_dir))
}

/// Distinct CDN prefixes in first-seen order (the fetch core wants one host key per distinct
/// endpoint; Java would simply try an identical URL twice, which changes nothing).
pub fn distinct_prefixes(cdn_prefixes: &[String]) -> Vec<String> {
    let mut seen = HashSet::new();
    let mut out = Vec::new();
    for p in cdn_prefixes {
        if seen.insert(p.as_str()) {
            out.push(p.clone());
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::super::manifest::test_support::*;
    use super::super::manifest::parse_manifest;
    use super::*;

    fn manifest() -> Manifest {
        parse_manifest(&build_manifest(&sample_chunks(), &sample_files(), 21, false)).unwrap()
    }

    #[test]
    fn install_tag_rules_match_java() {
        let m = manifest();
        // null → all
        assert_eq!(resolve_install_files(&m, None), vec![0, 1, 2]);
        // empty → required only (file 0 has no tags)
        assert_eq!(resolve_install_files(&m, Some(&[])), vec![0]);
        // German → base + the German file
        let de = vec!["German".to_string(), "de-DE".to_string()];
        assert_eq!(resolve_install_files(&m, Some(&de)), vec![0, 1]);
        // unknown tag → nothing extra matched, but base files matched, so base only
        let xx = vec!["Klingon".to_string()];
        assert_eq!(resolve_install_files(&m, Some(&xx)), vec![0]);
    }

    #[test]
    fn manifest_without_tags_treats_every_file_as_required() {
        let mut files = sample_files();
        for f in files.iter_mut() {
            f.tags.clear();
        }
        let m = parse_manifest(&build_manifest(&sample_chunks(), &files, 21, false)).unwrap();
        assert_eq!(required_install_files(&m), vec![0, 1, 2]);
        assert_eq!(resolve_install_files(&m, Some(&[])), vec![0, 1, 2]);
    }

    #[test]
    fn manifest_where_every_file_is_tagged_falls_back_to_all() {
        let mut files = sample_files();
        files[0].tags = vec!["English".to_string()];
        let m = parse_manifest(&build_manifest(&sample_chunks(), &files, 21, false)).unwrap();
        // No untagged file, unknown tag selected → matched nothing → required → (none) → all.
        let xx = vec!["Klingon".to_string()];
        assert_eq!(resolve_install_files(&m, Some(&xx)), vec![0, 1, 2]);
    }

    #[test]
    fn unique_chunks_follow_first_seen_part_order() {
        let m = manifest();
        // file 2 first: chunk c[2] then c[0]; then file 0: c[0] (dup), c[1].
        assert_eq!(unique_chunks_for_files(&m, &[2, 0]), vec![2, 0, 1]);
        assert_eq!(unique_chunks_for_files(&m, &[1]), vec![1]);
        assert!(unique_chunks_for_files(&m, &[]).is_empty());
        assert!(unique_chunks_for_files(&m, &[99]).is_empty(), "out-of-range index ignored");
        // total = 700000 + max(0,1) + 9
        assert_eq!(total_credit_bytes(&m, &[0, 1, 2]), 700_000 + 1 + 9);
    }

    #[test]
    fn parts_referencing_unknown_guids_are_dropped() {
        let mut files = sample_files();
        files[0]
            .parts
            .push(([0x1234, 0x5678, 0x9ABC, 0xDEF0], 0, 1));
        let m = parse_manifest(&build_manifest(&sample_chunks(), &files, 21, false)).unwrap();
        assert_eq!(unique_chunks_for_files(&m, &[0]), vec![0, 1]);
    }

    #[test]
    fn urls_and_cache_paths_match_java() {
        let m = manifest();
        let c = &m.unique_chunks[0];
        assert_eq!(
            chunk_url("https://fastly-download.epicgames.com/Builds/Org/o-x/y/default", "ChunksV4", c),
            "https://fastly-download.epicgames.com/Builds/Org/o-x/y/default/ChunksV4/05/0123456789ABCDEF_00000001000000020000000300000004.chunk"
        );
        let cache = chunk_cache_dir("/data/x/imagefs/epic_games/Game");
        assert_eq!(cache, PathBuf::from("/data/x/imagefs/epic_games/Game/.chunks"));
        assert_eq!(
            cached_chunk_path(&cache, c),
            PathBuf::from("/data/x/imagefs/epic_games/Game/.chunks/00000001000000020000000300000004")
        );
        let prefixes = vec!["a".to_string(), "b".to_string(), "a".to_string()];
        assert_eq!(distinct_prefixes(&prefixes), vec!["a".to_string(), "b".to_string()]);
    }
}
