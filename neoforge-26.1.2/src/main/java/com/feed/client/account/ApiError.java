package com.feed.client.account;

import com.google.gson.JsonObject;

/** Error from the MineFeed v2 API: {@code {error, code, ...}}. status 0 = network problem. */
public class ApiError extends Exception {

    public final int status;
    public final String code;
    public final JsonObject extra;

    public ApiError(int status, String code, String message, JsonObject extra) {
        super(message);
        this.status = status;
        this.code = code == null ? "" : code;
        this.extra = extra == null ? new JsonObject() : extra;
    }

    public ApiError(Throwable cause) {
        super(cause == null ? "network" : cause.getClass().getSimpleName(), cause);
        this.status = 0;
        this.code = "network";
        this.extra = new JsonObject();
    }

    public boolean isNetwork() {
        return status == 0;
    }

    /** Key revoked or unknown: the local key must be dropped. */
    public boolean isBadKey() {
        return status == 401;
    }

    public Long longExtra(String name) {
        try {
            return extra.has(name) && !extra.get(name).isJsonNull() ? extra.get(name).getAsLong() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
