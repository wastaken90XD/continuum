package ml.docilealligator.infinityforreddit.cookieauth;

import androidx.annotation.Nullable;

/**
 * In-memory cache of the most recently seen Reddit {@code modhash} -- the CSRF token every
 * listing response carries under {@code data.modhash} and every write request (vote, save,
 * comment, submit, subscribe) needs, either as the {@code X-Modhash} header or the {@code uh} form
 * field. {@link RedditCookieInterceptor} populates this from ordinary GET traffic and attaches it
 * to outgoing POSTs automatically, so individual call sites never need to manage it themselves.
 *
 * <p>Process-lifetime only, by design: a modhash is cheap to re-obtain (it rides along on the very
 * next listing fetch) and is tied to the current cookie session, not worth persisting past a
 * process restart.
 */
final class ModhashHolder {
    @Nullable
    private static volatile String modhash;

    private ModhashHolder() {
    }

    @Nullable
    static String get() {
        return modhash;
    }

    static void set(String value) {
        modhash = value;
    }
}
