package com.feed.data;

public class Comment {
    public String id;
    public String author;
    public String text;
    public String createdAt;
    public boolean fromMod; // true = MineFeed account, false = written in Discord
    public String authorId; // v2 account id
    public String authorTag;
    public String avatar;
    public String imageUrl;
    public int imageW;
    public int imageH;
    public boolean edited;
    /** The picture stays covered until clicked. */
    public boolean imageSpoiler;
}
