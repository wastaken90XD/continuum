# Reddit post/image/video download methods — research catalog

Requested: research deeply, find at least 50 unique ways people download Reddit media today.
This catalog has 80+, grouped by category, each backed by a real, current source (no fabricated
tools). The last section ("What this means for Continuum") is the actionable part — the rest is
reference material.

## A. Python libraries / scripts

1. **PRAW** (Python Reddit API Wrapper) + `requests` — the standard building block; walk a
   submission's `.url`/`.media_metadata`/`.gallery_data`, then `requests.get()` each file.
2. **asyncpraw** — async PRAW, used when scraping many posts/subreddits concurrently.
3. **bdfr** (Bulk Downloader for Reddit, `pip install bdfr`) — the most complete general-purpose
   CLI tool: subreddits, multireddits, users, saved posts; downloads images, galleries, v.redd.it
   video (merges audio via yt-dlp as a dependency), Imgur, Gfycat/Redgifs, Erome.
4. **bdfrx** — actively maintained community continuation after BDFR's original maintainers slowed
   down (`pipx install bdfrx`).
5. **bulk-downloader-for-reddit_with-saved-hashes** — a BDFR fork adding persistent hash-based
   dedup across runs.
6. **redditdownloader** (jveitchmichaelis) — small PRAW scraper with SHA-256 dedup, handles Imgur
   galleries + direct i.redd.it links.
7. **reddit-downloader** (ajayhk) — ships *two* implementations side by side: one PRAW-based, one
   BeautifulSoup-scraping-based, useful as a reference for building either style.
8. **RedditImageDownloader** (NullSingularity3) — asyncpraw + MD5 dedup, Docker-ready, built for
   scraping training datasets.
9. **reddit-media-downloader** (filipopper) — PRAW-based, keeps a CSV log of what's already been
   downloaded across runs.
10. **reddit_image_downloader** (samrand96) — PRAW + requests, per-user multi-threaded image
    scraper.
11. **gallery-dl** — general-purpose gallery downloader (200+ sites) with a dedicated Reddit
    extractor; handles multi-image galleries and v.redd.it (downloads video+audio streams
    separately, merges with ffmpeg automatically if present).
12. **yt-dlp** — has a native Reddit/v.redd.it extractor; `yt-dlp -f "bv*+ba/b" URL` downloads both
    DASH streams and merges them with its ffmpeg postprocessor. The modern standard for this.
13. **youtube-dl** — yt-dlp's predecessor, slower-moving but still has basic Reddit support.
14. **Direct JSON API scripting** — `requests.get("https://www.reddit.com/r/sub/.json")` or
    `/comments/<id>.json` needs no auth for public posts; manually walk `media_metadata` /
    `gallery_data` / `secure_media` to get raw URLs. This is what our own `RedditDownloadBridge`'s
    JS-side detection is a WebView-DOM-based analogue of.
15. **Pushshift-style historical APIs / PMAW** — for enumerating large numbers of old posts by
    search criteria before feeding URLs to any of the above.
16. **Scrapy** — a custom spider is a common DIY route when someone wants full control over
    crawl pacing/retries.
17. **Selenium / Playwright** — browser automation to load a page, wait for JS-rendered media
    elements, then pull `src` attributes — used when a site needs real JS execution.
18. **requests + BeautifulSoup against old.reddit.com directly** — no API key needed at all,
    since old Reddit's HTML is simple and stable.

## B. Command-line / general tools

19. **Manual ffmpeg** — the lowest-level real fix for "no audio": download `DASH_720.mp4` (video)
    and `DASH_audio.mp4` (audio) with curl/wget, then
    `ffmpeg -i video.mp4 -i audio.mp4 -c:v copy -c:a aac output.mp4`.
20. **streamlink** — can pull `v.redd.it`'s `HLSPlaylist.m3u8` manifest for stream-style capture.
21. **N_m3u8DL-RE** — purpose-built DASH/HLS downloader; can target `v.redd.it`'s
    `DASHPlaylist.mpd` directly instead of guessing filenames.
