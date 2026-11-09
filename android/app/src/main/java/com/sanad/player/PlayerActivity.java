package com.sanad.player;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import androidx.annotation.OptIn;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.TrackSelectionOverride;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.BufferedReader;
import java.io.FileReader;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;
import com.getcapacitor.JSObject;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends Activity {
  static PlayerActivity current;

  ExoPlayer player;
  PlayerView view;
  final EnhanceEffect.Params ep = new EnhanceEffect.Params();
  final Handler ui = new Handler(Looper.getMainLooper());
  TextView titleTv, subTv, badgeTv, enhBtn, cmpBtn, errTv, aspBtn;
  LinearLayout topBar;
  ListView chList;
  JSONObject opts;
  boolean live;
  String key = "", title = "";
  JSONArray chans;
  int chIndex = 0;
  File extSub;
  String extSubLabel = "عربي";
  boolean askedSubs = false, ended = false, controllerShown = true;
  int retries = 0;
  int aspectMode = 0;
  final String[] ENH = {"✨ تلقائي", "⚪ إيقاف", "✨ عادي", "✨ قوي"};
  final String[] ASP = {"ملاءمة", "تعبئة", "تكبير"};

  int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

  TextView btn(String t) {
    TextView b = new TextView(this);
    b.setText(t);
    b.setTextColor(Color.WHITE);
    b.setTextSize(14);
    b.setPadding(dp(12), dp(7), dp(12), dp(7));
    GradientDrawable g = new GradientDrawable();
    g.setColor(Color.argb(150, 30, 34, 44));
    g.setCornerRadius(dp(18));
    g.setStroke(1, Color.argb(60, 255, 255, 255));
    b.setBackground(g);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.setMarginStart(dp(6));
    b.setLayoutParams(lp);
    b.setClickable(true);
    b.setFocusable(true);
    return b;
  }

  @SuppressLint("SetTextI18n")
  @Override
  protected void onCreate(Bundle s) {
    super.onCreate(s);
    current = this;
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    immersive();
    try { opts = new JSONObject(getIntent().getStringExtra("opts")); } catch (Exception e) { opts = new JSONObject(); }
    live = opts.optBoolean("live", false);
    chans = opts.optJSONArray("list");
    chIndex = opts.optInt("index", 0);
    ep.mode = opts.optInt("enh", 0);
    ep.year = opts.optInt("year", 0);
    DisplayMetrics dm = getResources().getDisplayMetrics();
    ep.targetH = Math.min(Math.max(Math.min(dm.widthPixels, dm.heightPixels), 720), 2160);

    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(Color.BLACK);
    view = new PlayerView(this);
    view.setKeepScreenOn(true);
    view.setShowSubtitleButton(true);
    view.setControllerShowTimeoutMs(3500);
    view.setShowNextButton(false);
    view.setShowPreviousButton(false);
    view.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
    root.addView(view, new FrameLayout.LayoutParams(-1, -1));
    SubtitleView sv = view.getSubtitleView();
    if (sv != null) {
      sv.setApplyEmbeddedStyles(false);
      sv.setApplyEmbeddedFontSizes(false);
      sv.setStyle(new CaptionStyleCompat(Color.WHITE, Color.argb(110, 0, 0, 0), Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, Typeface.DEFAULT_BOLD));
      sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * (float) opts.optDouble("subScale", 1.25));
      sv.setBottomPaddingFraction(0.06f);
    }

    // ---- top bar ----
    topBar = new LinearLayout(this);
    topBar.setOrientation(LinearLayout.HORIZONTAL);
    topBar.setGravity(Gravity.CENTER_VERTICAL);
    topBar.setPadding(dp(14), dp(10), dp(14), dp(10));
    topBar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {Color.argb(200, 0, 0, 0), Color.TRANSPARENT});
    topBar.setBackground(shade);
    TextView back = btn("✕");
    back.setOnClickListener(v -> finishWithResult());
    topBar.addView(back);
    LinearLayout tcol = new LinearLayout(this);
    tcol.setOrientation(LinearLayout.VERTICAL);
    tcol.setPadding(dp(10), 0, dp(10), 0);
    titleTv = new TextView(this);
    titleTv.setTextColor(Color.WHITE);
    titleTv.setTextSize(17);
    titleTv.setTypeface(Typeface.DEFAULT_BOLD);
    titleTv.setSingleLine(true);
    titleTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
    subTv = new TextView(this);
    subTv.setTextColor(Color.argb(200, 255, 255, 255));
    subTv.setTextSize(12.5f);
    subTv.setSingleLine(true);
    badgeTv = new TextView(this);
    badgeTv.setTextColor(Color.rgb(196, 181, 253));
    badgeTv.setTextSize(12);
    badgeTv.setSingleLine(true);
    tcol.addView(titleTv);
    tcol.addView(subTv);
    tcol.addView(badgeTv);
    topBar.addView(tcol, new LinearLayout.LayoutParams(0, -2, 1f));
    enhBtn = btn(ENH[ep.mode]);
    enhBtn.setOnClickListener(v -> {
      ep.mode = (ep.mode + 1) % 4;
      enhBtn.setText(ENH[ep.mode]);
      SanadNativePlugin.emit("enhMode", new JSObject().put("mode", ep.mode));
      updateBadge();
    });
    topBar.addView(enhBtn);
    cmpBtn = btn("⇔ قبل/بعد");
    cmpBtn.setOnClickListener(v -> {
      ep.compare = !ep.compare;
      cmpBtn.setAlpha(ep.compare ? 1f : 0.75f);
      cmpBtn.setText(ep.compare ? "⇔ يسار: الأصل" : "⇔ قبل/بعد");
    });
    cmpBtn.setAlpha(0.75f);
    topBar.addView(cmpBtn);
    aspBtn = btn("⛶ " + ASP[0]);
    aspBtn.setOnClickListener(v -> {
      aspectMode = (aspectMode + 1) % 3;
      view.setResizeMode(aspectMode == 0 ? AspectRatioFrameLayout.RESIZE_MODE_FIT : aspectMode == 1 ? AspectRatioFrameLayout.RESIZE_MODE_FILL : AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
      aspBtn.setText("⛶ " + ASP[aspectMode]);
    });
    topBar.addView(aspBtn);
    if (live && chans != null && chans.length() > 1) {
      TextView up = btn("▲"), dn = btn("▼"), lst = btn("☰ القنوات");
      up.setOnClickListener(v -> zap(-1));
      dn.setOnClickListener(v -> zap(1));
      lst.setOnClickListener(v -> toggleList());
      topBar.addView(up);
      topBar.addView(dn);
      topBar.addView(lst);
    }
    FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
    root.addView(topBar, tlp);

    // ---- channel list ----
    if (live && chans != null && chans.length() > 1) {
      chList = new ListView(this);
      chList.setBackgroundColor(Color.argb(225, 12, 14, 18));
      chList.setDivider(null);
      List<String> names = new ArrayList<>();
      for (int i = 0; i < chans.length(); i++) {
        JSONObject c = chans.optJSONObject(i);
        String now = c != null ? c.optString("e", "") : "";
        names.add((i + 1) + "  " + (c != null ? c.optString("n", "") : "") + (now.isEmpty() ? "" : "\n     " + now));
      }
      chList.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, names) {
        @Override public View getView(int pos, View cv, ViewGroup parent) {
          TextView t = (TextView) super.getView(pos, cv, parent);
          t.setTextColor(pos == chIndex ? Color.rgb(196, 181, 253) : Color.WHITE);
          t.setTextSize(14);
          t.setTextDirection(View.TEXT_DIRECTION_RTL);
          return t;
        }
      });
      chList.setOnItemClickListener((AdapterView<?> a, View v, int pos, long id) -> { chIndex = pos; startChannel(); toggleList(); });
      chList.setVisibility(View.GONE);
      FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(dp(300), -1, Gravity.END);
      root.addView(chList, llp);
    }

    errTv = new TextView(this);
    errTv.setTextColor(Color.WHITE);
    errTv.setTextSize(15);
    errTv.setGravity(Gravity.CENTER);
    errTv.setPadding(dp(24), dp(16), dp(24), dp(16));
    GradientDrawable eg = new GradientDrawable();
    eg.setColor(Color.argb(200, 20, 20, 24));
    eg.setCornerRadius(dp(14));
    errTv.setBackground(eg);
    errTv.setVisibility(View.GONE);
    errTv.setOnClickListener(v -> { errTv.setVisibility(View.GONE); retries = 0; if (live) startChannel(); else startVod(player.getCurrentPosition()); });
    root.addView(errTv, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
    mtTv = new TextView(this);
    mtTv.setTextColor(Color.WHITE);
    mtTv.setTypeface(Typeface.DEFAULT_BOLD);
    mtTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, (float) (19 * opts.optDouble("subScale", 1.25)));
    mtTv.setShadowLayer(6f, 0f, 0f, Color.BLACK);
    mtTv.setGravity(Gravity.CENTER);
    mtTv.setTextDirection(View.TEXT_DIRECTION_RTL);
    mtTv.setPadding(dp(12), dp(4), dp(12), dp(4));
    mtTv.setBackgroundColor(Color.argb(90, 0, 0, 0));
    mtTv.setVisibility(View.GONE);
    FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    mlp.bottomMargin = dp(26);
    mlp.leftMargin = dp(40); mlp.rightMargin = dp(40);
    root.addView(mtTv, mlp);
    setContentView(root);

    view.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> {
      controllerShown = visibility == View.VISIBLE;
      topBar.setVisibility(controllerShown ? View.VISIBLE : View.GONE);
      if (!controllerShown) immersive();
    });

    buildPlayer();
    if (live) startChannel(); else startVod(opts.optLong("start", 0));
    ui.postDelayed(badgeTick, 1000);
  }

  final Runnable badgeTick = new Runnable() { public void run() { updateBadge(); ui.postDelayed(this, 1000); } };

  void buildPlayer() {
    DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
        .setUserAgent("Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36")
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15000)
        .setReadTimeoutMs(20000);
    DefaultDataSource.Factory ds = new DefaultDataSource.Factory(this, http);
    DefaultExtractorsFactory ex = new DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true);
    DefaultRenderersFactory rf = new DefaultRenderersFactory(this)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true);
    DefaultLoadControl lc = new DefaultLoadControl.Builder().setBufferDurationsMs(15000, 60000, 1500, 3000).build();
    player = new ExoPlayer.Builder(this, rf)
        .setMediaSourceFactory(new DefaultMediaSourceFactory(ds, ex))
        .setLoadControl(lc)
        .build();
    List<Effect> fx = new ArrayList<>();
    fx.add(new EnhanceEffect(ep));
    try { player.setVideoEffects(fx); } catch (Throwable t) { /* effects unsupported: play plain */ }
    TrackSelectionParameters.Builder tp = player.getTrackSelectionParameters().buildUpon()
        .setPreferredTextLanguage(opts.optString("prefSub", "ar"));
    String pa = opts.optString("prefAudio", "");
    if (!pa.isEmpty()) tp.setPreferredAudioLanguage(pa);
    player.setTrackSelectionParameters(tp.build());
    view.setPlayer(player);
    player.addListener(new Player.Listener() {
      @Override public void onPlaybackStateChanged(int st) {
        if (st == Player.STATE_READY) { retries = 0; errTv.setVisibility(View.GONE); }
        if (st == Player.STATE_ENDED && !live) { ended = true; finishWithResult(); }
      }
      @Override public void onPlayerError(PlaybackException e) {
        if (retries < 2) {
          retries++;
          long pos = player.getCurrentPosition();
          ui.postDelayed(() -> { if (live) startChannel(); else startVod(pos); }, 1200);
          return;
        }
        errTv.setText("تعذّر التشغيل\n" + e.getErrorCodeName().replace("ERROR_CODE_", "") + "\n\nاضغط لإعادة المحاولة");
        errTv.setVisibility(View.VISIBLE);
      }
      @Override public void onTracksChanged(Tracks tracks) { checkSubs(tracks); }
      @Override public void onVideoSizeChanged(VideoSize vs) { updateBadge(); }
      @Override public void onCues(CueGroup cg) {
        if (!mtOn) return;
        StringBuilder b = new StringBuilder();
        for (Cue c : cg.cues) if (c.text != null) { if (b.length() > 0) b.append('\n'); b.append(c.text.toString()); }
        showCue(b.toString());
      }
    });
  }

  MediaItem item(String url) {
    MediaItem.Builder b = new MediaItem.Builder().setUri(Uri.parse(url));
    String u = url.toLowerCase();
    if (u.contains(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8);
    if (extSub != null && !live) {
      b.setSubtitleConfigurations(Collections.singletonList(
          new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(extSub))
              .setMimeType(MimeTypes.APPLICATION_SUBRIP)
              .setLanguage(extSubLang)
              .setLabel(extSubLabel)
              .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
              .build()));
    }
    return b.build();
  }

  void startVod(long posMs) {
    key = opts.optString("key", "");
    title = opts.optString("title", "");
    titleTv.setText(title);
    subTv.setText(opts.optString("sub", ""));
    player.setMediaItem(item(opts.optString("url")), Math.max(0, posMs));
    player.prepare();
    player.setPlayWhenReady(true);
  }

  void startChannel() {
    String url = opts.optString("url");
    String name = opts.optString("title", "");
    String now = "";
    if (chans != null && chIndex >= 0 && chIndex < chans.length()) {
      JSONObject c = chans.optJSONObject(chIndex);
      if (c != null) { url = c.optString("u", url); name = c.optString("n", name); now = c.optString("e", ""); key = c.optString("k", key); }
    }
    title = name;
    titleTv.setText((chans != null ? (chIndex + 1) + "  " : "") + name);
    subTv.setText(now);
    player.setMediaItem(item(url));
    player.prepare();
    player.setPlayWhenReady(true);
    if (chList != null) ((ArrayAdapter<?>) chList.getAdapter()).notifyDataSetChanged();
    view.showController();
  }

  void zap(int d) {
    if (chans == null || chans.length() == 0) return;
    chIndex = (chIndex + d + chans.length()) % chans.length();
    retries = 0;
    startChannel();
  }

  void toggleList() {
    if (chList == null) return;
    boolean show = chList.getVisibility() != View.VISIBLE;
    chList.setVisibility(show ? View.VISIBLE : View.GONE);
    if (show) { chList.setSelection(Math.max(0, chIndex - 3)); view.hideController(); }
  }

  void checkSubs(Tracks tracks) {
    if (live || askedSubs || extSub != null) return;
    boolean hasVideo = false, hasAr = false;
    for (Tracks.Group g : tracks.getGroups()) {
      if (g.getType() == C.TRACK_TYPE_VIDEO) hasVideo = true;
      if (g.getType() == C.TRACK_TYPE_TEXT) {
        for (int i = 0; i < g.length; i++) {
          Format f = g.getTrackFormat(i);
          String l = f.language == null ? "" : f.language.toLowerCase();
          if (l.startsWith("ar") || l.equals("ara")) hasAr = true;
        }
      }
    }
    if (!hasVideo) return;
    askedSubs = true;
    boolean hasOther = false;
    for (Tracks.Group g : tracks.getGroups()) if (g.getType() == C.TRACK_TYPE_TEXT && g.length > 0) hasOther = true;
    if (!hasAr) SanadNativePlugin.emit("needSubs", new JSObject().put("key", opts.optString("key", "")).put("hasOther", hasOther));
    else badgeExtra = "💬 ترجمة عربية مدمجة";
  }

  String badgeExtra = "";
  // ---- on-device machine translation of subtitles (Google ML Kit) ----
  Translator translator;
  boolean mtOn = false, mtReady = false;
  String mtFrom = "en", curCue = "";
  TextView mtTv;
  final Map<String, String> mtCache = new HashMap<>();
  String extSubLang = "ar";

  static String norm(String t) { return t == null ? "" : t.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim(); }

  static String mlLang(String l) {
    if (l == null) return TranslateLanguage.ENGLISH;
    l = l.toLowerCase();
    if (l.contains("-")) l = l.substring(0, l.indexOf('-'));
    if (l.contains("_")) l = l.substring(0, l.indexOf('_'));
    switch (l) {
      case "eng": l = "en"; break; case "fre": case "fra": l = "fr"; break; case "spa": l = "es"; break; case "tur": l = "tr"; break;
      case "ger": case "deu": l = "de"; break; case "ita": l = "it"; break; case "por": l = "pt"; break; case "rus": l = "ru"; break;
      case "hin": l = "hi"; break; case "jpn": l = "ja"; break; case "kor": l = "ko"; break; case "chi": case "zho": l = "zh"; break;
      case "per": case "fas": l = "fa"; break; case "urd": l = "ur"; break; case "dut": case "nld": l = "nl"; break; case "ind": l = "id"; break;
    }
    String t = TranslateLanguage.fromLanguageTag(l);
    return t == null ? TranslateLanguage.ENGLISH : t;
  }

  void enableMt(String lang) {
    if (mtOn && translator != null && mlLang(lang).equals(mtFrom)) return;
    mtOn = true; mtReady = false;
    mtFrom = mlLang(lang);
    if (translator != null) translator.close();
    translator = Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage(mtFrom).setTargetLanguage(TranslateLanguage.ARABIC).build());
    badgeExtra = "🤖 تنزيل محرك الترجمة العربي (مرة وحدة)…";
    if (view.getSubtitleView() != null) view.getSubtitleView().setVisibility(View.GONE);
    mtTv.setVisibility(View.VISIBLE);
    translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
        .addOnSuccessListener(v -> { mtReady = true; badgeExtra = "🤖 ترجمة عربية آلية"; showCue(curCue); if (extSub != null) pretranslate(extSub); })
        .addOnFailureListener(e -> { badgeExtra = "تعذّر تنزيل محرك الترجمة — تأكد من الإنترنت"; mtOn = false; mtTv.setVisibility(View.GONE); if (view.getSubtitleView() != null) view.getSubtitleView().setVisibility(View.VISIBLE); });
  }

  void showCue(String text) {
    curCue = text;
    if (!mtOn) return;
    final String k = norm(text);
    if (k.isEmpty()) { mtTv.setText(""); mtTv.setVisibility(View.INVISIBLE); return; }
    String hit = mtCache.get(k);
    if (hit != null) { mtTv.setText(hit); mtTv.setVisibility(View.VISIBLE); return; }
    if (!mtReady || translator == null) return;
    translator.translate(k).addOnSuccessListener(r -> { mtCache.put(k, r); if (k.equals(norm(curCue))) { mtTv.setText(r); mtTv.setVisibility(View.VISIBLE); } });
  }

  /** translate a whole SRT file ahead of time so cues appear instantly */
  void pretranslate(File f) {
    final List<String> lines = new ArrayList<>();
    try (BufferedReader br = new BufferedReader(new FileReader(f))) {
      StringBuilder blk = new StringBuilder(); String ln; boolean inText = false;
      Pattern tp = Pattern.compile("-->");
      while ((ln = br.readLine()) != null) {
        if (ln.trim().isEmpty()) { if (blk.length() > 0) lines.add(norm(blk.toString())); blk.setLength(0); inText = false; continue; }
        if (tp.matcher(ln).find()) { inText = true; continue; }
        if (inText) blk.append(ln).append('\n');
      }
      if (blk.length() > 0) lines.add(norm(blk.toString()));
    } catch (Exception e) { return; }
    pretranslateFrom(lines, 0);
  }
  void pretranslateFrom(List<String> lines, int i) {
    if (isFinishing() || translator == null || i >= lines.size()) return;
    String k = lines.get(i);
    if (k.isEmpty() || mtCache.containsKey(k)) { pretranslateFrom(lines, i + 1); return; }
    translator.translate(k).addOnSuccessListener(r -> { mtCache.put(k, r); if (k.equals(norm(curCue))) { mtTv.setText(r); mtTv.setVisibility(View.VISIBLE); } pretranslateFrom(lines, i + 1); })
        .addOnFailureListener(e -> pretranslateFrom(lines, i + 1));
  }

  /** called by the plugin: translate the embedded (non-Arabic) subtitle track */
  void useMt() {
    if (player == null || live) return;
    Tracks.Group best = null; String bestLang = null;
    for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
      if (g.getType() != C.TRACK_TYPE_TEXT) continue;
      String l = g.getTrackFormat(0).language == null ? "und" : g.getTrackFormat(0).language.toLowerCase();
      if (best == null || l.startsWith("en")) { best = g; bestLang = l; }
    }
    if (best == null) return;
    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
        .setOverrideForType(new TrackSelectionOverride(best.getMediaTrackGroup(), 0)).build());
    enableMt(bestLang);
  }

  /** called by the plugin when the web layer found an external Arabic subtitle */
  void addExternalSub(File f, String label, String forKey, String lang, boolean translate) {
    if (live || !opts.optString("key", "").equals(forKey) || player == null) return;
    extSub = f;
    extSubLang = translate ? (lang == null || lang.isEmpty() ? "en" : lang) : "ar";
    if (label != null && !label.isEmpty()) extSubLabel = label;
    long pos = player.getCurrentPosition();
    boolean pwr = player.getPlayWhenReady();
    player.setMediaItem(item(opts.optString("url")), pos);
    player.prepare();
    player.setPlayWhenReady(pwr);
    badgeExtra = "💬 ترجمة عربية: " + extSubLabel;
    if (!"ar".equals(extSubLang)) {
      player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setPreferredTextLanguage(extSubLang).build());
      enableMt(extSubLang);
    }
  }

  @SuppressLint("SetTextI18n")
  void updateBadge() {
    if (badgeTv == null) return;
    StringBuilder b = new StringBuilder();
    if (ep.inH > 0) {
      b.append(ep.inH).append("p");
      if (ep.mode != 1 && ep.outH > ep.inH) b.append(" → ").append(ep.outH).append("p");
    }
    if (ep.mode == 1) b.append("  · بدون تحسين");
    else {
      b.append("  · ✨ ").append(ep.mode == 0 ? "تلقائي" : ep.mode == 2 ? "عادي" : "قوي");
      if (ep.oldFilm) b.append(" · 🎞 ترميم فيلم قديم");
      else {
        if (ep.faded) b.append(" · إصلاح ألوان باهتة");
        if (ep.noisy) b.append(" · إزالة تشويش");
        if (ep.dark) b.append(" · تفتيح");
      }
    }
    if (!badgeExtra.isEmpty()) b.append("  · ").append(badgeExtra);
    badgeTv.setText(b.toString());
  }

  void immersive() {
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
    c.hide(WindowInsetsCompat.Type.systemBars());
    c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
  }

  @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }

  @Override public boolean onKeyDown(int code, KeyEvent e) {
    if (live && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_CHANNEL_UP)) { zap(-1); return true; }
    if (live && (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_CHANNEL_DOWN)) { zap(1); return true; }
    return super.onKeyDown(code, e);
  }

  @SuppressWarnings("deprecation")
  @Override public void onBackPressed() {
    if (chList != null && chList.getVisibility() == View.VISIBLE) { toggleList(); return; }
    finishWithResult();
  }

  void finishWithResult() {
    Intent r = new Intent();
    if (player != null) {
      r.putExtra("pos", player.getCurrentPosition());
      long d = player.getDuration();
      r.putExtra("dur", d == C.TIME_UNSET ? 0 : d);
    }
    r.putExtra("ended", ended);
    r.putExtra("key", key);
    r.putExtra("index", chIndex);
    r.putExtra("enh", ep.mode);
    setResult(RESULT_OK, r);
    finish();
  }

  @Override protected void onPause() { super.onPause(); if (player != null) player.pause(); }
  @Override protected void onResume() { super.onResume(); immersive(); }

  @Override protected void onDestroy() {
    ui.removeCallbacksAndMessages(null);
    if (current == this) current = null;
    if (translator != null) { translator.close(); translator = null; }
    if (player != null) { player.release(); player = null; }
    super.onDestroy();
  }
}
