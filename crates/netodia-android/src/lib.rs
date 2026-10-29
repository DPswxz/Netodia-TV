//! Android TV bridge: reuse the desktop protocol client without desktop OS/UI dependencies.
use jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::jstring,
};
use netodia_core::entities::cloud::{Quality, SearchKind};
use netodia_net::netease::{Client, QrStatus};
use serde_json::{Value, json};
use std::sync::OnceLock;

#[path = "../../netodia-app/src/library/lyrics/romanize/mod.rs"]
mod romanize;

fn romanized_sheet(input: &str) -> String {
    let pattern=regex::Regex::new(r"(?m)^((?:\[\d+:\d+(?:\.\d+)?\])+)(.*)$").unwrap();
    let entries:Vec<_>=pattern.captures_iter(input).collect();
    let mut lines:Vec<_>=entries.iter().map(|capture|netodia_core::entities::lyrics::LyricLine{
        at_ms:None,end_ms:None,text:capture[2].trim().to_owned(),romanization:None,translation:None,
    }).collect();
    romanize::apply(&mut lines);
    entries.iter().zip(lines).filter_map(|(capture,line)|line.romanization.map(|text|format!("{}{text}",&capture[1]))).collect::<Vec<_>>().join("\n")
}

struct Backend {
    runtime: tokio::runtime::Runtime,
    client: Client,
    http: reqwest::Client,
    lyric_pacer: netodia_net::services::net::pacer::RequestPacer,
}
static BACKEND: OnceLock<Backend> = OnceLock::new();

