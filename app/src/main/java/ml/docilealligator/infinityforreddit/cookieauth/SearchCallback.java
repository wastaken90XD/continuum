package ml.docilealligator.infinityforreddit.cookieauth;

import org.json.JSONArray;

/** Shared result contract for every {@link SearchBackend}. */
public interface SearchCallback {
    /** Normalized {@code {"url":..., "title":..., "description":...}} results. */
    void onResults(JSONArray results);

    /** A captcha challenge was hit; {@code captchaUrl} is where the user can solve it. */
    void onCaptchaRequired(String captchaUrl);

    void onError(Exception e);
}