22. **wget/curl loops against guessed filenames** — zero-dependency version of #19, relying on the
    `DASH_<res>.mp4` / `DASH_audio.mp4` naming convention.
23. **you-get** — another universal CLI media downloader with broad site coverage.
24. **HTTrack** — full site mirroring; BDFR's own docs point to this for a "true" offline clone
    (BDFR's own "clone" mode is explicitly *not* a faithful clone).
25. **JDownloader 2** — Java batch/queue download manager; clipboard-watches for links and uses a
    site-plugin architecture that includes Reddit.

## C. Browser extensions

26. **Video DownloadHelper** (Firefox since 2006, Chrome port) — generic DASH/HLS/MPD sniffer,
    1000+ sites, catches Reddit's video player; free tier is watermarked/nagged, paid removes that.
27. **"Video Downloader for Reddit" by RedditSave** (Chrome + Firefox) — the official extension
    from the redditsave.com/rapidsave.com team; adds an inline download button to posts.
28. **Red Saver** (Edge/Chrome) — another dedicated Reddit-focused extension.
29. **Flash Video Downloader** — generic page-media sniffer, also catches Reddit/Facebook/Twitter.
30. **Video Downloader Plus / CocoCut / FetchV / Video Downloader Professional** — generic
    HLS/DASH sniffers, not Reddit-specific but work on it like any other HTML5-video page.
31. **DownThemAll** (Firefox) — generic bulk link/file downloader; handy for grabbing every
    i.redd.it image out of a rendered gallery page at once.
32. **Tampermonkey/Greasemonkey userscripts, Reddit-specific** (Greasy Fork):
    - *Reddit Media Downloader* (Shawn Parker) — video+audio/GIFs/galleries/images in one script.
    - *Reddit Video Downloader (with sound) [RedditSave]* (Salas Dario) — wraps the RapidSave API.
    - *Reddit Video Downloader* (Lawrence Sim) — adds direct-link buttons on post pages.
    - *Reddit Video Downloader* (Berk Alyamaç) — Ctrl+S/Meta+S keyboard-triggered capture.
    - *r2monkey* (jijirae) — Shift+D opens the current video in RapidSave.
    - *Reddit Download Buttons* — inline buttons for images/videos.
    - *Custom Native HTML5 Player with Shortcuts* (narcolepticinsomniac) — replaces Reddit's player
      with one that natively plays v.redd.it's split audio+video together, 5,000+ installs.
    - *Old Reddit Images* / *Reddit Direct Raw Image Viewer* — bypass Reddit's media wrapper pages
      to expose the raw file URL for a plain right-click-save.

## D. Standalone "paste a link" downloader websites

33. **RapidSave** (formerly RedditSave) — repeatedly cited as the current "gold standard"; merges
    audio automatically, free, no login.
34. **redditsave.com** — companion site to the u/savevideo bot (see section E).
35. **Viddit.red** — audio/video merging specialist; also ships as an Android/iOS app (see F).
36. **RedVid / redvid.io** — known specifically for reliability/uptime and handling multi-video
    gallery posts.
37. **Toolzin** — batch download, HD, handles video/gif/image together.
38. **toolzu** — minimal two-step flow for a single video/gif.
39. **Bigbangram** — multi-platform (not Reddit-only) downloader that also covers Reddit.
40. **SaveFrom.net** — long-running multi-platform downloader with Reddit support.
41. **SnapDownloader** — paid, higher-end web/desktop hybrid.
42. **VideoDownloader.so** — positions itself on safety/no-malware for multi-platform downloads.
43. **4K Video Downloader** — desktop app, also accepts Reddit URLs, claims up to 8K passthrough.
44. **Video Duke** — Mac/PC desktop app aimed at power users doing many downloads/day.
45. **dropzap.digital** — explicitly documents doing the ffmpeg merge server-side, same technique
    as #19/#20 but as a hosted service.
46. **Wondershare UniConverter** — desktop converter suite with a Reddit downloader module.
47. **SSReddit** — another paste-link web tool.
48. **ReSavr / RDSave** — further paste-link tools showing up across independent round-ups.

## E. Reddit-native bots (comment-triggered)

49. **u/savevideo** — by far the most-cited: comment the username under any video post, it replies
    with a redditsave.com link within ~30-120s. Requires a logged-in Reddit account to comment.
50. **u/savevideobot** — alternate bot name referenced by some guides alongside u/savevideo.
51. **VRedditDownloader / VRedditShare / SaveThisVideo** (historical) — earlier bots in this niche
    that got suspended after creators complained about unauthorized reposting; cited here as
    precedent for how fragile the "comment-triggered bot" category is long-term.

## F. Mobile apps

52. **Viddit** (Android + iOS) — clipboard-paste app, resolution picker, from the Viddit.red team.
53. **Video Downloader for Reddit** (King Clover Studio, Play Store/APKPure) — dedicated Reddit
    APK, multi-resolution, "5x faster" multi-threaded claim.
54. **Downloader for Reddit** (lay/laydev) — explicit multi-image-gallery and GIF support, no login.
55. **Video + Audio Downloader For Reddit** (Roman Koval R) — minimal clipboard-paste app, no
    frills (not even a download history).
56. **1DM** (formerly IDM+) — general Android download manager with in-browser link sniffing,
    frequently recommended specifically for Reddit video in app-recommendation threads.
57. **ADM (Advanced Download Manager)** — same category as 1DM, same use pattern.
58. **Seal** (F-Droid, open source, Material You) — a proper Android front-end *for yt-dlp*, so it
    inherits yt-dlp's Reddit support rather than reimplementing it.
59. **PowerTube** — another general Android video-downloader app recommended alongside the above.
60. **All Video Saver** (iOS) — iOS-side equivalent of the generic multi-platform saver apps.

## G. Prior art: other Reddit client apps with a built-in download/save feature

61. **Sync for Reddit**, **RIF is Fun**, **BaconReader**, **Relay for Reddit**, **Boost for
    Reddit** — the earlier generation of third-party native Reddit clients (this project's own
    category before the WebView pivot) each shipped their own save-to-gallery button, functionally
    the same job our `RedditDownloadBridge` does now.

## H. Archival-grade / bulk data-hoarding tools

62. **ripme** (RipMeApp) — the long-running "OG" bulk media ripper the r/DataHoarder community
    still references, has a Reddit ripper module.
63. **HTTrack** (also #24) — full offline site clone, heavier-weight than a media-only downloader.
64. **ArchiveBox** — generic URL archiver; can snapshot whole Reddit post pages (not just the
    linked media) for long-term offline reference.

## I. Non-Python scripting ecosystems

65. **snoowrap** (Node.js) — the JavaScript equivalent of PRAW, same technique as #1 but in Node.

## J. Low-level / manual techniques (no library, no app)

66. **Direct DASH manifest fetch** — `GET https://v.redd.it/<id>/DASHPlaylist.mpd` and parse it
    properly for the exact video+audio representation URLs, instead of guessing filenames.
67. **Direct HLS manifest fetch** — `GET https://v.redd.it/<id>/HLSPlaylist.m3u8`, same idea.
68. **Filename-guessing** — the technique most lightweight tools actually use: take the known video
    URL (`.../DASH_720.mp4`) and swap the resolution segment for `audio` (`DASH_audio.mp4`) or, on
    older posts, `AUDIO_128` (`DASH_AUDIO_128.mp4`) — cheaper than parsing a manifest, good enough
    in practice.
69. **Reddit's own GDPR data export** ("Request my data" in account settings) — downloads a user's
    own post/comment history as CSV/JSON. Metadata only, not the media files themselves, but it is
    a first-party "download your Reddit content" path worth knowing about.
70. **The official Reddit app's own "Download" button** — exists today on some video posts in the
    first-party app; doesn't help third-party clients, but it's proof Reddit itself doesn't
    consider downloading a forbidden action for its own client.

## K. Techniques specific to fixing *our* app

71. **Android `DownloadManager`** — what `RedditDownloadBridge` uses today: simplest possible
    enqueue-and-forget download, but it has no concept of "fetch two URLs and combine them", which
    is exactly why today's downloaded v.redd.it videos have no audio.
72. **Android `MediaMuxer` + `MediaExtractor`** (`android.media`, framework-only, API 18+ — well
    under our minSdk 21) — reads the separately-downloaded video and audio files and muxes them
    into one playable MP4 *in-process, with no ffmpeg dependency at all*. This is the same
    video+audio problem every tool in sections A-F solves with ffmpeg; Android's own framework can
    do the equivalent muxing step (not transcoding — it's a container-level copy, so it's fast and
    lossless) without adding a single new library to the app.
73. **FFmpegKit / mobile-ffmpeg** (Android AAR) — the heavier alternative to #72: bundles a real
    compiled ffmpeg binary (~20-40MB added to the APK) for cases `MediaMuxer` can't handle (actual
    transcoding, not just muxing). Not needed for this specific problem, since v.redd.it's streams
    are already compatible containers/codecs that just need muxing, not re-encoding.
74. **WorkManager-orchestrated multi-file download** — the architectural pattern tying the above
    together: enqueue the video-stream download and audio-stream download, then chain a muxing
    step once both finish. (Not evaluated for this app yet — would be a real dependency add, unlike
    #72 which is framework-only.)

## Image-specific notes (separate from the video problem above)

75. **Plain "Save image as" / long-press-save** — for `i.redd.it`/`preview.redd.it` posts, there's
    no video-style split-stream problem at all; these are already single static files.
76. **Imgur-specific bulk downloaders** — a meaningful fraction of older Reddit posts link out to
    Imgur rather than hosting on `i.redd.it`; Imgur album downloaders are their own well-established
    tool category, separate from "Reddit downloaders" proper.
77. **Redgifs' own official download button** — most Reddit NSFW video posts embed a redgifs.com
    player; visiting that link directly gives an official first-party download option with no
    third-party tool needed at all.
78. **Gfycat** (historical, merged into Redgifs) — same idea as #77 for older posts.

---

## What this means for Continuum

Two concrete, low-risk findings came out of this that map directly onto
`RedditDownloadBridge`/`reddit_web_enhancements.js`'s known limitations:

1. **The "no audio" limitation is fixable without adding a single dependency.** Every serious tool
   in this list (yt-dlp, gallery-dl, BDFR, RapidSave, Video DownloadHelper, the Tampermonkey
   scripts) solves the exact same problem the same way: fetch the video stream *and* the audio
   stream, then mux them. Our app already detects the video URL; it would need to also derive the
   audio URL (filename-guessing, item #68, with the DASH manifest fetch of #66 as a more robust
   fallback) and use Android's own `MediaMuxer`/`MediaExtractor` (#72) to combine them — framework
   classes available since well below our minSdk 21, no ffmpeg binary, no new Gradle dependency.
2. **Gallery/multi-image posts are a well-established, well-solved case** (BDFR, gallery-dl, PRAW's
   `media_metadata`/`gallery_data` walk in section A all handle it the same documented way) that
   would be worth checking our own `reddit_web_enhancements.js`'s DOM-based media detection against,
   if it doesn't already offer a "download all" for gallery posts the way `action_download_all_gallery_media`
   (an existing but currently-unused string resource) suggests the original native app once did.

I haven't changed any code yet — this message is the research you asked for. Let me know if you'd
like me to implement the audio-mux fix (point 1) and/or gallery "download all" support (point 2);
both are scoped clearly enough from this research to build directly.
