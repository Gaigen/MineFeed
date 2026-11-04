import com.feed.client.MessageTemplate;
import java.util.Map;

public final class MessageTemplateTest {
    private static void equal(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    public static void main(String[] args) {
        Map<String, String> values = Map.of("author", "Alex", "message", "hello {author}", "post", "build");
        equal("Alex: hello {author} / build", MessageTemplate.render("{author}: {message} / {post}", values));
        equal("{unknown} { broken", MessageTemplate.render("{unknown} { broken", values));
        equal("Alex Alex", MessageTemplate.render("{author} {author}", values));
        equal("", MessageTemplate.render(null, values));
        equal("→ «build»", MessageTemplate.render("→ «{post}»", values));
        System.out.println("MessageTemplate tests passed");
    }
}
