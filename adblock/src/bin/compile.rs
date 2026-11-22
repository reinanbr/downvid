//! Host tool for scripts/build_adblock_lists.py.
//!
//!   dvadblock-compile engine OUT.dat MASK:LIST.txt...
//!       Compiles filter lists (MASK = scriptlet permission bits) into the
//!       serialized engine loaded by the app.
//!   dvadblock-compile resources OUT.json WAR_DIR REDIRECTS.js EXTRA.json...
//!       uBlock Origin's redirect resources (web_accessible_resources +
//!       redirect-resources.js) followed by the EXTRA resource arrays
//!       (Brave's resources.json, uBO's converted scriptlets).

use adblock::resources::resource_assembler::assemble_web_accessible_resources;
use adblock::resources::Resource;
use std::path::Path;
use std::time::Instant;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.first().map(String::as_str) {
        Some("engine") if args.len() >= 3 => engine(&args[1], &args[2..]),
        Some("resources") if args.len() >= 4 => resources(&args[1], &args[2], &args[3], &args[4..]),
        _ => {
            eprintln!("usage: dvadblock-compile engine OUT.dat MASK:LIST...\n       dvadblock-compile resources OUT.json WAR_DIR REDIRECTS.js EXTRA.json...");
            std::process::exit(2);
        }
    }
}

fn engine(out: &str, lists: &[String]) {
    let texts: Vec<(String, u8)> = lists
        .iter()
        .map(|a| {
            let (mask, p) = a.split_once(':').expect("MASK:PATH");
            let text = std::fs::read_to_string(p).unwrap_or_else(|e| panic!("{p}: {e}"));
            (text, mask.parse().expect("mask"))
        })
        .collect();
    let rules: usize = texts
        .iter()
        .map(|(t, _)| t.lines().filter(|l| !l.is_empty() && !l.starts_with('!')).count())
        .sum();
    let t = Instant::now();
    let dat = dvadblock::compile(texts);
    std::fs::write(out, &dat).unwrap();
    let t2 = Instant::now();
    dvadblock::Blocker::load(&dat, None).expect("reload");
    eprintln!(
        "{} lists, {rules} rules -> {} bytes (compile {:?}, load {:?})",
        lists.len(),
        dat.len(),
        t2 - t,
        t2.elapsed()
    );
}

fn resources(out: &str, war: &str, redirects: &str, extra: &[String]) {
    let mut all: Vec<Resource> = assemble_web_accessible_resources(Path::new(war), Path::new(redirects))
        .into_iter()
        // As Brave does: it breaks Google IMA integrations.
        .filter(|r| r.name != "google-ima-dai.js")
        .collect();
    let redirect_count = all.len();
    for p in extra {
        let bytes = std::fs::read(p).unwrap_or_else(|e| panic!("{p}: {e}"));
        let more: Vec<Resource> = serde_json::from_slice(&bytes).unwrap_or_else(|e| panic!("{p}: {e}"));
        all.extend(more);
    }
    let json = serde_json::to_vec(&all).unwrap();
    std::fs::write(out, &json).unwrap();
    // The app loads it the same way.
    dvadblock::Blocker::load(&dvadblock::compile(Vec::new()), Some(&json)).expect("resources load");
    eprintln!("{} resources ({redirect_count} redirects) -> {} bytes", all.len(), json.len());
}
