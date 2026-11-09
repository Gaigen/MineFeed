import com.feed.client.NotificationOrder;
import java.util.ArrayList;
import java.util.List;

public final class NotificationOrderTest {
    private static void check(boolean b) { if (!b) throw new AssertionError(); }
    public static void main(String[] args) {
        check(NotificationOrder.initialCursor(null).equals("0"));
        check(NotificationOrder.initialCursor("").equals("0"));
        check(NotificationOrder.initialCursor("123").equals("123"));
        check(NotificationOrder.compare("100", "99") > 0);
        check(NotificationOrder.compare("99999999999999999999", "100000000000000000000") < 0);
        var ids = new ArrayList<>(List.of("103", "101", "102"));
        ids.sort(NotificationOrder::compare);
        String cursor = "100";
        int received = 0;
        for (String id : ids) if (NotificationOrder.compare(id, cursor) > 0) { received++; cursor = id; }
        check(received == 3 && cursor.equals("103"));
        check(NotificationOrder.compare("1", NotificationOrder.initialCursor(null)) > 0);
        System.out.println("NotificationOrder tests passed");
    }
}
