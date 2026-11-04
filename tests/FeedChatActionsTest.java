import com.feed.client.FeedChatActions;

public final class FeedChatActionsTest {
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        check(FeedChatActions.validPostId("1234567890123456789"));
        check(!FeedChatActions.validPostId("123/command"));
        check(!FeedChatActions.validPostId("1 2"));
        check(!FeedChatActions.validPostId("1".repeat(26)));
        var open = FeedChatActions.parse("open 12345");
        check(open != null && open.kind().equals("open") && open.postId().equals("12345"));
        var reply = FeedChatActions.parse("reply 12345 Hello world!");
        check(reply != null && reply.text().equals("Hello world!"));
        check(FeedChatActions.parse("reply 12345") == null);
        check(FeedChatActions.parse("open 12345 extra") == null);
        check(FeedChatActions.parse("reply 1 " + "a".repeat(301)) == null);
        check(FeedChatActions.parse("reply 12/x test") == null);
        System.out.println("FeedChatActions tests passed");
    }
}
