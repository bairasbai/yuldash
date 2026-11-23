package com.yuldash.qa.taxidraft;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Read one live frame without waiting for Compose animations to become idle, in our own process. */
public final class WindowObserver extends Instrumentation {
    private static final Pattern CTA = Pattern.compile("(?:Заказать на [0-9]{1,2}:[0-9]{2}|[0-9]{1,2}:[0-9]{2}-ға заказ итеү)");
    private final Set<String> allowed = new HashSet<>();
    private int visited;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        try {
            byte[] encoded = Base64.decode(args.getString("labels"), Base64.DEFAULT);
            JSONArray names = new JSONArray(new String(encoded, StandardCharsets.UTF_8));
            for (int i = 0; i < names.length(); i++) allowed.add(names.getString(i));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Missing QA label allowlist");
        }
        start();
    }

    private String safeLabel(CharSequence raw) {
        if (raw == null) return "";
        String text = raw.toString();
        if (allowed.contains(text) || CTA.matcher(text).matches()) return text;
        // Compose can merge an icon description or text with another label in the same control.
        for (String part : text.split("[\\n,]+")) {
            String item = part.trim();
            if (allowed.contains(item) || CTA.matcher(item).matches()) return item;
        }
        return "";
    }

    private void collect(AccessibilityNodeInfo node, JSONArray rows, int depth) throws JSONException {
        if (depth > 80 || visited >= 1500) return;
        visited++;
        if (node.isVisibleToUser()) {
            String text = safeLabel(node.getText());
            String description = safeLabel(node.getContentDescription());
            String resource = "android:id/button1".equals(node.getViewIdResourceName()) ? "android:id/button1" : "";
            String type = String.valueOf(node.getClassName());
            if (!text.isEmpty() || !description.isEmpty() || !resource.isEmpty() || node.isClickable()
                    || node.isScrollable() || node.isCheckable() || type.equals("android.widget.EditText")) {
                Rect box = new Rect();
                node.getBoundsInScreen(box);
                if (!box.isEmpty()) rows.put(new JSONObject().put("text", text).put("content-desc", description)
                        .put("class", type.startsWith("android.") ? type : "")
                        .put("resource-id", resource).put("bounds", box.toShortString())
                        .put("clickable", Boolean.toString(node.isClickable()))
                        .put("checkable", Boolean.toString(node.isCheckable()))
                        .put("scrollable", Boolean.toString(node.isScrollable())));
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { collect(child, rows, depth + 1); } finally { child.recycle(); }
        }
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            UiAutomation automation = getUiAutomation();
            AccessibilityServiceInfo options = automation.getServiceInfo();
            options.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            automation.setServiceInfo(options);
            AccessibilityNodeInfo root = null;
            for (int i = 0; i < 30 && root == null; i++) {
                root = automation.getRootInActiveWindow();
                if (root == null) SystemClock.sleep(70);
            }
            JSONArray rows = new JSONArray();
            if (root == null) {
                result.putString("observation", "null-root");
            } else {
                try {
                    String owner = String.valueOf(root.getPackageName());
                    if (owner.equals("com.yuldash.app") || owner.equals("android")) {
                        collect(root, rows, 0);
                        result.putString("observation", "app-window-" + visited);
                    } else result.putString("observation", "other-window");
                } finally { root.recycle(); }
            }
            byte[] bytes = rows.toString().getBytes(StandardCharsets.UTF_8);
            result.putString("window_nodes", Base64.encodeToString(bytes, Base64.NO_WRAP));
            finish(Activity.RESULT_OK, result);
        } catch (Exception failure) {
            result.putString("observation", "read-error-" + failure.getClass().getSimpleName());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