fn dispatch(op: &str, input: &str) -> Result<Value, Box<dyn std::error::Error>> {
    let args: Value = serde_json::from_str(input)?;
    if op == "themes" {
        return Ok(json!(netodia_core::themes::registry().iter().map(|theme| {
            json!({"id":theme.id,"name":theme.name,"defaultVariant":theme.default_variant,"defaultAccent":theme.default_accent,
                "variants":theme.variants.iter().map(|v| {let p=v.palette; json!({"id":v.id,"name":v.name,"base":p.base,"mantle":p.mantle,"crust":p.crust,"surface0":p.surface0,"surface1":p.surface1,"text":p.text,"muted":p.subtext0,"border":p.border,"red":p.red,"green":p.green})}).collect::<Vec<_>>(),
                "accents":theme.accents.iter().map(|a| json!({"id":a.id,"name":a.name,"colors":a.per_variant.iter().map(|(id,color)|json!({"variant":id,"color":color})).collect::<Vec<_>>()})).collect::<Vec<_>>()})
        }).collect::<Vec<_>>()));
    }
    if BACKEND.get().is_none() {
        let runtime =
            tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build()?;
        let http = {
            let _guard = runtime.enter();
            let roots = rustls::RootCertStore { roots: webpki_roots::TLS_SERVER_ROOTS.to_vec() };
            let tls =
                rustls::ClientConfig::builder().with_root_certificates(roots).with_no_client_auth();
            reqwest::Client::builder().tls_backend_preconfigured(tls).build()?
        };
        let _ = BACKEND.set(Backend {
            runtime,
            http:http.clone(),
            lyric_pacer:netodia_net::services::net::lyrics_directory::pacer(),
            client: match args["device"].as_str().filter(|s| !s.is_empty()) {
                Some(device) => Client::with_device_id(http, device)?,
                None => Client::new(http),
            },
        });
    }
    let backend = BACKEND.get().ok_or("backend unavailable")?;
    let c = &backend.client;
    backend.runtime.block_on(async {
        let id = args["id"].as_i64().unwrap_or(0);
        let offset = args["offset"].as_u64().unwrap_or(0);
        Ok(match op {
            "restore" => {
                c.restore_cookie(args["cookie"].as_str().unwrap_or("")).await;
                json!({"device": c.device_id()})
            }
            "account" => serde_json::to_value(c.account().await?)?,
            "discover" => serde_json::to_value(c.discover().await?)?,
            "charts" => serde_json::to_value(c.charts().await?)?,
            "mine" => serde_json::to_value(c.user_playlists(id, 0).await?)?,
            "library" => {
                let (items, more) = if args["created"].as_bool().unwrap_or(false) {
                    c.created_playlists_page(id, offset).await?
                } else { c.user_playlists_page(id, offset).await? };
                json!({"items":items,"more":more})
            }
            "recommendations" => serde_json::to_value(c.recommendations().await?)?,
            "fm" => serde_json::to_value(c.fm().await?)?,
            "heart" => {
                let account = c.account().await?;
                let playlist = c.liked_playlist_id(account.id).await?;
                let liked = c.liked_ids(account.id).await?;
                let seed = if liked.contains(&id) { Some(id) } else { liked.first().copied() };
                match seed {
                    Some(seed) => serde_json::to_value(c.intelligence(seed, playlist, if id > 0 { id } else { seed }).await?)?,
                    None => json!([]),
                }
            },
            "similar" => serde_json::to_value(c.similar_songs(id).await?)?,
            "liked" => serde_json::to_value(c.liked_songs(id).await?.into_iter().map(|entry| entry.song).collect::<Vec<_>>())?,
            "recent" => serde_json::to_value(c.recent_songs().await?.into_iter().map(|entry| entry.song).collect::<Vec<_>>())?,
            "albums" => { let (items, more) = c.saved_albums_page(offset).await?; json!({"items":items,"more":more}) },
            "artists" => serde_json::to_value(c.saved_artists(offset).await?)?,
            "album" => { let (album, songs) = c.album(id).await?; json!({"entity":album,"songs":songs}) },
            "artist" => { let (artist, songs) = c.artist(id).await?; json!({"entity":artist,"songs":songs}) },
            "like" => { c.like(id, args["value"].as_bool().unwrap_or(true)).await?; json!(true) },
            "subscribe" => {
                let value = args["value"].as_bool().unwrap_or(true);
                match args["kind"].as_str().unwrap_or("playlist") {
                    "album" => c.subscribe_album(id, value).await?,
                    "artist" => c.subscribe_artist(id, value).await?,
                    _ => c.subscribe_playlist(id, value).await?,
                };
                json!(true)
            }
            "daily" => serde_json::to_value(c.daily().await?)?,
            "playlist" => {
                let (p, songs) = c.playlist(id).await?;
                json!({"playlist":p,"songs":songs})
            }
            "search_all" => {
                let query=args["query"].as_str().unwrap_or("");
                let (songs, albums, artists)=tokio::try_join!(
                    c.search(query, SearchKind::Songs, offset),
                    c.search(query, SearchKind::Albums, 0),
                    c.search(query, SearchKind::Artists, 0)
                )?;
                json!({"songs":songs.songs,"songCount":songs.song_count,"albums":albums.albums,"artists":artists.artists})
            }
            "search" => serde_json::to_value(
                c.search(args["query"].as_str().unwrap_or(""), match args["kind"].as_str().unwrap_or("songs") {
                    "albums" => SearchKind::Albums, "artists" => SearchKind::Artists,
                    "playlists" => SearchKind::Playlists, _ => SearchKind::Songs,
                }, offset).await?,
            )?,
            "stream" => {
                let s = c.stream(id, Quality::from_level(args["quality"].as_str().unwrap_or("exhigh"))).await?;
                json!({"url":s.url,"trial":s.trial})
            }
            "lyrics" => {
                let l = c.lyrics(id).await?;
                let roman=if l.romanization.trim().is_empty(){romanized_sheet(&l.original)}else{l.romanization};
                json!({"original":l.original,"translation":l.translation,"romanization":roman})
            }
            "romanize_sheet" => json!({"romanization":romanized_sheet(args["original"].as_str().unwrap_or(""))}),
            "lyrics_lookup" => {
                let credit=netodia_core::entities::artist::ArtistCredit::from_name(args["artist"].as_str().unwrap_or(""));
                let answer=netodia_net::services::net::lyrics_directory::fetch(&backend.http,&backend.lyric_pacer,
                    args["title"].as_str().unwrap_or(""),&credit,args["album"].as_str().unwrap_or(""),args["duration"].as_i64().unwrap_or(0)).await?;
                let original=answer.as_ref().and_then(|a|a.text()).unwrap_or("");
                json!({"original":original,"romanization":romanized_sheet(original),"instrumental":answer.as_ref().is_some_and(|a|a.instrumental)})
            }
            "qr" => {
                let key = c.qr_key().await?;
                let url = format!("https://music.163.com/login?codekey={key}");
                let qr = qrcode::QrCode::new(url.as_bytes())?;
                let rows: Vec<String> = (0..qr.width())
                    .map(|y| {
                        (0..qr.width())
                            .map(|x| if qr[(x, y)] == qrcode::Color::Dark { '1' } else { '0' })
                            .collect()
                    })
                    .collect();
                json!({"key":key,"rows":rows})
            }
            "qr_check" => {
                let status = match c.qr_check(args["key"].as_str().unwrap_or("")).await? {
                    QrStatus::Expired => "expired",
                    QrStatus::Waiting => "waiting",
                    QrStatus::Confirming => "confirming",
                    QrStatus::Authorized => "authorized",
                };
                json!({"status":status})
            }
            "session" => json!({"cookie":c.cookie_for_storage().await,"device":c.device_id()}),
            "logout" => {
                c.clear_session().await;
                json!(true)
            }
            _ => return Err("unknown operation".into()),
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_netodia_tv_Native_call(
    mut env: JNIEnv,
    _: JClass,
    op: JString,
    input: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let op: String = env.get_string(&op)?.into();
        let input: String = env.get_string(&input)?.into();
        Ok::<_, Box<dyn std::error::Error>>(dispatch(&op, &input)?)
    }));
    let response = match result {
        Ok(Ok(data)) => json!({"ok":true,"data":data}),
        Ok(Err(e)) => json!({"ok":false,"error":e.to_string()}),
        Err(_) => json!({"ok":false,"error":"播放器内部错误，请重试"}),
    };
    env.new_string(response.to_string()).map_or(std::ptr::null_mut(), |s| s.into_raw())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn desktop_theme_contract() -> Result<(), Box<dyn std::error::Error>> {
        let registry=dispatch("themes", "{}")?;
        let windows=registry.as_array().ok_or("theme registry missing")?.iter().find(|theme|theme["id"]=="windows-fluent").ok_or("Windows theme missing")?;
        let dark=windows["variants"].as_array().ok_or("variants missing")?.iter().find(|v|v["id"]=="dark").ok_or("dark variant missing")?;
        assert_eq!(dark["base"],0x202020);
        assert_eq!(dark["surface0"],0x2d2d2d);
        assert_eq!(windows["defaultAccent"],"blue");
        Ok(())
    }

    #[test]
    #[ignore = "requires live NetEase connectivity"]
    fn live_search_pagination() -> Result<(), Box<dyn std::error::Error>> {
        let first = dispatch("search_all", r#"{"query":"love","offset":0}"#)?;
        let next = dispatch("search_all", r#"{"query":"love","offset":30}"#)?;
        let a = first["songs"].as_array().ok_or("missing first page")?;
        let b = next["songs"].as_array().ok_or("missing following page")?;
        assert_eq!(a.len(), 30, "TV cursor advances by the API's 30-item batch");
        assert_eq!(b.len(), 30);
        assert!(first["songCount"].as_u64().unwrap_or(0) > 30);
        let ids: std::collections::HashSet<i64> = a.iter().filter_map(|s| s["id"].as_i64()).collect();
        assert!(b.iter().filter_map(|s| s["id"].as_i64()).any(|id| !ids.contains(&id)), "offset must advance to new songs");
        println!("pagination contract: {} + {} songs with advancing IDs", a.len(), b.len());
        Ok(())
    }

    /// Opt-in live contract check. Never logs cookies, QR keys, or signed media URLs.
    #[test]
    #[ignore = "requires live NetEase connectivity"]
    fn live_tv_music_flow() -> Result<(), Box<dyn std::error::Error>> {
        dispatch("restore", r#"{"cookie":""}"#)?;
        let discover = dispatch("discover", "{}")?;
        println!("discovery playlists: {}", discover.as_array().ok_or("missing discovery")?.len());
        let search = dispatch("search", r#"{"query":"海阔天空"}"#)?;
        assert!(!search["songs"].as_array().ok_or("missing search results")?.is_empty());
        println!("search results: {}", search["songs"].as_array().unwrap().len());
        let combined=dispatch("search_all",r#"{"query":"海阔天空"}"#)?;
        assert!(combined["songs"].as_array().is_some_and(|items|!items.is_empty()));
        assert!(combined["albums"].is_array());
        assert!(combined["artists"].is_array());
        println!("combined desktop search contract: passed");
        let charts = dispatch("charts", "{}")?;
        let charts = charts.as_array().ok_or("charts is not an array")?;
        assert!(!charts.is_empty());
        println!("charts: {}", charts.len());
        let id = charts[0]["id"].as_i64().ok_or("missing chart id")?;
        let playlist = dispatch("playlist", &json!({"id":id}).to_string())?;
        let songs = playlist["songs"].as_array().ok_or("missing songs")?;
        assert!(!songs.is_empty());
        println!("playlist songs: {}", songs.len());
        let mut playable = None;
        for song in songs.iter().take(10) {
            let id = song["id"].as_i64().ok_or("missing song id")?;
            if let Ok(ticket) = dispatch("stream", &json!({"id":id}).to_string()) {
                let url = ticket["url"].as_str().ok_or("missing stream URL")?;
                assert!(url.starts_with("https://") || url.starts_with("http://"));
                let backend = BACKEND.get().unwrap();
                backend.runtime.block_on(async {
                    let mut response = reqwest::Client::new()
                        .get(url)
                        .header("Range", "bytes=0-8191")
                        .timeout(std::time::Duration::from_secs(10))
                        .send()
                        .await?
                        .error_for_status()?;
                    let mut audio = Vec::new();
                    while audio.len() < 4096 {
                        let Some(chunk) = response.chunk().await? else { break };
                        audio.extend_from_slice(&chunk);
                    }
                    assert!(audio.len() >= 1024, "audio sample is unexpectedly small");
                    assert!(
                        audio.starts_with(b"ID3")
                            || audio.starts_with(b"fLaC")
                            || audio.windows(2).any(|w| w[0] == 0xff && w[1] & 0xe0 == 0xe0),
                        "not a recognized audio stream"
                    );
                    println!("audio CDN returned {} bytes, audio signature verified", audio.len());
                    Ok::<_, Box<dyn std::error::Error>>(())
                })?;

                playable = Some(id);
                println!("playable song: {id}, trial: {}", ticket["trial"]);
                break;
            }
        }
        let id = playable.ok_or("no playable song in sample")?;
        let lyrics = dispatch("lyrics", &json!({"id":id}).to_string())?;
        assert!(lyrics["original"].is_string());
        println!("lyrics bytes: {}", lyrics["original"].as_str().unwrap_or("").len());
        let qr = dispatch("qr", "{}")?;
        let rows = qr["rows"].as_array().ok_or("missing QR matrix")?;
        assert!(rows.len() > 20);
        assert!(rows.iter().all(|row| row.as_str().is_some_and(|r| r.len() == rows.len())));
        println!("QR matrix: {} x {}", rows.len(), rows.len());
        Ok(())
    }
}
