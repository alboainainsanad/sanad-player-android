package com.sanad.player;

import android.content.Context;
import android.opengl.GLES20;
import androidx.annotation.OptIn;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Real-time GPU picture enhancement for playback (runs on the phone GPU inside ExoPlayer's effect pipeline).
 * One pass at output resolution: Catmull-Rom upscaling + edge-preserving (bilateral) denoise / deblock +
 * contrast-adaptive sharpening + automatic levels / gamma / saturation restoration.
 * Every ~1s a tiny statistics pass (32x18) is read back to drive the automatic mode (faded/dark/noisy detection).
 */
@OptIn(markerClass = UnstableApi.class)
public final class EnhanceEffect implements GlEffect {

  /** Shared, live-tweakable parameters (read every frame). */
  public static final class Params {
    public volatile int mode = 0;          // 0 auto, 1 off, 2 normal, 3 strong
    public volatile boolean compare = false;
    public volatile int targetH = 1080;    // screen height in px
    public volatile int year = 0;          // release year if known
    // current applied values (smoothed), exposed for the on-screen badge
    public volatile float denoise, sharp, sat = 1f, lo = 0f, hi = 1f, gamma = 1f;
    public volatile boolean oldFilm = false, faded = false, dark = false, noisy = false;
    public volatile int inW, inH, outW, outH;
    public volatile float noiseLevel = 0f;
  }

  public final Params p;
  public EnhanceEffect(Params p) { this.p = p; }

  @Override
  public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr) throws VideoFrameProcessingException {
    return new Program(p);
  }

  static final String VS =
      "attribute vec2 aPos;\n" +
      "varying vec2 vUv;\n" +
      "void main(){ gl_Position = vec4(aPos,0.0,1.0); vUv = aPos*0.5+0.5; }\n";

  static final String FS =
      "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n" +
      "uniform sampler2D uTex;\n" +
      "uniform vec2 uIn;\n" +
      "uniform vec2 uOut;\n" +
      "uniform vec4 uA;\n" +   // x denoise, y sharp, z sat, w mode(0 off,1 on)
      "uniform vec4 uB;\n" +   // x lo, y hi, z gamma, w split (<0 none)
      "varying vec2 vUv;\n" +
      "vec3 T(vec2 u){ return texture2D(uTex,u).rgb; }\n" +
      "vec3 catmull(vec2 uv){\n" +
      "  vec2 sp = uv*uIn; vec2 t1 = floor(sp-0.5)+0.5; vec2 f = sp-t1;\n" +
      "  vec2 w0 = f*(-0.5+f*(1.0-0.5*f)); vec2 w1 = 1.0+f*f*(-2.5+1.5*f);\n" +
      "  vec2 w2 = f*(0.5+f*(2.0-1.5*f)); vec2 w3 = f*f*(-0.5+0.5*f);\n" +
      "  vec2 w12 = w1+w2; vec2 o12 = w2/w12;\n" +
      "  vec2 p0 = (t1-1.0)/uIn; vec2 p3 = (t1+2.0)/uIn; vec2 p12 = (t1+o12)/uIn;\n" +
      "  vec3 r = T(vec2(p0.x,p0.y))*w0.x*w0.y + T(vec2(p12.x,p0.y))*w12.x*w0.y + T(vec2(p3.x,p0.y))*w3.x*w0.y;\n" +
      "  r += T(vec2(p0.x,p12.y))*w0.x*w12.y + T(vec2(p12.x,p12.y))*w12.x*w12.y + T(vec2(p3.x,p12.y))*w3.x*w12.y;\n" +
      "  r += T(vec2(p0.x,p3.y))*w0.x*w3.y + T(vec2(p12.x,p3.y))*w12.x*w3.y + T(vec2(p3.x,p3.y))*w3.x*w3.y;\n" +
      "  return r;\n" +
      "}\n" +
      "void main(){\n" +
      "  vec2 uv = vUv;\n" +
      "  if(uA.w < 0.5 || (uB.w > 0.0 && uv.x < uB.w)){\n" +
      "    vec3 o = T(uv);\n" +
      "    if(uB.w > 0.0 && abs(uv.x-uB.w) < 1.5/uOut.x) o = vec3(1.0);\n" +
      "    gl_FragColor = vec4(o,1.0); return;\n" +
      "  }\n" +
      "  vec3 base = clamp(catmull(uv),0.0,1.0);\n" +
      // bilateral 3x3 at source texel spacing
      "  vec2 px = 1.0/uIn; vec3 c0 = T(uv); vec3 acc = c0; float ws = 1.0; float mx = 0.0;\n" +
      "  float sig = 0.025 + 0.09*uA.x; float k = 1.0/(2.0*sig*sig);\n" +
      "  for(int j=-1;j<=1;j++){ for(int i=-1;i<=1;i++){\n" +
      "    if(i==0 && j==0) continue;\n" +
      "    vec3 c = T(uv+vec2(float(i),float(j))*px); vec3 d = c-c0; float dd = dot(d,d);\n" +
      "    mx = max(mx, dd);\n" +
      "    float w = exp(-dd*k) * ((i==0||j==0)?1.0:0.7); acc += c*w; ws += w;\n" +
      "  }}\n" +
      "  vec3 den = acc/ws;\n" +
      "  float flt = 1.0 - smoothstep(0.0015, 0.03, mx);\n" +
      "  float dn = uA.x*(0.35+0.65*flt);\n" +
      "  vec3 res = mix(base, den + (base-c0)*(1.0-flt), dn);\n" +
      // contrast adaptive sharpening (AMD CAS style)
      "  vec2 d = max(1.0/uOut, 0.6/uIn);\n" +
      "  vec3 n = T(uv+vec2(0.0,-d.y)), s = T(uv+vec2(0.0,d.y)), e = T(uv+vec2(d.x,0.0)), w = T(uv+vec2(-d.x,0.0));\n" +
      "  vec3 mn = min(base,min(min(n,s),min(e,w))); vec3 mxc = max(base,max(max(n,s),max(e,w)));\n" +
      "  vec3 amp = sqrt(clamp(min(mn,1.0-mxc)/max(mxc,vec3(1e-4)),0.0,1.0));\n" +
      "  vec3 wgt = amp*(-1.0/mix(8.0,4.5,uA.y));\n" +
      "  vec3 sh = (base + (n+s+e+w)*wgt)/(1.0+4.0*wgt);\n" +
      "  res += (sh-base)*uA.y*1.15*(1.0-0.6*flt*uA.x);\n" +
      // levels, gamma, saturation
      "  res = clamp((res-uB.x)/max(uB.y-uB.x,0.2),0.0,1.0);\n" +
      "  res = pow(res, vec3(uB.z));\n" +
      "  float l = dot(res, vec3(0.299,0.587,0.114));\n" +
      "  res = clamp(mix(vec3(l),res,uA.z),0.0,1.0);\n" +
      "  gl_FragColor = vec4(res,1.0);\n" +
      "}\n";

  // statistics shader: rgb = colour at the sample, a = local high-frequency energy (noise proxy)
  static final String FS_STATS =
      "precision mediump float;\n" +
      "uniform sampler2D uTex; uniform vec2 uIn; varying vec2 vUv;\n" +
      "void main(){ vec2 px=1.0/uIn; vec3 c=texture2D(uTex,vUv).rgb;\n" +
      " vec3 a=(texture2D(uTex,vUv+vec2(px.x,0.0)).rgb+texture2D(uTex,vUv-vec2(px.x,0.0)).rgb+texture2D(uTex,vUv+vec2(0.0,px.y)).rgb+texture2D(uTex,vUv-vec2(0.0,px.y)).rgb)*0.25;\n" +
      " float hf=abs(dot(c-a,vec3(0.299,0.587,0.114)));\n" +
      " gl_FragColor=vec4(c, clamp(hf*8.0,0.0,1.0)); }\n";

  static final class Program extends BaseGlShaderProgram {
    final Params p;
    int prog, progStats, aPos, aPosS;
    int uTex, uIn, uOut, uA, uB, uTexS, uInS;
    final FloatBuffer quad;
    int statsTex = 0, statsFbo = 0, frameN = 0;
    static final int SW = 32, SH = 18;
    final ByteBuffer statsBuf = ByteBuffer.allocateDirect(SW * SH * 4).order(ByteOrder.nativeOrder());
    // auto targets (EMA smoothed into p.*)
    float tDen = 0.3f, tSharp = 0.5f, tSat = 1f, tLo = 0f, tHi = 1f, tGamma = 1f;
    boolean firstStats = true;

    Program(Params p) throws VideoFrameProcessingException {
      super(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 1);
      this.p = p;
      float[] v = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
      quad = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
      quad.put(v).position(0);
      try {
        prog = link(VS, FS);
        progStats = link(VS, FS_STATS);
      } catch (RuntimeException e) {
        throw new VideoFrameProcessingException(e);
      }
      aPos = GLES20.glGetAttribLocation(prog, "aPos");
      uTex = GLES20.glGetUniformLocation(prog, "uTex");
      uIn = GLES20.glGetUniformLocation(prog, "uIn");
      uOut = GLES20.glGetUniformLocation(prog, "uOut");
      uA = GLES20.glGetUniformLocation(prog, "uA");
      uB = GLES20.glGetUniformLocation(prog, "uB");
      aPosS = GLES20.glGetAttribLocation(progStats, "aPos");
      uTexS = GLES20.glGetUniformLocation(progStats, "uTex");
      uInS = GLES20.glGetUniformLocation(progStats, "uIn");
    }

    static int compile(int type, String src) {
      int s = GLES20.glCreateShader(type);
      GLES20.glShaderSource(s, src);
      GLES20.glCompileShader(s);
      int[] ok = new int[1];
      GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
      if (ok[0] == 0) {
        String log = GLES20.glGetShaderInfoLog(s);
        GLES20.glDeleteShader(s);
        throw new RuntimeException("shader compile: " + log);
      }
      return s;
    }

    static int link(String vs, String fs) {
      int v = compile(GLES20.GL_VERTEX_SHADER, vs), f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
      int pr = GLES20.glCreateProgram();
      GLES20.glAttachShader(pr, v);
      GLES20.glAttachShader(pr, f);
      GLES20.glLinkProgram(pr);
      int[] ok = new int[1];
      GLES20.glGetProgramiv(pr, GLES20.GL_LINK_STATUS, ok, 0);
      if (ok[0] == 0) throw new RuntimeException("link: " + GLES20.glGetProgramInfoLog(pr));
      return pr;
    }

    @Override
    public Size configure(int inputWidth, int inputHeight) {
      int th = Math.max(360, Math.min(2160, p.targetH));
      int outH = inputHeight >= th ? Math.min(inputHeight, 2160) : th;
      int outW = Math.round(inputWidth * (outH / (float) inputHeight) / 2f) * 2;
      if ((long) outW * outH > 3840L * 2160L) { outW = inputWidth; outH = inputHeight; }
      p.inW = inputWidth; p.inH = inputHeight; p.outW = outW; p.outH = outH;
      firstStats = true;
      chooseBase();
      return new Size(outW, outH);
    }

    void chooseBase() {
      int h = p.inH;
      float den = h <= 480 ? 0.55f : h <= 720 ? 0.38f : h <= 1080 ? 0.22f : 0.12f;
      float sh = h <= 480 ? 0.85f : h <= 720 ? 0.7f : h <= 1080 ? 0.5f : 0.3f;
      if (p.mode == 2) { den *= 0.8f; sh *= 0.85f; }
      if (p.mode == 3) { den = Math.min(1f, den * 1.5f + 0.1f); sh = Math.min(1f, sh * 1.25f); }
      tDen = den; tSharp = sh;
      if (p.year > 0 && p.year < 1995) p.oldFilm = true;
    }

    void bindQuad(int attr) {
      quad.position(0);
      GLES20.glEnableVertexAttribArray(attr);
      GLES20.glVertexAttribPointer(attr, 2, GLES20.GL_FLOAT, false, 0, quad);
    }

    void runStats(int inputTexId) {
      int[] fb = new int[1];
      int[] vp = new int[4];
      GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, fb, 0);
      GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, vp, 0);
      if (statsFbo == 0) {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        statsTex = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, statsTex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, SW, SH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        int[] f = new int[1];
        GLES20.glGenFramebuffers(1, f, 0);
        statsFbo = f[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, statsFbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, statsTex, 0);
      }
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, statsFbo);
      GLES20.glViewport(0, 0, SW, SH);
      GLES20.glUseProgram(progStats);
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId);
      GLES20.glUniform1i(uTexS, 0);
      GLES20.glUniform2f(uInS, p.inW, p.inH);
      bindQuad(aPosS);
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
      statsBuf.position(0);
      GLES20.glReadPixels(0, 0, SW, SH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, statsBuf);
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb[0]);
      GLES20.glViewport(vp[0], vp[1], vp[2], vp[3]);
      analyze();
    }

    void analyze() {
      int n = SW * SH;
      int[] hist = new int[256];
      float sumL = 0, sumS = 0, nz = 0;
      int nzN = 0;
      for (int i = 0; i < n; i++) {
        int r = statsBuf.get(i * 4) & 255, g = statsBuf.get(i * 4 + 1) & 255, b = statsBuf.get(i * 4 + 2) & 255, a = statsBuf.get(i * 4 + 3) & 255;
        int l = (int) (0.299f * r + 0.587f * g + 0.114f * b);
        hist[Math.min(255, l)]++;
        sumL += l;
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        sumS += mx > 0 ? (mx - mn) / (float) mx : 0;
        if (a < 60) { nz += a; nzN++; }   // ignore real edges, keep flat-ish texture energy
      }
      float meanL = sumL / n / 255f, meanS = sumS / n;
      int acc = 0, p2 = 0, p98 = 255;
      for (int i = 0; i < 256; i++) { acc += hist[i]; if (acc >= n * 0.02f) { p2 = i; break; } }
      acc = 0;
      for (int i = 255; i >= 0; i--) { acc += hist[i]; if (acc >= n * 0.02f) { p98 = i; break; } }
      float lo = p2 / 255f, hi = p98 / 255f;
      float noise = nzN > 0 ? nz / nzN / 255f : 0f;   // 0..~0.25
      p.noiseLevel = noise;
      if (meanL < 0.04f) return;   // black/fade frame: keep previous
      boolean faded = (hi - lo) < 0.78f && meanL > 0.08f;
      boolean dark = meanL < 0.22f;
      boolean gray = meanS < 0.04f;
      boolean noisy = noise > 0.06f;
      p.faded = faded; p.dark = dark; p.noisy = noisy;
      if (p.mode == 0) p.oldFilm = (p.year > 0 && p.year < 1995) || (faded && (noisy || p.inH <= 576)) || (gray && p.inH <= 720);
      // targets
      if (p.mode == 0 || p.mode == 3) {
        float k = p.mode == 3 ? 0.85f : 0.65f;
        tLo = faded || p.oldFilm ? lo * k : lo * 0.3f;
        tHi = faded || p.oldFilm ? 1f - (1f - hi) * k : 1f - (1f - hi) * 0.3f;
        if (tHi - tLo < 0.62f) { float m = (tHi + tLo) / 2f; tLo = m - 0.31f; tHi = m + 0.31f; }
        tGamma = dark ? 0.86f : meanL > 0.72f ? 1.06f : 1f;
        tSat = gray ? 1f : meanS < 0.18f ? (p.oldFilm ? 1.28f : 1.18f) : meanS < 0.3f ? 1.08f : 1.02f;
        chooseBase();
        if (noisy || p.oldFilm) tDen = Math.min(1f, tDen + 0.25f + Math.min(0.3f, noise * 2f));
      } else {   // normal: fixed gentle grade
        tLo = 0f; tHi = 1f; tGamma = 1f; tSat = 1.05f;
        chooseBase();
      }
    }

    static float ema(float cur, float tgt, float a) { return cur + (tgt - cur) * a; }

    @Override
    public void drawFrame(int inputTexId, long presentationTimeUs) throws VideoFrameProcessingException {
      try {
        boolean on = p.mode != 1;
        if (on && (frameN++ % 24 == 0)) runStats(inputTexId);
        float a = firstStats ? 1f : 0.06f;
        firstStats = false;
        p.denoise = ema(p.denoise, tDen, a);
        p.sharp = ema(p.sharp, tSharp, a);
        p.sat = ema(p.sat, tSat, a);
        p.lo = ema(p.lo, tLo, a);
        p.hi = ema(p.hi, tHi, a);
        p.gamma = ema(p.gamma, tGamma, a);
        GLES20.glUseProgram(prog);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glUniform1i(uTex, 0);
        GLES20.glUniform2f(uIn, p.inW, p.inH);
        GLES20.glUniform2f(uOut, p.outW, p.outH);
        GLES20.glUniform4f(uA, p.denoise, p.sharp, p.sat, on ? 1f : 0f);
        GLES20.glUniform4f(uB, p.lo, p.hi, p.gamma, p.compare && on ? 0.5f : -1f);
        bindQuad(aPos);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
        int err = GLES20.glGetError();
        if (err != GLES20.GL_NO_ERROR && err != GLES20.GL_INVALID_ENUM) {
          throw new VideoFrameProcessingException("GL error " + err, presentationTimeUs);
        }
      } catch (RuntimeException e) {
        throw new VideoFrameProcessingException(e, presentationTimeUs);
      }
    }

    @Override
    public void release() throws VideoFrameProcessingException {
      super.release();
      if (statsFbo != 0) GLES20.glDeleteFramebuffers(1, new int[] {statsFbo}, 0);
      if (statsTex != 0) GLES20.glDeleteTextures(1, new int[] {statsTex}, 0);
      GLES20.glDeleteProgram(prog);
      GLES20.glDeleteProgram(progStats);
    }
  }
}
