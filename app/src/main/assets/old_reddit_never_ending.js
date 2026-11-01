// Continuum's version of Reddit Enhancement Suite's "Never Ending Reddit" -- RES's flagship
// feature. Reimplemented from scratch against old.reddit.com's actual markup (a WebView can't
// load a browser extension), not copied: RES is a many-thousand-line jQuery-era codebase built
// for a dozen RES features that all interact; this is the one feature, standing alone.
//
// Only runs on old.reddit.com listing pages (subreddit/multireddit/user/search -- anything with a
// paginated .sitetable and a "next" link). Comment pages have a sitetable too but never have that
// pagination bar, so checking for the nav-buttons bar is sufficient to exclude them without having
// to separately detect "is this a comments page".
(function () {
    if (window.__continuumNeverEndingReddit) {
        return;
    }
    window.__continuumNeverEndingReddit = true;

    function logToNative(message) {
        try {
            if (window.ContinuumDownloadBridge && window.ContinuumDownloadBridge.log) {
                window.ContinuumDownloadBridge.log(message);
            }
        } catch (e) {
            // Bridge not available for some reason -- not worth doing anything about.
        }
    }

    function findSiteTable(root) {
        return root.querySelector('#siteTable.linklisting, .sitetable.linklisting');
    }

    function findNextLink(navButtons) {
        if (!navButtons) {
            return null;
        }
        var a = navButtons.querySelector('.next-button a');
        return a ? a.getAttribute('href') : null;
    }

    var siteTable = findSiteTable(document);
    var navButtons = document.querySelector('.nav-buttons');
    if (!siteTable || !navButtons || !findNextLink(navButtons)) {
        // Not a paginated listing page (e.g. a comments page, or the last page already).
        return;
    }

    var loading = false;
    var exhausted = false;
    var pagesLoaded = 1;
    // A safety cap, same idea as RES's "pause after N pages" -- without one this would keep
    // appending DOM nodes to a single page for as long as the user keeps scrolling, which is
    // exactly the kind of unbounded growth a phone's WebView can least afford.
    var MAX_AUTO_PAGES = 20;

    function nearBottom() {
        return (window.innerHeight + window.scrollY) >= (document.documentElement.scrollHeight - 1200);
    }

    function directChildThings(table) {
        var things = [];
        var children = table.children;
        for (var i = 0; i < children.length; i++) {
            if (children[i].classList && children[i].classList.contains('thing')) {
                things.push(children[i]);
            }
        }
        return things;
    }

    function loadNextPage() {
        if (loading || exhausted) {
            return;
        }
        var nextHref = findNextLink(navButtons);
        if (!nextHref) {
            exhausted = true;
            return;
        }
        if (pagesLoaded >= MAX_AUTO_PAGES) {
            exhausted = true;
            logToNative('Never Ending Reddit: reached the ' + MAX_AUTO_PAGES + '-page safety cap; use the Next link to keep going.');
            return;
        }

        loading = true;
        fetch(nextHref, {credentials: 'same-origin'})
            .then(function (response) {
                return response.text();
            })
            .then(function (html) {
                var nextDoc = new DOMParser().parseFromString(html, 'text/html');
                var nextSiteTable = findSiteTable(nextDoc);
                if (nextSiteTable) {
                    var things = directChildThings(nextSiteTable);
                    for (var i = 0; i < things.length; i++) {
                        siteTable.appendChild(things[i]);
                    }
                }

                var nextNavButtons = nextDoc.querySelector('.nav-buttons');
                var newNextHref = findNextLink(nextNavButtons);
                var ownNextLinkEl = navButtons.querySelector('.next-button a');
                if (newNextHref && ownNextLinkEl) {
                    ownNextLinkEl.setAttribute('href', newNextHref);
                } else if (ownNextLinkEl && ownNextLinkEl.parentNode) {
                    // No more pages after this one.
                    ownNextLinkEl.parentNode.parentNode.removeChild(ownNextLinkEl.parentNode);
                    exhausted = true;
                }

                pagesLoaded++;
                loading = false;
                if (!exhausted && nearBottom()) {
                    loadNextPage();
                }
            })
            .catch(function (err) {
                loading = false;
                logToNative('Never Ending Reddit: failed to load next page: ' + err);
            });
    }

    window.addEventListener('scroll', function () {
        if (nearBottom()) {
            loadNextPage();
        }
    }, {passive: true});
})();
