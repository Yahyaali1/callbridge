package com.callbridge.host;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Telephony;

import com.callbridge.common.Proto;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Read-only queries against the host phone's SMS, contacts and call log. */
final class Data {
    private static final Map<String, String> nameCache = new HashMap<>();

    static String nameFor(Context ctx, String number) {
        if (number == null || number.isEmpty()) return "";
        synchronized (nameCache) {
            if (nameCache.containsKey(number)) return nameCache.get(number);
        }
        String name = "";
        try (Cursor c = ctx.getContentResolver().query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {}
        synchronized (nameCache) { nameCache.put(number, name); }
        return name;
    }

    static JSONObject threads(Context ctx) {
        JSONArray arr = new JSONArray();
        Set<Long> seen = new HashSet<>();
        try (Cursor c = ctx.getContentResolver().query(Telephony.Sms.CONTENT_URI,
                new String[]{"thread_id", "address", "body", "date", "type", "read"}, null, null, "date DESC")) {
            while (c != null && c.moveToNext() && seen.size() < 80) {
                long tid = c.getLong(0);
                if (!seen.add(tid)) continue;
                String addr = c.getString(1);
                arr.put(new JSONObject().put("id", tid).put("address", addr).put("name", nameFor(ctx, addr))
                        .put("body", c.getString(2)).put("date", c.getLong(3))
                        .put("type", c.getInt(4)).put("read", c.getInt(5) == 1));
            }
        } catch (Exception e) {
            return Proto.m("threads", "error", e.getMessage(), "items", arr);
        }
        return Proto.m("threads", "items", arr);
    }

    static JSONObject thread(Context ctx, long id, String address) {
        JSONArray arr = new JSONArray();
        String sel; String[] args;
        if (id > 0) { sel = "thread_id = ?"; args = new String[]{String.valueOf(id)}; }
        else { sel = "address = ?"; args = new String[]{address}; }
        try (Cursor c = ctx.getContentResolver().query(Telephony.Sms.CONTENT_URI,
                new String[]{"body", "date", "type", "address"}, sel, args, "date DESC")) {
            int n = 0;
            while (c != null && c.moveToNext() && n++ < 300) {
                if (address == null || address.isEmpty()) address = c.getString(3);
                arr.put(new JSONObject().put("body", c.getString(0)).put("date", c.getLong(1)).put("type", c.getInt(2)));
            }
        } catch (Exception e) {
            return Proto.m("thread", "error", e.getMessage(), "items", arr);
        }
        return Proto.m("thread", "id", id, "address", address,
                "name", nameFor(ctx, address), "items", arr); // newest first
    }

    static JSONObject contacts(Context ctx) {
        JSONArray arr = new JSONArray();
        Set<String> seen = new HashSet<>();
        try (Cursor c = ctx.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER},
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC")) {
            while (c != null && c.moveToNext()) {
                String name = c.getString(0), num = c.getString(1);
                if (num == null || !seen.add(name + "|" + num.replaceAll("[^0-9+]", ""))) continue;
                arr.put(new JSONObject().put("name", name).put("number", num));
            }
        } catch (Exception e) {
            return Proto.m("contacts", "error", e.getMessage(), "items", arr);
        }
        return Proto.m("contacts", "items", arr);
    }

    static JSONObject recents(Context ctx) {
        JSONArray arr = new JSONArray();
        try (Cursor c = ctx.getContentResolver().query(CallLog.Calls.CONTENT_URI,
                new String[]{CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.CACHED_NAME},
                null, null, CallLog.Calls.DATE + " DESC")) {
            int n = 0;
            while (c != null && c.moveToNext() && n++ < 150) {
                String num = c.getString(0);
                String name = c.getString(4);
                arr.put(new JSONObject().put("number", num).put("type", c.getInt(1)).put("date", c.getLong(2))
                        .put("duration", c.getLong(3)).put("name", name != null ? name : nameFor(ctx, num)));
            }
        } catch (Exception e) {
            return Proto.m("recents", "error", e.getMessage(), "items", arr);
        }
        return Proto.m("recents", "items", arr);
    }
}
