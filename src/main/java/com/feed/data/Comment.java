package com.feed.data;

public class Comment {
    public String id;
    public String author;
    public String text;
    public String createdAt;
    public boolean fromMod; // true = posted via mod (Discord embed)
}
