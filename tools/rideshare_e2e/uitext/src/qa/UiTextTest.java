package qa;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import android.view.accessibility.AccessibilityNodeInfo;
/** QA-only Unicode input for the rideshare acceptance stand: sets text of the focused
 *  (or N-th) EditText through the accessibility SET_TEXT action. Runs in the shell
 *  uiautomator process, so the app process is NOT restarted (unlike am instrument). */
public class UiTextTest extends UiAutomatorTestCase {
  Object call(Object o, String name) throws Exception {
    Class<?> c = o.getClass();
    while (c != null) {
      try { java.lang.reflect.Method m = c.getDeclaredMethod(name); m.setAccessible(true); return m.invoke(o); }
      catch (NoSuchMethodException e) { c = c.getSuperclass(); }
    }
    throw new Exception(name);
  }
  void edits(AccessibilityNodeInfo n, java.util.List<AccessibilityNodeInfo> out) {
    if (n == null) return;
    if ("android.widget.EditText".contentEquals(n.getClassName())) out.add(n);
    for (int i = 0; i < n.getChildCount(); i++) edits(n.getChild(i), out);
  }
  public void testType() throws Exception {
    String text = new String(android.util.Base64.decode(getParams().getString("value"), 0), "UTF-8");
    Object bridge = call(getUiDevice(), "getAutomatorBridge");
    Object controller = call(bridge, "getQueryController");
    AccessibilityNodeInfo root = (AccessibilityNodeInfo) call(controller, "getAccessibilityRootNode");
    java.util.List<AccessibilityNodeInfo> fields = new java.util.ArrayList<>();
    edits(root, fields);
    String idx = getParams().getString("index");
    AccessibilityNodeInfo field = idx == null ? root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) : fields.get(Integer.parseInt(idx));
    assertNotNull("Focused input", field);
    android.os.Bundle args = new android.os.Bundle();
    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
    assertTrue("Accessibility text input", field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args));
    System.out.println("QA_TEXT_SET fields=" + fields.size());
  }
}
