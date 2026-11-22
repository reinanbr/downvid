//! DownVid's ad blocker: Brave's adblock-rust engine (network filters,
//! cosmetic filters, scriptlets, redirects) behind a small JNI API used by
//! the in-app browser (`com.downvid.downvid.browser.AdEngine`).
//!
//! The filter lists are compiled ahead of time (`dvadblock-compile`) into a
//! serialized engine shipped as an asset; loading it is a FlatBuffer copy.

use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::resources::Resource;
use adblock::Engine;
use std::collections::HashSet;
use std::sync::{Arc, Mutex};

/// Request types, as the Kotlin side encodes them.
pub const TYPES: [&str; 11] = [
    "other", "document", "subdocument", "stylesheet", "script", "image", "font", "media",
    "xmlhttprequest", "websocket", "ping",
];

pub enum Verdict {
    Allow,
    Block,
    /// Replace the response with this `data:` URL (e.g. an empty video).
    Redirect(String),
}

pub struct Blocker {
    engine: Engine,
    /// Generic-hiding state of recent hosts (for `hidden`).
    hosts: Mutex<Vec<(String, Arc<Generic>)>>,
}

/// What generic cosmetic rules may do on a host.
struct Generic {
    /// `$generichide`: no generic rules at all.
    off: bool,
    /// Class/id selectors excepted on the host (`#@#`).
    exceptions: HashSet<String>,
}

/// Serialized engine for the given filter lists (ABP / uBO syntax), each
/// with the scriptlet permission bits Brave's catalog grants it.
pub fn compile(lists: Vec<(String, u8)>) -> Vec<u8> {
    let mut set = FilterSet::new(false);
    for (text, permission) in lists {
        let opts = ParseOptions {
            permissions: adblock::resources::PermissionMask::from_bits(permission),
            ..Default::default()
        };
        set.add_filter_list(text, opts);
    }
    Engine::new_with_filter_set(set).serialize()
}

impl Blocker {
    /// `dat` from [`compile`]; `resources` is Brave's resources.json
    /// (scriptlets and redirect payloads).
    pub fn load(dat: &[u8], resources: Option<&[u8]>) -> Result<Self, String> {
        let mut engine = Engine::default();
        engine.deserialize(dat).map_err(|e| format!("engine: {e:?}"))?;
        if let Some(r) = resources {
            let res: Vec<Resource> = serde_json::from_slice(r).map_err(|e| format!("resources: {e}"))?;
            engine.use_resources(res);
        }
        Ok(Self { engine, hosts: Mutex::new(Vec::new()) })
    }

    pub fn check(&self, url: &str, source: &str, kind: &str) -> Verdict {
        let Ok(req) = Request::new(url, source, kind, "get") else {
            return Verdict::Allow;
        };
        let r = self.engine.check_network_request(&req);
        if !r.should_block() {
            return Verdict::Allow;
        }
        match r.redirect {
            Some(data) => Verdict::Redirect(data),
            None => Verdict::Block,
        }
    }

    /// What a page needs before it loads: CSS hiding its ad elements (one
    /// rule per selector, so an invalid selector only drops itself), the
    /// scriptlets to inject, and whether generic hiding is disabled.
    pub fn cosmetic(&self, url: &str) -> String {
        let r = self.engine.url_cosmetic_resources(url);
        let css = hide_css(r.hide_selectors.iter());
        self.remember(url, Generic { off: r.generichide, exceptions: r.exceptions });
        serde_json::json!({
            "css": css,
            "script": r.injected_script,
            "generichide": r.generichide,
        })
        .to_string()
    }

    /// CSS for the generic rules matching classes/ids seen on the page
    /// (`tokens`: ".class" / "#id" per line).
    pub fn hidden(&self, url: &str, tokens: &str) -> String {
        let (mut classes, mut ids) = (Vec::new(), Vec::new());
        for t in tokens.lines() {
            if let Some(c) = t.strip_prefix('.') {
                classes.push(c);
            } else if let Some(i) = t.strip_prefix('#') {
                ids.push(i);
            }
        }
        let host = host_of(url);
        let cached = self.hosts.lock().unwrap().iter().find(|(h, _)| *h == host).map(|(_, g)| Arc::clone(g));
        let g = cached.unwrap_or_else(|| {
            let r = self.engine.url_cosmetic_resources(url);
            self.remember(url, Generic { off: r.generichide, exceptions: r.exceptions })
        });
        if g.off {
            return String::new();
        }
        hide_css(self.engine.hidden_class_id_selectors(classes, ids, &g.exceptions).iter())
    }

    fn remember(&self, url: &str, g: Generic) -> Arc<Generic> {
        let host = host_of(url).to_string();
        let g = Arc::new(g);
        let mut m = self.hosts.lock().unwrap();
        m.retain(|(h, _)| *h != host);
        m.insert(0, (host, Arc::clone(&g)));
        m.truncate(16);
        g
    }
}

