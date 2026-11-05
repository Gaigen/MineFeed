import com.feed.client.screen.TagText;

import java.util.List;

/** Hashtag/mention parsing must match the server (server/src/hashtags.js). */
public class TagTextTest {
    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    private static String kinds(String line) {
        StringBuilder sb = new StringBuilder();
        for (TagText.Seg s : TagText.segments(line)) {
            if (s.tag()) {
                sb.append(s.kind() == TagText.Kind.HASHTAG ? "#" : s.kind() == TagText.Kind.COORDS ? "~" : "@").append('[').append(s.text()).append(']');
            }
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        check(kinds("Убил дракона! #GG #Эндер, @alex_demo.").equals("#[#GG]#[#Эндер]@[@alex_demo.]"), "basic: " + kinds("Убил дракона! #GG #Эндер, @alex_demo."));
        check(kinds("C# и a#b и #1 и #x и https://x.ru/#frag и &#39;").isEmpty(), "not tags: " + kinds("C# и a#b и #1 и #x и https://x.ru/#frag и &#39;"));
        check(kinds("#" + "a".repeat(33)).isEmpty(), "too long");
        check("елка".equals(TagText.normalizeHashtag("#Ёлка")), "normalize");
        check(TagText.normalizeHashtag("#12") == null, "digits only");
        List<TagText.Seg> segs = TagText.segments("a #b_c d");
        check(segs.size() == 3 && segs.get(0).text().equals("a ") && segs.get(2).text().equals(" d"), "segments");
        check(kinds("база тут [120 64 -300] и ад [-5 70 12 nether]").equals("~[[120 64 -300]]~[[-5 70 12 nether]]"),
                "coords: " + kinds("база тут [120 64 -300] и ад [-5 70 12 nether]"));
        check(kinds("[1 2] [a b c] [1 2 3 moon]").isEmpty(), "not coords: " + kinds("[1 2] [a b c] [1 2 3 moon]"));
        check(TagText.coords(1, -2, 3, "minecraft:the_end").equals("[1 -2 3 end]"), "coords format");
        check(TagText.coords(1, 2, 3, "minecraft:overworld").equals("[1 2 3]"), "coords overworld");
        System.out.println("TagTextTest OK");
    }
}
