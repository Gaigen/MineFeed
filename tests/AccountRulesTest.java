import com.feed.client.account.TagRules;
import com.feed.client.account.Versions;

public class AccountRulesTest {
    static int fails = 0;

    static void eq(Object a, Object b, String what) {
        if (!a.equals(b)) {
            fails++;
            System.out.println("FAIL " + what + ": " + a + " != " + b);
        }
    }

    public static void main(String[] args) {
        eq(TagRules.normalize("  @Steve_42 "), "steve_42", "normalize");
        eq(TagRules.check("steve_42"), TagRules.Problem.NONE, "ok tag");
        eq(TagRules.check("a"), TagRules.Problem.LENGTH, "short");
        eq(TagRules.check("a".repeat(33)), TagRules.Problem.LENGTH, "long");
        eq(TagRules.check("ab-c"), TagRules.Problem.CHARS, "dash");
        eq(TagRules.check("абв"), TagRules.Problem.CHARS, "cyrillic");
        eq(TagRules.check("a..b"), TagRules.Problem.DOUBLE_DOT, "double dot");
        eq(TagRules.check("admin"), TagRules.Problem.RESERVED, "reserved");
        eq(TagRules.check("minefeed_bot"), TagRules.Problem.RESERVED, "reserved prefix");
        eq(TagRules.check("admin2"), TagRules.Problem.RESERVED, "admin prefix");
        eq(TagRules.suggestFrom("Steve-42"), "steve42", "suggest strips");
        eq(TagRules.suggestFrom("Notch"), "", "suggest reserved");
        eq(TagRules.suggestFrom("X"), "", "suggest short");
        eq(TagRules.cleanNick("  §aBob\u200b   the \n builder "), "Bob the builder", "clean nick");
        eq(TagRules.validNick(""), false, "empty nick");
        eq(TagRules.validNick("😀".repeat(32)), true, "emoji nick 32 code points");
        eq(TagRules.validNick("x".repeat(33)), false, "nick 33");

        eq(Versions.compare("0.2.0", "0.2.0"), 0, "same");
        eq(Versions.compare("0.1.0", "0.2.0") < 0, true, "older");
        eq(Versions.compare("0.10.0", "0.9.9") > 0, true, "numeric");
        eq(Versions.compare("0.2", "0.2.0"), 0, "short form");
        eq(Versions.compare("0.2.1-beta", "0.2.1"), 0, "suffix ignored");
        eq(Versions.compare("1.0.0+mc1.21.1", "0.9.0") > 0, true, "build meta");

        if (fails > 0) {
            System.out.println(fails + " failures");
            System.exit(1);
        }
        System.out.println("AccountRulesTest OK");
    }
}