fn host_of(url: &str) -> &str {
    let rest = url.split_once("://").map_or(url, |(_, r)| r);
    rest.split(['/', '?', '#']).next().unwrap_or("")
}

fn hide_css<'a>(selectors: impl Iterator<Item = &'a String>) -> String {
    let mut css = String::new();
    for s in selectors {
        css.push_str(s);
        css.push_str("{display:none!important}\n");
    }
    css
}

mod jni_api;

#[cfg(test)]
mod tests {
    use super::*;

    fn blocker(rules: &str) -> Blocker {
        Blocker::load(&compile(vec![(rules.to_string(), 0)]), None).unwrap()
    }

    #[test]
    fn blocks_network_rules_and_honours_exceptions() {
        let b = blocker("||ads.example.com^\n||cdn.site.com/ads/*$script\n@@||ads.example.com/ok.js\n");
        assert!(matches!(b.check("https://ads.example.com/x.js", "https://news.com/", "script"), Verdict::Block));
        assert!(matches!(b.check("https://ads.example.com/ok.js", "https://news.com/", "script"), Verdict::Allow));
        assert!(matches!(b.check("https://cdn.site.com/ads/a.js", "https://news.com/", "script"), Verdict::Block));
        assert!(matches!(b.check("https://cdn.site.com/ads/a.png", "https://news.com/", "image"), Verdict::Allow));
        assert!(matches!(b.check("https://www.example.com/", "https://news.com/", "document"), Verdict::Allow));
    }

    #[test]
    fn cosmetic_rules_for_the_site_and_generic_classes() {
        let b = blocker("news.com##.sponsored\n##.ad-banner\n");
        let c = b.cosmetic("https://www.news.com/a");
        assert!(c.contains(".sponsored{display:none!important}"), "{c}");
        assert!(!b.cosmetic("https://other.org/").contains(".sponsored"));
        let h = b.hidden("https://www.news.com/a", ".ad-banner\n.content\n#main");
        assert_eq!(h, ".ad-banner{display:none!important}\n");
    }

    #[test]
    fn generic_hiding_respects_exceptions_and_generichide() {
        let b = blocker("##.ad-banner\nkeep.com#@#.ad-banner\n@@||nogeneric.com^$generichide\n");
        assert_eq!(b.hidden("https://keep.com/", ".ad-banner"), "");
        assert_eq!(b.hidden("https://nogeneric.com/", ".ad-banner"), "");
        assert!(b.cosmetic("https://nogeneric.com/").contains("\"generichide\":true"));
        assert_eq!(b.hidden("https://other.com/", ".ad-banner"), ".ad-banner{display:none!important}\n");
    }

    #[test]
    fn redirect_rules_serve_the_resource() {
        let res = r#"[{"name":"noopmp4-1s","aliases":["noopmp4"],"kind":{"mime":"video/mp4"},"content":"AAAA"}]"#;
        let b = Blocker::load(&compile(vec![("||ads.cdn.com^$media,redirect=noopmp4-1s\n".into(), 0)]), Some(res.as_bytes())).unwrap();
        match b.check("https://ads.cdn.com/preroll.mp4", "https://video.site/", "media") {
            Verdict::Redirect(d) => assert!(d.starts_with("data:video/mp4;base64,"), "{d}"),
            _ => panic!("expected a redirect"),
        }
    }

    /// The engine shipped in the APK (scripts/build_adblock_lists.py).
    #[test]
    fn bundled_engine_blocks_known_ads() {
        let dir = concat!(env!("CARGO_MANIFEST_DIR"), "/../android/app/src/main/assets/adblock/");
        let Ok(dat) = std::fs::read(format!("{dir}engine.dat")) else { return };
        let res = std::fs::read(format!("{dir}resources.json")).ok();
        let t = std::time::Instant::now();
        let b = Blocker::load(&dat, res.as_deref()).unwrap();
        eprintln!("bundled engine loaded in {:?}", t.elapsed());
        let page = "https://www.example.org/article";
        assert!(matches!(b.check("https://securepubads.g.doubleclick.net/tag/js/gpt.js", page, "script"), Verdict::Block));
        assert!(matches!(b.check("https://www.google-analytics.com/analytics.js", page, "script"), Verdict::Block | Verdict::Redirect(_)));
        assert!(matches!(b.check("https://www.youtube.com/watch?v=x", "https://www.youtube.com/", "document"), Verdict::Allow));
        assert!(matches!(b.check("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", page, "xmlhttprequest"), Verdict::Allow));
        let t = std::time::Instant::now();
        for _ in 0..1000 {
            b.check("https://cdn.example.org/assets/app.4f2a.js", page, "script");
        }
        eprintln!("1000 checks in {:?}", t.elapsed());
    }
}
