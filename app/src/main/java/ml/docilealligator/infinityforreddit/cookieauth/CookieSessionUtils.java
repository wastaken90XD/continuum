package ml.docilealligator.infinityforreddit.cookieauth;

import androidx.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * Shared helpers for telling a real, logged-in Reddit session cookie apart from an anonymous one.
 * Extracted out of {@link CookieLoginActivity} so every consumer of the cookie-session path (that
 * activity, and {@code RedditWebActivity}'s own cookie bookkeeping) agrees on the same definition
 * instead of drifting into subtly different checks over time.
 */
public final class CookieSessionUtils {
    private CookieSessionUtils() {
    }

    /**
     * Whether {@code cookieHeader} (the full {@code "name1=value1; name2=value2; ..."} string from
     * the cookie jar) shows real, logged-in evidence rather than just an anonymous visit.
     */
    public static boolean looksLikeRealSession(@Nullable String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            return false;
        }
        if (cookieHeader.contains("reddit_session=")) {
            // Never minted for a purely anonymous visit, unlike token_v2 -- sufficient on its own.
            return true;
        }
        String tokenV2 = extractCookieValue(cookieHeader, "token_v2");
        return tokenV2 != null && !isAnonymousJwt(tokenV2);
    }

    @Nullable
    public static String extractCookieValue(String cookieHeader, String name) {
        String prefix = name + "=";
        for (String part : cookieHeader.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length());
            }
        }
        return null;
    }

    /**
     * Reddit's {@code token_v2} cookie is a JWT with a {@code sub} claim of either {@code "loid"}
     * (anonymous visitor id -- minted even when logged out) or {@code "user"} (a real signed-in
     * session). If the token can't be decoded at all, errs toward "not confirmed yet" (true is
     * returned) rather than treating an unreadable token as proof of login.
     */
    public static boolean isAnonymousJwt(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                return true;
            }
            String payload = parts[1];
            int remainder = payload.length() % 4;
            if (remainder != 0) {
                payload = payload + "====".substring(remainder);
            }
            byte[] decoded = android.util.Base64.decode(payload,
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP);
            JSONObject json = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
            return "loid".equals(json.optString("sub", "loid"));
        } catch (Exception e) {
            return true;
        }
    }
}
