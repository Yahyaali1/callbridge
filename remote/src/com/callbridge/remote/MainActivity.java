package com.callbridge.remote;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.callbridge.common.Proto;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Phone B home screen: Keypad · Recents · Contacts · Messages. */
public class MainActivity extends Activity implements LinkService.Listener {
    static volatile String openConversation; // address of the conversation on screen, for notification suppression
    static JSONArray threads = new JSONArray(), contacts = new JSONArray(), recents = new JSONArray();

    SharedPreferences prefs;
    TextView status;
    FrameLayout content;
    final List<TextView> tabViews = new ArrayList<>();
    String tab = "keypad";

    // conversation screen state
    String convAddress, convName;
    long convId;
    JSONArray convItems = new JSONArray();
    BaseAdapter convAdapter;

    EditText keypadNumber;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("remote", MODE_PRIVATE);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.SURFACE);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);

        LinearLayout top = Ui.row(this);
        top.setPadding(Ui.dp(this, 20), Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 6));
        LinearLayout titles = Ui.col(this);
        titles.addView(Ui.text(this, "Bridge Phone", 22, Ui.TEXT, true));
        status = Ui.text(this, "", 13, Ui.MUTED, false);
        titles.addView(status);
        top.addView(titles, Ui.weight(1));
        TextView gear = Ui.text(this, "⚙", 24, Ui.MUTED, false);
        gear.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        gear.setOnClickListener(v -> showSettings(false));
        top.addView(gear);
        root.addView(top);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout tabs = Ui.row(this);
        tabs.setBackgroundColor(Ui.SURFACE);
        String[][] defs = {{"keypad", "⌨\nKeypad"}, {"recents", "🕘\nRecents"}, {"contacts", "👤\nContacts"}, {"messages", "💬\nMessages"}};
        for (String[] d : defs) {
            TextView t = Ui.text(this, d[1], 12, Ui.MUTED, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
            t.setTag(d[0]);
            t.setOnClickListener(v -> showTab((String) v.getTag()));
            tabViews.add(t);
            tabs.addView(t, Ui.weight(1));
        }
        root.addView(tabs);
        setContentView(root);

        LinkService.start(this);
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != 0) need.add(Manifest.permission.RECORD_AUDIO);
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != 0)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
        if (prefs.getString("code", "").isEmpty()) showSettings(true);

        showTab("keypad");
        handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handleIntent(i); }

    void handleIntent(Intent i) {
        String conv = i == null ? null : i.getStringExtra("conversation");
        if (conv != null) { showTab("messages"); openConversation(0, conv, ""); }
    }

    @Override protected void onResume() {
        super.onResume();
        LinkService.listeners.add(this);
        updateStatus();
        refreshTab();
        if (convAddress != null) openConversation = convAddress;
        JSONObject c = LinkService.call;
        if (c != null && "ringing".equals(c.optString("state"))) startActivity(new Intent(this, CallActivity.class));
    }

    @Override protected void onPause() {
        super.onPause();
        LinkService.listeners.remove(this);
        openConversation = null;
    }

    @Override public void onBackPressed() {
        if (convAddress != null) { closeConversation(); return; }
        super.onBackPressed();
    }

    LinkService svc() { return LinkService.instance; }

    boolean send(JSONObject m) {
        LinkService s = svc();
        return s != null && s.send(m);
    }

    // ------------------------------------------------------------------ events

    @Override public void onEvent(JSONObject m) {
        String t = m.optString("t");
        switch (t) {
            case "link": updateStatus(); if ("connected".equals(LinkService.linkState)) refreshTab(); break;
            case "welcome": refreshTab(); break;
            case "threads": threads = m.optJSONArray("items"); if (tab.equals("messages") && convAddress == null) showTab("messages"); break;
            case "contacts": contacts = m.optJSONArray("items"); if (tab.equals("contacts")) showTab("contacts"); break;
            case "recents": recents = m.optJSONArray("items"); if (tab.equals("recents")) showTab("recents"); break;
            case "thread":
                if (convAddress != null && (m.optLong("id") == convId && convId > 0 || Ui.sameNumber(m.optString("address"), convAddress))) {
                    convItems = reverse(m.optJSONArray("items"));
                    if (convId <= 0) convId = m.optLong("id");
                    if (convAdapter != null) convAdapter.notifyDataSetChanged();
                }
                break;
            case "sms":
                if (convAddress != null && Ui.sameNumber(m.optString("address"), convAddress)) requestThread();
                else if (tab.equals("messages")) send(Proto.m("threads"));
                break;
            case "sms_sent":
                if (!m.optBoolean("ok")) Toast.makeText(this, "Message failed: " + m.optString("error"), Toast.LENGTH_LONG).show();
                content.postDelayed(() -> { if (convAddress != null) requestThread(); }, 1200);
                break;
            case "ringing":
                startActivity(new Intent(this, CallActivity.class));
                break;
            case "ended":
                if (tab.equals("recents")) content.postDelayed(() -> send(Proto.m("recents")), 1500);
                break;
        }
    }

    void updateStatus() {
        String s = LinkService.linkState;
        boolean ok = "connected".equals(s);
        status.setText((ok ? "● " : "○ ") + (ok ? "Connected to " + LinkService.hostName : LinkService.linkDetail));
        status.setTextColor(ok ? Ui.GREEN : "auth_fail".equals(s) ? Ui.RED : Ui.MUTED);
    }

    void refreshTab() {
        switch (tab) {
            case "recents": send(Proto.m("recents")); break;
            case "contacts": if (contacts.length() == 0) send(Proto.m("contacts")); break;
            case "messages": if (convAddress != null) requestThread(); else send(Proto.m("threads")); break;
        }
    }

    // ------------------------------------------------------------------ tabs

    void showTab(String name) {
        boolean changed = !name.equals(tab);
        tab = name;
        for (TextView t : tabViews) t.setTextColor(name.equals(t.getTag()) ? Ui.ACCENT : Ui.MUTED);
        if (changed && convAddress != null) closeConversation();
        if (convAddress != null) return;
        content.removeAllViews();
        switch (name) {
            case "keypad": content.addView(keypad()); break;
            case "recents": content.addView(recentsView()); break;
            case "contacts": content.addView(contactsView()); break;
            case "messages": content.addView(threadsView()); break;
        }
        if (changed) refreshTab();
    }

    View keypad() {
        LinearLayout v = Ui.col(this);
        v.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        v.setPadding(0, 0, 0, Ui.dp(this, 16));
        String prev = keypadNumber == null ? "" : keypadNumber.getText().toString();
        keypadNumber = new EditText(this);
        keypadNumber.setShowSoftInputOnFocus(false);
        keypadNumber.setText(prev);
        keypadNumber.setTextSize(32);
        keypadNumber.setTextColor(Ui.TEXT);
        keypadNumber.setGravity(Gravity.CENTER);
        keypadNumber.setBackground(null);
        keypadNumber.setHint("Enter number");
        keypadNumber.setHintTextColor(Ui.MUTED);
        keypadNumber.setInputType(InputType.TYPE_CLASS_PHONE);
        v.addView(keypadNumber, Ui.matchWrap());

        String keys = "123456789*0#";
        for (int r = 0; r < 4; r++) {
            LinearLayout row = Ui.row(this);
            row.setGravity(Gravity.CENTER);
            for (int c = 0; c < 3; c++) {
                char k = keys.charAt(r * 3 + c);
                TextView key = Ui.circle(this, String.valueOf(k), Ui.SURFACE, Ui.TEXT, 28, 76);
                key.setOnClickListener(x -> insert(String.valueOf(k)));
                if (k == '0') key.setOnLongClickListener(x -> { insert("+"); return true; });
                row.addView(key);
            }
            v.addView(row);
        }
        LinearLayout bottom = Ui.row(this);
        bottom.setGravity(Gravity.CENTER);
        TextView sms = Ui.circle(this, "💬", Ui.BG, Ui.TEXT, 24, 76);
        sms.setOnClickListener(x -> {
            String n = keypadNumber.getText().toString().trim();
            if (!n.isEmpty()) { showTab("messages"); openConversation(0, n, ""); }
        });
        TextView call = Ui.circle(this, "📞", Ui.GREEN, Ui.TEXT, 28, 76);
        call.setOnClickListener(x -> dial(keypadNumber.getText().toString()));
        TextView del = Ui.circle(this, "⌫", Ui.BG, Ui.MUTED, 24, 76);
        del.setOnClickListener(x -> {
            int s = keypadNumber.getSelectionStart();
            Editable e = keypadNumber.getText();
            if (s > 0) e.delete(s - 1, s); else if (s < 0 && e.length() > 0) e.delete(e.length() - 1, e.length());
        });
        del.setOnLongClickListener(x -> { keypadNumber.setText(""); return true; });
        bottom.addView(sms);
        bottom.addView(call);
        bottom.addView(del);
        v.addView(bottom);
        return v;
    }

    void insert(String s) {
        int pos = Math.max(0, keypadNumber.getSelectionStart());
        keypadNumber.getText().insert(pos, s);
    }

    View recentsView() {
        ListView lv = list();
        lv.setAdapter(new JsonAdapter(() -> recents, (o, pos) -> {
            String name = o.optString("name");
            String num = o.optString("number");
            int type = o.optInt("type");
            String kind = type == 1 ? "↙ Incoming" : type == 2 ? "↗ Outgoing" : type == 3 ? "✕ Missed" : type == 5 ? "✕ Declined" : "Call";
            View r = Ui.listRow(this, name.isEmpty() ? num : name, kind + (name.isEmpty() ? "" : " · " + num), Ui.when(o.optLong("date")), false);
            if (type == 3) ((TextView) ((LinearLayout) ((LinearLayout) r).getChildAt(0)).getChildAt(0)).setTextColor(Ui.RED);
            return r;
        }));
        lv.setOnItemClickListener((p, v, pos, id) -> numberActions(recents.optJSONObject(pos)));
        return emptyWrap(lv, recents.length() == 0, "connected".equals(LinkService.linkState) ? "Loading…" : "Not connected");
    }

    JSONArray filteredContacts = new JSONArray();

    View contactsView() {
        LinearLayout v = Ui.col(this);
        EditText search = new EditText(this);
        search.setHint("Search contacts");
        search.setHintTextColor(Ui.MUTED);
        search.setTextColor(Ui.TEXT);
        search.setSingleLine(true);
        search.setBackground(Ui.round(Ui.SURFACE, Ui.dp(this, 24)));
        search.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 10));
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.setMargins(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 8));
        v.addView(search, lp);
        filteredContacts = contacts;
        ListView lv = list();
        JsonAdapter ad = new JsonAdapter(() -> filteredContacts, (o, pos) ->
                Ui.listRow(this, o.optString("name"), o.optString("number"), null, false));
        lv.setAdapter(ad);
        lv.setOnItemClickListener((p, x, pos, id) -> numberActions(filteredContacts.optJSONObject(pos)));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable e) {
                String q = e.toString().toLowerCase().trim();
                JSONArray out = new JSONArray();
                for (int i = 0; i < contacts.length(); i++) {
                    JSONObject o = contacts.optJSONObject(i);
                    if (q.isEmpty() || o.optString("name").toLowerCase().contains(q)
                            || o.optString("number").replaceAll("[^0-9+]", "").contains(q)) out.put(o);
                }
                filteredContacts = out;
                ad.notifyDataSetChanged();
            }
        });
        v.addView(emptyWrap(lv, contacts.length() == 0, "connected".equals(LinkService.linkState) ? "Loading…" : "Not connected"),
                new LinearLayout.LayoutParams(-1, 0, 1));
        return v;
    }

    View threadsView() {
        LinearLayout v = Ui.col(this);
        android.widget.Button compose = Ui.button(this, "✎  New message", Ui.ACCENT, Ui.BG);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.dp(this, 44));
        lp.setMargins(Ui.dp(this, 16), Ui.dp(this, 4), 0, Ui.dp(this, 8));
        compose.setOnClickListener(x -> askNumber());
        v.addView(compose, lp);
        ListView lv = list();
        lv.setAdapter(new JsonAdapter(() -> threads, (o, pos) -> {
            String name = o.optString("name");
            String body = (o.optInt("type") == 2 ? "You: " : "") + o.optString("body").replace('\n', ' ');
            return Ui.listRow(this, name.isEmpty() ? o.optString("address") : name, body, Ui.when(o.optLong("date")),
                    !o.optBoolean("read", true));
        }));
        lv.setOnItemClickListener((p, x, pos, id) -> {
            JSONObject o = threads.optJSONObject(pos);
            openConversation(o.optLong("id"), o.optString("address"), o.optString("name"));
        });
        v.addView(emptyWrap(lv, threads.length() == 0, "connected".equals(LinkService.linkState) ? "Loading…" : "Not connected"),
                new LinearLayout.LayoutParams(-1, 0, 1));
        return v;
    }

    // ------------------------------------------------------------------ conversation

    void openConversation(long id, String address, String name) {
        convId = id;
        convAddress = address;
        convName = name == null || name.isEmpty() ? address : name;
        convItems = new JSONArray();
        openConversation = address;
        content.removeAllViews();

        LinearLayout v = Ui.col(this);
        LinearLayout head = Ui.row(this);
        head.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), Ui.dp(this, 4));
        TextView back = Ui.text(this, "←", 24, Ui.TEXT, false);
        back.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6));
        back.setOnClickListener(x -> closeConversation());
        head.addView(back);
        head.addView(Ui.text(this, convName, 18, Ui.TEXT, true), Ui.weight(1));
        TextView call = Ui.text(this, "📞", 22, Ui.TEXT, false);
        call.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        call.setOnClickListener(x -> dial(convAddress));
        head.addView(call);
        v.addView(head);

        ListView lv = list();
        lv.setStackFromBottom(true);
        lv.setTranscriptMode(ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL);
        lv.setDivider(null);
        convAdapter = new JsonAdapter(() -> convItems, (o, pos) -> bubble(o));
        lv.setAdapter(convAdapter);
        v.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout input = Ui.row(this);
        input.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 10));
        EditText msg = new EditText(this);
        msg.setHint("Text message");
        msg.setHintTextColor(Ui.MUTED);
        msg.setTextColor(Ui.TEXT);
        msg.setMaxLines(5);
        msg.setBackground(Ui.round(Ui.SURFACE, Ui.dp(this, 22)));
        msg.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 10));
        input.addView(msg, Ui.weight(1));
        TextView sendBtn = Ui.circle(this, "➤", Ui.ACCENT, Ui.BG, 20, 48);
        sendBtn.setOnClickListener(x -> {
            String body = msg.getText().toString().trim();
            if (body.isEmpty()) return;
            if (!send(Proto.m("sms_send", "to", convAddress, "body", body))) {
                Toast.makeText(this, "Not connected to the host phone", Toast.LENGTH_SHORT).show();
                return;
            }
            convItems.put(Proto.m("x", "body", body, "date", System.currentTimeMillis(), "type", 4)); // 4 = sending
            convAdapter.notifyDataSetChanged();
            msg.setText("");
        });
        input.addView(sendBtn);
        v.addView(input);
        content.addView(v);
        requestThread();
    }

    void closeConversation() {
        convAddress = null;
        convAdapter = null;
        openConversation = null;
        String t = tab;
        tab = "";
        showTab(t.isEmpty() ? "messages" : t);
    }

    void requestThread() {
        send(Proto.m("thread", "id", convId, "address", convAddress));
    }

    View bubble(JSONObject o) {
        boolean mine = o.optInt("type") != 1;
        LinearLayout wrap = Ui.col(this);
        wrap.setPadding(Ui.dp(this, 12), Ui.dp(this, 3), Ui.dp(this, 12), Ui.dp(this, 3));
        wrap.setGravity(mine ? Gravity.END : Gravity.START);
        TextView t = Ui.text(this, o.optString("body"), 16, mine ? Ui.BG : Ui.TEXT, false);
        t.setBackground(Ui.round(mine ? Ui.ACCENT : Ui.SURFACE, Ui.dp(this, 18)));
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, 9), Ui.dp(this, 14), Ui.dp(this, 9));
        t.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.78));
        t.setTextIsSelectable(true);
        wrap.addView(t);
        String meta = o.optInt("type") == 4 ? "Sending…" : o.optInt("type") == 5 ? "Failed" : Ui.when(o.optLong("date"));
        TextView m = Ui.text(this, meta, 11, Ui.MUTED, false);
        m.setPadding(Ui.dp(this, 6), Ui.dp(this, 2), Ui.dp(this, 6), 0);
        wrap.addView(m);
        return wrap;
    }

    static JSONArray reverse(JSONArray a) {
        JSONArray r = new JSONArray();
        if (a == null) return r;
        for (int i = a.length() - 1; i >= 0; i--) r.put(a.opt(i));
        return r;
    }

    // ------------------------------------------------------------------ actions

    void dial(String number) {
        number = number == null ? "" : number.trim();
        if (number.isEmpty()) return;
        LinkService s = svc();
        if (s == null || !"connected".equals(LinkService.linkState)) {
            Toast.makeText(this, "Not connected to the host phone", Toast.LENGTH_SHORT).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != 0) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2);
            return;
        }
        s.dial(number);
        startActivity(new Intent(this, CallActivity.class));
    }

    void numberActions(JSONObject o) {
        if (o == null) return;
        String num = o.optString("number");
        String name = o.optString("name");
        new AlertDialog.Builder(this)
                .setTitle(name.isEmpty() ? num : name + "\n" + num)
                .setItems(new String[]{"📞  Call", "💬  Message"}, (d, which) -> {
                    if (which == 0) dial(num);
                    else { showTab("messages"); openConversation(0, num, name); }
                }).show();
    }

    void askNumber() {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_PHONE);
        e.setHint("Phone number");
        new AlertDialog.Builder(this).setTitle("New message").setView(pad(e))
                .setPositiveButton("Open", (d, w) -> {
                    String n = e.getText().toString().trim();
                    if (!n.isEmpty()) openConversation(0, n, "");
                }).setNegativeButton("Cancel", null).show();
    }

    void showSettings(boolean firstRun) {
        LinearLayout v = Ui.col(this);
        EditText code = new EditText(this);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setHint("Pairing code (shown on the host phone)");
        code.setText(prefs.getString("code", ""));
        v.addView(code);
        EditText host = new EditText(this);
        host.setHint("Host phone address (leave empty = automatic)");
        host.setText(prefs.getString("host", ""));
        Spinner method = new Spinner(this);
        String[] methods = {"auto", "A", "B", "C"};
        method.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Call audio method: Auto", "Method A (interception API)", "Method B (call-assistant)", "Method C (telephony device)"}));
        method.setSelection(java.util.Arrays.asList(methods).indexOf(prefs.getString("method", "auto")));
        CheckBox mute = new CheckBox(this);
        mute.setText("Mute the host phone's own microphone during calls");
        mute.setChecked(prefs.getBoolean("muteMic", false));
        if (!firstRun) {
            v.addView(host);
            v.addView(method);
            v.addView(mute);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (android.os.Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) {
                android.widget.Button fs = new android.widget.Button(this);
                fs.setText("Allow full-screen incoming calls");
                fs.setOnClickListener(x -> startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:" + getPackageName()))));
                v.addView(fs);
            }
            android.widget.Button bat = new android.widget.Button(this);
            bat.setText("Keep connected in background");
            bat.setOnClickListener(x -> startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()))));
            v.addView(bat);
        }
        new AlertDialog.Builder(this)
                .setTitle(firstRun ? "Pair with the host phone" : "Settings")
                .setMessage(firstRun ? "Connect this phone to the host phone's hotspot, then enter the 6-digit code shown in the Call Bridge app on the host phone." : null)
                .setView(pad(v))
                .setCancelable(!firstRun)
                .setPositiveButton("Save", (d, w) -> {
                    boolean reconnect = !code.getText().toString().trim().equals(prefs.getString("code", ""))
                            || !host.getText().toString().trim().equals(prefs.getString("host", ""));
                    prefs.edit().putString("code", code.getText().toString().trim())
                            .putString("host", host.getText().toString().trim())
                            .putString("method", methods[Math.max(0, method.getSelectedItemPosition())])
                            .putBoolean("muteMic", mute.isChecked()).apply();
                    LinkService s = svc();
                    if (s != null) { if (reconnect) s.reconnect(); else s.pushSettings(); }
                })
                .show();
    }

    // ------------------------------------------------------------------ list helpers

    ListView list() {
        ListView lv = new ListView(this);
        lv.setDivider(null);
        lv.setSelector(android.R.color.transparent);
        return lv;
    }

    View emptyWrap(ListView lv, boolean empty, String msg) {
        FrameLayout f = new FrameLayout(this);
        f.addView(lv);
        if (empty) {
            TextView t = Ui.text(this, msg, 15, Ui.MUTED, false);
            t.setGravity(Gravity.CENTER);
            f.addView(t, new FrameLayout.LayoutParams(-1, -1));
        }
        return f;
    }

    View pad(View v) {
        FrameLayout f = new FrameLayout(this);
        int p = Ui.dp(this, 20);
        f.setPadding(p, Ui.dp(this, 8), p, 0);
        f.addView(v);
        return f;
    }

    interface Source { JSONArray get(); }
    interface Binder { View bind(JSONObject o, int pos); }

    static class JsonAdapter extends BaseAdapter {
        final Source src; final Binder binder;
        JsonAdapter(Source s, Binder b) { src = s; binder = b; }
        @Override public int getCount() { JSONArray a = src.get(); return a == null ? 0 : a.length(); }
        @Override public Object getItem(int i) { return src.get().optJSONObject(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View convert, ViewGroup parent) { return binder.bind(src.get().optJSONObject(i), i); }
    }
}
