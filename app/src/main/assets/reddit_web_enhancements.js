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
// Known limitation: Reddit's current web app renders with Shadow DOM web components
// (shreddit-post, shreddit-player, gallery-carousel, ...) whose internal structure Reddit can and
// does change without notice -- the selectors below are a best-effort snapshot, not a stable
// contract. If Reddit changes their markup, the worst case is the download button silently stops
// appearing (fails safe), not a crash.
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

    function guessMimeHint(url) {
        var lower = url.toLowerCase();
        if (lower.indexOf('v.redd.it') !== -1 || /\.(mp4|webm|mov|m4v)(\?|$)/.test(lower)) {
            return 'video';
        }
        return 'image';
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

    // --- Media detection + a small floating download control per post ---
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
                    return { url: videoSrc, type: 'video' };
                }
            }

            var img = root.querySelector && root.querySelector(
                'img[src^="https://i.redd.it"], img[src^="https://preview.redd.it"], img[src^="https://external-preview.redd.it"]'
            );
            if (img && img.src && isAllowedMediaUrl(img.src)) {
                return { url: img.src, type: 'image' };
            }
        }

        return null;
    }

    function postTitleFor(postEl) {
        var titleEl = postEl.querySelector('[slot="title"], a[slot="title"], h1, h3');
        return (titleEl && titleEl.textContent) ? titleEl.textContent.trim() : 'reddit-media';
    }

    function addDownloadControl(postEl, media) {
        if (postEl.querySelector('.continuum-dl-btn')) {
            return;
        }
        var btn = document.createElement('button');
        btn.className = 'continuum-dl-btn';
        btn.textContent = media.type === 'video' ? '\u2b07 Video' : '\u2b07 Image';
        btn.style.cssText =
            'position:absolute;top:8px;right:8px;z-index:2147483000;padding:6px 10px;' +
            'border:none;border-radius:16px;background:rgba(0,0,0,0.65);color:#fff;' +
            'font-size:12px;font-weight:600;cursor:pointer;';
        btn.addEventListener('click', function (event) {
            event.preventDefault();
            event.stopPropagation();
            var filename = sanitizeFilename(postTitleFor(postEl)) +
                (media.type === 'video' ? '.mp4' : (media.url.match(/\.[a-zA-Z0-9]+($|\?)/) || ['.jpg'])[0].replace('?', ''));
            if (window.ContinuumDownloadBridge && isAllowedMediaUrl(media.url)) {
                window.ContinuumDownloadBridge.downloadMedia(media.url, filename, media.type);
            }
        });

        if (getComputedStyle(postEl).position === 'static') {
            postEl.style.position = 'relative';
        }
        postEl.appendChild(btn);
    }

    function scanForMedia() {
        hideKnownOverlays();
        var posts = document.querySelectorAll('shreddit-post, article, [data-testid="post-container"]');
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
