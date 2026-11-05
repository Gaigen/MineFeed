package com.feed.data;

import java.util.List;

public class FeedResponse {
    public List<Post> posts;
    public String nextCursor;
    /** Pinned posts of the space (first page of the plain feed only), else null. */
    public List<Post> pinned;

    public FeedResponse() {
    }
}
