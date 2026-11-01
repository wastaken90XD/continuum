// Continuum's "full site" userscript-style enhancements for reddit.com, run inside
// RedditWebActivity's WebView. Design note: this is written and audited by Continuum, not pulled
// in wholesale from a third-party userscript -- the app's WebView holds the user's real,
// logged-in Reddit session cookie, and running unaudited third-party JS against that session would
// be a real way to leak or hijack it. The media-detection *approach* below (trusted-CDN allowlist,
// strict HTTPS-only validation, filename sanitizing) mirrors the public-domain design pattern used
// by several open-source Reddit media downloader userscripts (see e.g.
// github.com/mrkwxopya/reddit-media-downloader's "Zero Trust" validation), reimplemented here from
// scratch against this app's own native download bridge instead of a browser extension API.
//
// Targets both reddit.com frontends this app can show (see RedditSettings): new Reddit's Shadow
// DOM web components (shreddit-post, shreddit-player, gallery-carousel, ...) and old.reddit.com's
// classic server-rendered markup (.thing.link posts, .media-preview-content / .gallery-tile-content
// expandos). Not run on Redlib -- see RedditWebActivity's javadoc on why that allowlist is
// reddit.com's own media CDNs only.
//
// Known limitation: both of those markups can and do change without notice -- the selectors below
// are a best-effort snapshot, not a stable contract. If Reddit changes their markup, the worst case
// is the download button silently stops appearing (fails safe), not a crash.
//
// Download menu: clicking the per-post download button opens a small in-page action sheet (built
// here, not a native Android dialog) listing every option this file can offer for that post --
// this is the "multiple options" surface, modeled loosely on how a desktop extension like Video
// DownloadHelper presents several quality/format choices instead of a single silent download.
//   - video posts: fetches the DASH manifest (same one v.redd.it's own player already requests) to
//     list real quality options, each paired with the best available audio track, so the resulting
//     file actually has sound -- see downloadVideoMuxed below and RedditDownloadBridge's
//     MediaMuxer-based merge. A "video only, no audio" option is always offered too, since manifest
//     parsing is a best-effort step that can fail if Reddit changes the manifest shape.
//   - gallery posts: "download all N images" plus one entry per image.
//   - single image posts: a plain download, plus "copy link" for anyone who'd rather save it with
//     a different tool from this file's research catalog (see docs/reddit-download-methods-research.md).
(function () {
    'use strict';

    if (window.__continuumEnhancementsInstalled) {
        return;
    }
    window.__continuumEnhancementsInstalled = true;

    var ALLOWED_MEDIA_HOSTS = ['i.redd.it', 'preview.redd.it', 'external-preview.redd.it', 'v.redd.it'];

    function isAllowedMediaUrl(value) {
        if (typeof value !== 'string' || !value || value.length > 4096) {
            return false;
        }
        var url;
        try {
            url = new URL(value, location.href);
        } catch (e) {
            return false;
        }
        if (url.protocol !== 'https:' || url.username || url.password) {
            return false;
        }
        var host = url.hostname.toLowerCase();
        for (var i = 0; i < ALLOWED_MEDIA_HOSTS.length; i++) {
            if (host === ALLOWED_MEDIA_HOSTS[i]) {
                return true;
            }
        }
        return false;
    }

    function sanitizeFilename(value) {
        if (typeof value !== 'string' || !value) {
            return 'reddit-media';
        }
        var result = value
            .replace(/[<>:"/\\|?*\x00-\x1F]/g, '')
            .replace(/\s+/g, '_')
            .replace(/__+/g, '_')
            .trim();
        return result.substring(0, 150) || 'reddit-media';
    }

    function extensionFromUrl(value, fallback) {
        var withoutQuery = value.split('?')[0];
        var match = withoutQuery.match(/\.[a-zA-Z0-9]+$/);
        return match ? match[0] : fallback;
    }

    // --- Hide the app-install nag / cookie-consent overlays that otherwise cover the page ---
    function hideKnownOverlays() {
        var selectors = [
            '#data-protection-consent-wrapper',
            'shreddit-app-install-banner',
            '[data-testid="app-install-banner"]'
        ];
        for (var i = 0; i < selectors.length; i++) {
            var el = document.querySelector(selectors[i]);
            if (el) {
                el.style.display = 'none';
            }
        }
    }

    // --- Clipboard helper used by the "copy link" option everywhere below ---
    function copyToClipboard(text) {
        var ok = false;
        try {
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(text);
                ok = true;
            } else {
                var textarea = document.createElement('textarea');
                textarea.value = text;
                textarea.style.position = 'fixed';
                textarea.style.opacity = '0';
                document.body.appendChild(textarea);
                textarea.select();
                ok = document.execCommand('copy');
                document.body.removeChild(textarea);
            }
        } catch (e) {
            ok = false;
        }
        if (window.ContinuumDownloadBridge && window.ContinuumDownloadBridge.toast) {
            window.ContinuumDownloadBridge.toast(ok ? 'Link copied' : "Couldn't copy link");
        }
    }

    function triggerSingleDownload(url, filename, typeHint) {
        if (window.ContinuumDownloadBridge && isAllowedMediaUrl(url)) {
            window.ContinuumDownloadBridge.downloadMedia(url, filename, typeHint);
        }
    }

    // --- Action sheet: a minimal bottom-sheet-style menu for presenting multiple download
    // options. Built from plain DOM/inline styles (no new CSS file, matching this file's existing
    // single-button style) and attached to document.body rather than the post element, since post
    // containers can clip overflow and are frequently re-rendered/recycled by Reddit's own virtual
    // list as the user scrolls. sheetToken guards against a slow async callback (e.g. the DASH
    // manifest fetch below) repainting a sheet the user has already dismissed or replaced.
    var sheetToken = 0;
    var sheetBackdrop = null;

    function closeActionSheet() {
        sheetToken++;
        if (sheetBackdrop && sheetBackdrop.parentNode) {
            sheetBackdrop.parentNode.removeChild(sheetBackdrop);
        }
        sheetBackdrop = null;
    }

    function showActionSheet(title, items) {
        closeActionSheet();
        sheetToken++;
        var myToken = sheetToken;

        var backdrop = document.createElement('div');
        backdrop.style.cssText =
            'position:fixed;inset:0;background:rgba(0,0,0,0.5);z-index:2147483646;';
        backdrop.addEventListener('click', function (event) {
            if (event.target === backdrop) {
                closeActionSheet();
            }
        });

        var panel = document.createElement('div');
        panel.style.cssText =
            'position:fixed;left:0;right:0;bottom:0;max-height:70vh;overflow-y:auto;' +
            'background:#1a1a1b;border-radius:12px 12px 0 0;padding:10px 0 4px;' +
            'font-family:sans-serif;box-shadow:0 -2px 16px rgba(0,0,0,0.4);';

        var titleEl = document.createElement('div');
        titleEl.textContent = title;
        titleEl.style.cssText =
            'padding:6px 16px 10px;font-size:12px;font-weight:700;color:#818384;' +
            'text-transform:uppercase;letter-spacing:.04em;';
        panel.appendChild(titleEl);

        for (var i = 0; i < items.length; i++) {
            (function (item) {
                var row = document.createElement('button');
                row.type = 'button';
                row.textContent = item.label;
                row.disabled = !!item.disabled;
                row.style.cssText =
                    'display:block;width:100%;text-align:left;padding:14px 16px;border:none;' +
                    'background:transparent;color:' + (item.disabled ? '#565758' : '#d7dadc') + ';' +
                    'font-size:15px;cursor:' + (item.disabled ? 'default' : 'pointer') + ';' +
                    'border-bottom:1px solid #343536;';
                if (!item.disabled && item.action) {
                    row.addEventListener('click', function () {
                        closeActionSheet();
                        item.action();
                    });
                }
                panel.appendChild(row);
            })(items[i]);
        }

        var cancel = document.createElement('button');
        cancel.type = 'button';
        cancel.textContent = 'Cancel';
        cancel.style.cssText =
            'display:block;width:100%;text-align:center;padding:14px 16px;margin-top:4px;' +
            'border:none;background:transparent;color:#d7dadc;font-size:15px;font-weight:600;' +
            'cursor:pointer;';
        cancel.addEventListener('click', closeActionSheet);
        panel.appendChild(cancel);

        backdrop.appendChild(panel);
        document.body.appendChild(backdrop);
        sheetBackdrop = backdrop;
        return myToken;
    }

    // --- DASH manifest parsing, used only to build the video quality menu on demand (not run
    // during the background scan, to avoid firing a network request per video post just from
    // scrolling past it). This is the same manifest v.redd.it's own player already fetches, so a
    // plain same-origin-style GET for it is expected traffic, not something unusual we're adding.
    function deriveManifestUrl(videoUrl) {
        try {
            var parsed = new URL(videoUrl, location.href);
            if (parsed.hostname.toLowerCase() !== 'v.redd.it') {
                return null;
            }
            var segments = parsed.pathname.split('/');
            if (segments.length < 2) {
                return null;
            }
            segments[segments.length - 1] = 'DASHPlaylist.mpd';
            return parsed.origin + segments.join('/');
        } catch (e) {
            return null;
        }
    }

    function fetchVideoQualityOptions(videoUrl, callback) {
        var manifestUrl = deriveManifestUrl(videoUrl);
        if (!manifestUrl || !isAllowedMediaUrl(manifestUrl)) {
            callback(null);
            return;
        }
        fetch(manifestUrl, { credentials: 'omit' })
            .then(function (response) {
                if (!response.ok) {
                    throw new Error('manifest http ' + response.status);
                }
                return response.text();
            })
            .then(function (xmlText) {
                var doc = new DOMParser().parseFromString(xmlText, 'application/xml');
                if (doc.querySelector('parsererror')) {
                    throw new Error('manifest did not parse as xml');
                }
                var videoReps = [];
                var audioReps = [];
                var adaptationSets = doc.getElementsByTagName('AdaptationSet');
                for (var i = 0; i < adaptationSets.length; i++) {
                    var set = adaptationSets[i];
                    var setContentType = set.getAttribute('contentType') || '';
                    var setMimeType = set.getAttribute('mimeType') || '';
                    var representations = set.getElementsByTagName('Representation');
                    for (var j = 0; j < representations.length; j++) {
                        var rep = representations[j];
                        var baseUrlEl = rep.getElementsByTagName('BaseURL')[0];
                        if (!baseUrlEl || !baseUrlEl.textContent) {
                            continue;
                        }
                        var resolvedUrl;
                        try {
                            resolvedUrl = new URL(baseUrlEl.textContent.trim(), manifestUrl).href;
                        } catch (e) {
                            continue;
                        }
                        if (!isAllowedMediaUrl(resolvedUrl)) {
                            continue;
                        }
                        var repMimeType = rep.getAttribute('mimeType') || setMimeType;
                        var isAudio = setContentType === 'audio' || repMimeType.indexOf('audio') === 0;
                        var isVideo = setContentType === 'video' || repMimeType.indexOf('video') === 0;
                        var bandwidth = parseInt(rep.getAttribute('bandwidth') || '0', 10) || 0;
                        if (isAudio && !isVideo) {
                            audioReps.push({ url: resolvedUrl, bandwidth: bandwidth });
                        } else if (isVideo) {
                            var height = parseInt(rep.getAttribute('height') || '0', 10) || 0;
                            videoReps.push({ url: resolvedUrl, height: height, bandwidth: bandwidth });
                        }
                    }
                }
                if (!videoReps.length) {
                    throw new Error('no usable video representations in manifest');
                }
                videoReps.sort(function (a, b) {
                    return (b.height - a.height) || (b.bandwidth - a.bandwidth);
                });
                audioReps.sort(function (a, b) {
                    return b.bandwidth - a.bandwidth;
                });
                callback({ video: videoReps, audioUrl: audioReps.length ? audioReps[0].url : null });
            })
            .catch(function () {
                callback(null);
            });
    }

    function qualityLabel(rep) {
        return rep.height ? rep.height + 'p' : (Math.max(1, Math.round(rep.bandwidth / 1000)) + ' kbps');
    }

    function openVideoDownloadMenu(media, filenameBase) {
        var baseItems = [
            {
                label: 'Video only (fastest, no audio)',
                action: function () {
                    triggerSingleDownload(media.url, filenameBase + '.mp4', 'video');
                }
            },
            {
                label: 'Copy video link',
                action: function () {
                    copyToClipboard(media.url);
                }
            }
        ];
        var myToken = showActionSheet('Download video', baseItems.concat([
            { label: 'Loading quality options\u2026', disabled: true }
        ]));

        fetchVideoQualityOptions(media.url, function (result) {
            if (sheetToken !== myToken) {
                // The user already closed or replaced this sheet; don't reopen it from a stale
                // async callback.
                return;
            }
            var items = baseItems.slice();
            if (result && result.video.length && result.audioUrl) {
                var audioUrl = result.audioUrl;
                for (var i = 0; i < result.video.length; i++) {
                    (function (rep) {
                        items.splice(items.length - 1, 0, {
                            label: qualityLabel(rep) + ' (with audio)',
                            action: function () {
                                if (window.ContinuumDownloadBridge &&
                                        isAllowedMediaUrl(rep.url) && isAllowedMediaUrl(audioUrl)) {
                                    window.ContinuumDownloadBridge.downloadVideoMuxed(
                                        rep.url, audioUrl, filenameBase + '.mp4');
                                }
                            }
                        });
                    })(result.video[i]);
                }
            } else {
                items.splice(items.length - 1, 0, {
                    label: "Couldn't load quality list \u2014 video-only download still available",
                    disabled: true
                });
            }
            showActionSheet('Download video', items);
        });
    }

    function openImageDownloadMenu(media, filenameBase) {
        showActionSheet('Download image', [
            {
                label: 'Download image',
                action: function () {
                    triggerSingleDownload(
                        media.url, filenameBase + extensionFromUrl(media.url, '.jpg'), 'image');
                }
            },
            {
                label: 'Copy image link',
                action: function () {
                    copyToClipboard(media.url);
                }
            }
        ]);
    }

    function openGalleryDownloadMenu(media, filenameBase) {
        var urls = media.galleryUrls;
        var items = [
            {
                label: 'Download all ' + urls.length + ' images',
                action: function () {
                    if (window.ContinuumDownloadBridge) {
                        window.ContinuumDownloadBridge.downloadGalleryAll(
                            JSON.stringify(urls), filenameBase);
                    }
                }
            }
        ];
        for (var i = 0; i < urls.length; i++) {
            (function (url, index) {
                items.push({
                    label: 'Image ' + (index + 1) + ' of ' + urls.length,
                    action: function () {
                        triggerSingleDownload(
                            url, filenameBase + '_' + (index + 1) + extensionFromUrl(url, '.jpg'), 'image');
                    }
                });
            })(urls[i], i);
        }
        showActionSheet('Download gallery', items);
    }

    // --- Media detection + a small floating download control per post ---
    function collectGalleryImages(root) {
        if (!root || !root.querySelectorAll) {
            return [];
        }
        var imgs = root.querySelectorAll(
            'img[src^="https://i.redd.it"], img[src^="https://preview.redd.it"], img[src^="https://external-preview.redd.it"]'
        );
        var seen = {};
        var urls = [];
        for (var i = 0; i < imgs.length; i++) {
            var src = imgs[i].src;
            if (src && isAllowedMediaUrl(src) && !seen[src]) {
                seen[src] = true;
                urls.push(src);
            }
        }
        return urls;
    }

    function findMediaUrlForPost(postEl) {
        // Direct <img>/<video>/<source> anywhere under the post, including open shadow roots one
        // level deep (shreddit-post's own media elements, and simple nested components).
        var roots = [postEl];
        var all = postEl.querySelectorAll('*');
        for (var i = 0; i < all.length; i++) {
            if (all[i].shadowRoot) {
                roots.push(all[i].shadowRoot);
            }
        }

        for (var r = 0; r < roots.length; r++) {
            var root = roots[r];
            var video = root.querySelector && root.querySelector('video');
            if (video) {
                var videoSrc = video.currentSrc || video.src;
                if (!videoSrc) {
                    var source = video.querySelector('source');
                    if (source) {
                        videoSrc = source.src;
                    }
                }
                if (videoSrc && isAllowedMediaUrl(videoSrc)) {
                    return { type: 'video', url: videoSrc };
                }
            }
        }

        // Gallery detection looks for a dedicated gallery signal first -- a more reliable test
        // than "more than one <img> under the post", since a post card can legitimately contain
        // one real image plus unrelated avatar/award/thumbnail chrome elsewhere.
        for (var g = 0; g < roots.length; g++) {
            var root4 = roots[g];

            // New Reddit: a dedicated gallery carousel web component.
            var carousel = root4.querySelector && root4.querySelector(
                'gallery-carousel, shreddit-gallery-carousel, [data-testid="gallery-carousel"]'
            );
            if (carousel) {
                var carouselUrls = collectGalleryImages(carousel.shadowRoot || carousel);
                if (carouselUrls.length > 1) {
                    return { type: 'gallery', url: carouselUrls[0], galleryUrls: carouselUrls };
                }
            }

            // old.reddit.com: each gallery image sits in its own ".gallery-tile-content" (or
            // "-item") tile, a sibling pattern to the single-image ".media-preview-content"
            // expando Reddit documented when it introduced inline expando previews. Requiring at
            // least 2 tiles (not just "any image on the page") avoids misreading an ordinary
            // single-image post as a 1-item gallery.
            var tiles = root4.querySelectorAll && root4.querySelectorAll(
                '.gallery-tile-content, .gallery-tile, .gallery-item'
            );
            if (tiles && tiles.length > 1) {
                var tileUrls = collectGalleryImages(root4);
                if (tileUrls.length > 1) {
                    return { type: 'gallery', url: tileUrls[0], galleryUrls: tileUrls };
                }
            }
        }

        for (var s = 0; s < roots.length; s++) {
            var img = roots[s].querySelector && roots[s].querySelector(
                'img[src^="https://i.redd.it"], img[src^="https://preview.redd.it"], img[src^="https://external-preview.redd.it"]'
            );
            if (img && img.src && isAllowedMediaUrl(img.src)) {
                return { type: 'image', url: img.src };
            }
        }

        return null;
    }

    function postTitleFor(postEl) {
        // "[slot=title]"/h1/h3 are new Reddit; "a.title" is old.reddit.com's title link.
        var titleEl = postEl.querySelector('[slot="title"], a[slot="title"], h1, h3, a.title');
        return (titleEl && titleEl.textContent) ? titleEl.textContent.trim() : 'reddit-media';
    }

    function buttonLabelFor(media) {
        if (media.type === 'video') {
            return '\u2b07 Video';
        }
        if (media.type === 'gallery') {
            return '\u2b07 Gallery (' + media.galleryUrls.length + ')';
        }
        return '\u2b07 Image';
    }

    function addDownloadControl(postEl, media) {
        if (postEl.querySelector('.continuum-dl-btn')) {
            return;
        }
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'continuum-dl-btn';
        btn.textContent = buttonLabelFor(media);
        btn.style.cssText =
            'position:absolute;top:8px;right:8px;z-index:2147483000;padding:6px 10px;' +
            'border:none;border-radius:16px;background:rgba(0,0,0,0.65);color:#fff;' +
            'font-size:12px;font-weight:600;cursor:pointer;';
        btn.addEventListener('click', function (event) {
            event.preventDefault();
            event.stopPropagation();
            var filenameBase = sanitizeFilename(postTitleFor(postEl));
            if (media.type === 'gallery') {
                openGalleryDownloadMenu(media, filenameBase);
            } else if (media.type === 'video') {
                openVideoDownloadMenu(media, filenameBase);
            } else {
                openImageDownloadMenu(media, filenameBase);
            }
        });

        if (getComputedStyle(postEl).position === 'static') {
            postEl.style.position = 'relative';
        }
        postEl.appendChild(btn);
    }

    function scanForMedia() {
        hideKnownOverlays();
        // shreddit-post/article/[data-testid=post-container] are new Reddit; .thing.link is
        // old.reddit.com's post row (used both in listings and as the OP post on a comments page).
        var posts = document.querySelectorAll(
            'shreddit-post, article, [data-testid="post-container"], .thing.link'
        );
        for (var i = 0; i < posts.length; i++) {
            var media = findMediaUrlForPost(posts[i]);
            if (media) {
                addDownloadControl(posts[i], media);
            }
        }
    }

    var scanTimer = null;
    function scheduleScan() {
        if (scanTimer) {
            clearTimeout(scanTimer);
        }
        scanTimer = setTimeout(scanForMedia, 400);
    }

    new MutationObserver(scheduleScan).observe(document.documentElement || document, {
        childList: true,
        subtree: true
    });

    scheduleScan();
})();
