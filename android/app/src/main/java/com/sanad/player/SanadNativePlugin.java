package com.sanad.player;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.util.DisplayMetrics;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;

@CapacitorPlugin(name = "SanadNative")
public class SanadNativePlugin extends Plugin {
  static SanadNativePlugin inst;

  @Override public void load() { inst = this; }

  static void emit(String ev, JSObject data) { if (inst != null) inst.notifyListeners(ev, data); }

  @PluginMethod
  public void info(PluginCall call) {
    DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
    JSObject o = new JSObject();
    o.put("w", Math.max(dm.widthPixels, dm.heightPixels));
    o.put("h", Math.min(dm.widthPixels, dm.heightPixels));
    o.put("model", android.os.Build.MODEL);
    o.put("sdk", android.os.Build.VERSION.SDK_INT);
    call.resolve(o);
  }

  @PluginMethod
  public void play(PluginCall call) {
    Intent i = new Intent(getContext(), PlayerActivity.class);
    i.putExtra("opts", call.getData().toString());
    startActivityForResult(call, i, "playResult");
  }

  @ActivityCallback
  private void playResult(PluginCall call, ActivityResult result) {
    if (call == null) return;
    JSObject o = new JSObject();
    Intent d = result.getData();
    if (d != null) {
      o.put("pos", d.getLongExtra("pos", 0) / 1000.0);
      o.put("dur", d.getLongExtra("dur", 0) / 1000.0);
      o.put("ended", d.getBooleanExtra("ended", false));
      o.put("key", d.getStringExtra("key"));
      o.put("index", d.getIntExtra("index", 0));
      o.put("enh", d.getIntExtra("enh", 0));
    }
    call.resolve(o);
  }

  @PluginMethod
  public void addSubtitle(PluginCall call) {
    String text = call.getString("text", "");
    String key = call.getString("key", "");
    String label = call.getString("label", "عربي");
    try {
      File f = new File(getContext().getCacheDir(), "sub_" + System.currentTimeMillis() + ".srt");
      try (FileOutputStream os = new FileOutputStream(f)) { os.write(text.getBytes(StandardCharsets.UTF_8)); }
      PlayerActivity a = PlayerActivity.current;
      if (a != null) a.runOnUiThread(() -> a.addExternalSub(f, label, key));
      call.resolve();
    } catch (Exception e) {
      call.reject(e.getMessage());
    }
  }

  // ---------------- downloads (Android DownloadManager → Movies/Sanad Player/<folder>) ----------------
  static String safe(String s) { return s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|]", "_").trim(); }

  @PluginMethod
  public void download(PluginCall call) {
    try {
      String url = call.getString("url");
      String title = safe(call.getString("title", "video"));
      String folder = safe(call.getString("folder", ""));
      String ext = call.getString("ext", "mp4");
      String rel = "Sanad Player/" + (folder.isEmpty() ? "" : folder + "/") + title + "." + ext;
      DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url))
          .setTitle(title)
          .setDescription("Sanad Player")
          .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
          .setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, rel)
          .addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")
          .setAllowedOverMetered(true)
          .setAllowedOverRoaming(true);
      DownloadManager dm = (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
      long id = dm.enqueue(r);
      JSObject o = new JSObject();
      o.put("id", String.valueOf(id));
      o.put("file", rel);
      call.resolve(o);
    } catch (Exception e) {
      call.reject(e.getMessage());
    }
  }

  @PluginMethod
  public void dlStatus(PluginCall call) {
    JSArray out = new JSArray();
    try {
      JSONArray ids = call.getArray("ids");
      DownloadManager dm = (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
      for (int i = 0; ids != null && i < ids.length(); i++) {
        long id = Long.parseLong(ids.getString(i));
        JSObject o = new JSObject();
        o.put("id", String.valueOf(id));
        try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
          if (c != null && c.moveToFirst()) {
            int st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            o.put("status", st == DownloadManager.STATUS_SUCCESSFUL ? "done" : st == DownloadManager.STATUS_FAILED ? "err" : st == DownloadManager.STATUS_PAUSED ? "paused" : "run");
            o.put("got", c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)));
            o.put("total", c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)));
            o.put("reason", c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)));
            Uri u = dm.getUriForDownloadedFile(id);
            if (u != null) o.put("uri", u.toString());
          } else {
            o.put("status", "gone");
          }
        }
        out.put(o);
      }
    } catch (Exception e) {
      call.reject(e.getMessage());
      return;
    }
    JSObject r = new JSObject();
    r.put("list", out);
    call.resolve(r);
  }

  @PluginMethod
  public void dlRemove(PluginCall call) {
    try {
      DownloadManager dm = (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
      dm.remove(Long.parseLong(call.getString("id", "0")));
      call.resolve();
    } catch (Exception e) {
      call.reject(e.getMessage());
    }
  }
}
