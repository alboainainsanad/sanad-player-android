import Foundation
import Metal
import MetalKit
import CoreVideo
import AVFoundation

/// Real-time GPU picture enhancement (Metal): Catmull-Rom upscaling to the screen's native resolution,
/// edge-preserving denoise, contrast-adaptive sharpening and automatic levels / gamma / saturation.
/// Same algorithm as the Android build; statistics are sampled from the decoded frame about once per second.
final class SPEnhanceRenderer: NSObject, MTKViewDelegate {
    final class Params {
        var mode = 0            // 0 auto, 1 off, 2 normal, 3 strong
        var pic = 0             // 0 natural, 1 vivid, 2 cinematic, 3 sport, 4 anime, 5 night
        var picOn: Bool { pic != 0 }
        var compare = false
        var year = 0
        var fill = 0            // 0 fit, 1 fill, 2 zoom
        var denoise: Float = 0.3, sharp: Float = 0.5, sat: Float = 1, lo: Float = 0, hi: Float = 1, gamma: Float = 1
        var oldFilm = false, faded = false, dark = false, noisy = false
        var inW = 0, inH = 0, outH = 0
    }
    let p = Params()
    let device: MTLDevice
    let queue: MTLCommandQueue
    var pipeline: MTLRenderPipelineState!
    var cache: CVMetalTextureCache?
    var current: CVPixelBuffer?
    var currentTex: CVMetalTexture?
    var frameN = 0
    var tDen: Float = 0.3, tSharp: Float = 0.5, tSat: Float = 1, tLo: Float = 0, tHi: Float = 1, tGamma: Float = 1
    var first = true
    let lock = NSLock()

    static let shader = """
    #include <metal_stdlib>
    using namespace metal;
    struct VOut { float4 pos [[position]]; float2 uv; };
    vertex VOut vmain(uint vid [[vertex_id]], constant float4 &rect [[buffer(0)]]) {
        float2 p[4] = { float2(rect.x, rect.y), float2(rect.z, rect.y), float2(rect.x, rect.w), float2(rect.z, rect.w) };
        float2 t[4] = { float2(0.0, 1.0), float2(1.0, 1.0), float2(0.0, 0.0), float2(1.0, 0.0) };
        VOut o; o.pos = float4(p[vid], 0.0, 1.0); o.uv = t[vid]; return o;
    }
    struct U { float2 inSize; float2 outSize; float4 a; float4 b; float4 c; };
    static float3 T(texture2d<float> tex, sampler s, float2 u) { return tex.sample(s, u).rgb; }
    static float3 catmull(texture2d<float> tex, sampler s, float2 uv, float2 inSize) {
        float2 sp = uv * inSize; float2 t1 = floor(sp - 0.5) + 0.5; float2 f = sp - t1;
        float2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f)); float2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
        float2 w2 = f * (0.5 + f * (2.0 - 1.5 * f)); float2 w3 = f * f * (-0.5 + 0.5 * f);
        float2 w12 = w1 + w2; float2 o12 = w2 / w12;
        float2 p0 = (t1 - 1.0) / inSize; float2 p3 = (t1 + 2.0) / inSize; float2 p12 = (t1 + o12) / inSize;
        float3 r = T(tex,s,float2(p0.x,p0.y))*w0.x*w0.y + T(tex,s,float2(p12.x,p0.y))*w12.x*w0.y + T(tex,s,float2(p3.x,p0.y))*w3.x*w0.y;
        r += T(tex,s,float2(p0.x,p12.y))*w0.x*w12.y + T(tex,s,float2(p12.x,p12.y))*w12.x*w12.y + T(tex,s,float2(p3.x,p12.y))*w3.x*w12.y;
        r += T(tex,s,float2(p0.x,p3.y))*w0.x*w3.y + T(tex,s,float2(p12.x,p3.y))*w12.x*w3.y + T(tex,s,float2(p3.x,p3.y))*w3.x*w3.y;
        return r;
    }
    fragment float4 fmain(VOut in [[stage_in]], texture2d<float> tex [[texture(0)]], constant U &u [[buffer(0)]]) {
        constexpr sampler s(filter::linear, address::clamp_to_edge);
        float2 uv = in.uv;
        if (u.a.w < 0.5 || (u.b.w > 0.0 && uv.x < u.b.w)) {
            float3 o = T(tex, s, uv);
            if (u.b.w > 0.0 && abs(uv.x - u.b.w) < 1.5 / u.outSize.x) o = float3(1.0);
            return float4(o, 1.0);
        }
        float3 base = clamp(catmull(tex, s, uv, u.inSize), 0.0, 1.0);
        float2 px = 1.0 / u.inSize; float3 c0 = T(tex, s, uv); float3 acc = c0; float ws = 1.0; float mx = 0.0;
        float sig = 0.025 + 0.09 * u.a.x; float k = 1.0 / (2.0 * sig * sig);
        for (int j = -1; j <= 1; j++) { for (int i = -1; i <= 1; i++) {
            if (i == 0 && j == 0) continue;
            float3 c = T(tex, s, uv + float2(float(i), float(j)) * px); float3 d = c - c0; float dd = dot(d, d);
            mx = max(mx, dd);
            float w = exp(-dd * k) * ((i == 0 || j == 0) ? 1.0 : 0.7); acc += c * w; ws += w;
        } }
        float3 den = acc / ws;
        float flt = 1.0 - smoothstep(0.0015, 0.03, mx);
        float dn = u.a.x * (0.35 + 0.65 * flt);
        float3 res = mix(base, den + (base - c0) * (1.0 - flt), dn);
        float2 d = max(1.0 / u.outSize, 0.6 / u.inSize);
        float3 n = T(tex,s,uv+float2(0.0,-d.y)), so = T(tex,s,uv+float2(0.0,d.y)), e = T(tex,s,uv+float2(d.x,0.0)), w = T(tex,s,uv+float2(-d.x,0.0));
        float3 mn = min(base, min(min(n, so), min(e, w))); float3 mxc = max(base, max(max(n, so), max(e, w)));
        float3 amp = sqrt(clamp(min(mn, 1.0 - mxc) / max(mxc, float3(1e-4)), 0.0, 1.0));
        float3 wgt = amp * (-1.0 / mix(8.0, 4.5, u.a.y));
        float3 sh = (base + (n + so + e + w) * wgt) / (1.0 + 4.0 * wgt);
        res += (sh - base) * u.a.y * 1.15 * (1.0 - 0.6 * flt * u.a.x);
        res = clamp((res - u.b.x) / max(u.b.y - u.b.x, 0.2), 0.0, 1.0);
        res = pow(res, float3(u.b.z));
        float l = dot(res, float3(0.299, 0.587, 0.114));
        res = clamp(mix(float3(l), res, u.a.z), 0.0, 1.0);
        // picture mode: contrast, gamma, warmth (c.x con, c.y gamma, c.z warm)
        res = clamp((res - 0.5) * u.c.x + 0.5, 0.0, 1.0);
        res = pow(res, float3(u.c.y));
        res = clamp(res + float3(u.c.z, u.c.z * 0.25, -u.c.z), 0.0, 1.0);
        return float4(res, 1.0);
    }
    """

    init?(view: MTKView) {
        guard let dev = MTLCreateSystemDefaultDevice(), let q = dev.makeCommandQueue() else { return nil }
        device = dev; queue = q
        super.init()
        view.device = dev
        view.colorPixelFormat = .bgra8Unorm
        view.framebufferOnly = true
        view.isPaused = false
        view.enableSetNeedsDisplay = false
        view.preferredFramesPerSecond = 60
        view.clearColor = MTLClearColorMake(0, 0, 0, 1)
        do {
            let lib = try dev.makeLibrary(source: SPEnhanceRenderer.shader, options: nil)
            let d = MTLRenderPipelineDescriptor()
            d.vertexFunction = lib.makeFunction(name: "vmain")
            d.fragmentFunction = lib.makeFunction(name: "fmain")
            d.colorAttachments[0].pixelFormat = .bgra8Unorm
            pipeline = try dev.makeRenderPipelineState(descriptor: d)
        } catch {
            NSLog("SanadPlayer shader error: \(error)")
            return nil
        }
        CVMetalTextureCacheCreate(nil, nil, dev, nil, &cache)
    }

    /// called from the display link with each new decoded frame
    func push(_ pb: CVPixelBuffer, screenH: Int) {
        let w = CVPixelBufferGetWidth(pb), h = CVPixelBufferGetHeight(pb)
        guard let cache = cache else { return }
        var tex: CVMetalTexture?
        CVMetalTextureCacheCreateTextureFromImage(nil, cache, pb, nil, .bgra8Unorm, w, h, 0, &tex)
        lock.lock()
        if p.inW != w || p.inH != h { p.inW = w; p.inH = h; first = true; chooseBase() }
        p.outH = max(h, min(screenH, 2160))
        current = pb; currentTex = tex
        lock.unlock()
        frameN += 1
        if p.mode != 1 && frameN % 24 == 1 { analyze(pb) }
    }

    func chooseBase() {
        let h = p.inH
        var den: Float = h <= 480 ? 0.55 : h <= 720 ? 0.38 : h <= 1080 ? 0.22 : 0.12
        var sh: Float = h <= 480 ? 0.85 : h <= 720 ? 0.7 : h <= 1080 ? 0.5 : 0.3
        if p.mode == 2 { den *= 0.8; sh *= 0.85 }
        if p.mode == 3 { den = min(1, den * 1.5 + 0.1); sh = min(1, sh * 1.25) }
        tDen = den; tSharp = sh
        if p.year > 0 && p.year < 1995 { p.oldFilm = true }
    }

    func analyze(_ pb: CVPixelBuffer) {
        CVPixelBufferLockBaseAddress(pb, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(pb) else { return }
        let w = CVPixelBufferGetWidth(pb), h = CVPixelBufferGetHeight(pb), bpr = CVPixelBufferGetBytesPerRow(pb)
        let ptr = base.assumingMemoryBound(to: UInt8.self)
        let SW = 32, SH = 18
        var hist = [Int](repeating: 0, count: 256)
        var sumL: Float = 0, sumS: Float = 0, nz: Float = 0
        var nzN = 0
        func lum(_ x: Int, _ y: Int) -> Float {
            let o = y * bpr + x * 4
            return 0.114 * Float(ptr[o]) + 0.587 * Float(ptr[o + 1]) + 0.299 * Float(ptr[o + 2])
        }
        for j in 0..<SH {
            for i in 0..<SW {
                let x = min(w - 2, max(1, (i * 2 + 1) * w / (SW * 2))), y = min(h - 2, max(1, (j * 2 + 1) * h / (SH * 2)))
                let o = y * bpr + x * 4
                let b = Float(ptr[o]), g = Float(ptr[o + 1]), r = Float(ptr[o + 2])
                let l = 0.299 * r + 0.587 * g + 0.114 * b
                hist[min(255, Int(l))] += 1
                sumL += l
                let mx = max(r, max(g, b)), mn = min(r, min(g, b))
                sumS += mx > 0 ? (mx - mn) / mx : 0
                let a = (lum(x + 1, y) + lum(x - 1, y) + lum(x, y + 1) + lum(x, y - 1)) / 4
                let hf = min(255, abs(l - a) * 8)
                if hf < 60 { nz += hf; nzN += 1 }
            }
        }
        let n = SW * SH
        let meanL = sumL / Float(n) / 255, meanS = sumS / Float(n)
        var acc = 0, p2 = 0, p98 = 255
        for i in 0..<256 { acc += hist[i]; if Float(acc) >= Float(n) * 0.02 { p2 = i; break } }
        acc = 0
        for i in stride(from: 255, through: 0, by: -1) { acc += hist[i]; if Float(acc) >= Float(n) * 0.02 { p98 = i; break } }
        let lo = Float(p2) / 255, hi = Float(p98) / 255
        let noise = nzN > 0 ? nz / Float(nzN) / 255 : 0
        if meanL < 0.04 { return }
        lock.lock(); defer { lock.unlock() }
        let faded = (hi - lo) < 0.78 && meanL > 0.08, dark = meanL < 0.22, gray = meanS < 0.04, noisy = noise > 0.06
        p.faded = faded; p.dark = dark; p.noisy = noisy
        if p.mode == 0 { p.oldFilm = (p.year > 0 && p.year < 1995) || (faded && (noisy || p.inH <= 576)) || (gray && p.inH <= 720) }
        if p.mode == 0 || p.mode == 3 {
            let k: Float = p.mode == 3 ? 0.85 : 0.65
            tLo = (faded || p.oldFilm) ? lo * k : lo * 0.3
            tHi = (faded || p.oldFilm) ? 1 - (1 - hi) * k : 1 - (1 - hi) * 0.3
            if tHi - tLo < 0.62 { let m = (tHi + tLo) / 2; tLo = m - 0.31; tHi = m + 0.31 }
            tGamma = dark ? 0.86 : meanL > 0.72 ? 1.06 : 1
            tSat = gray ? 1 : meanS < 0.18 ? (p.oldFilm ? 1.28 : 1.18) : meanS < 0.3 ? 1.08 : 1.02
            chooseBase()
            if noisy || p.oldFilm { tDen = min(1, tDen + 0.25 + min(0.3, noise * 2)) }
        } else {
            tLo = 0; tHi = 1; tGamma = 1; tSat = 1.05
            chooseBase()
        }
    }

    struct PicPreset { var sat: Float; var con: Float; var gamma: Float; var warm: Float; var sharp: Float }
    static func picPreset(_ i: Int) -> PicPreset {
        switch i {
        case 1: return PicPreset(sat: 1.22, con: 1.08, gamma: 0.96, warm: 0.012, sharp: 0.05)   // vivid
        case 2: return PicPreset(sat: 0.94, con: 1.06, gamma: 1.06, warm: 0.035, sharp: -0.1)   // cinematic
        case 3: return PicPreset(sat: 1.10, con: 1.05, gamma: 1.0, warm: 0, sharp: 0.2)         // sport
        case 4: return PicPreset(sat: 1.16, con: 1.04, gamma: 0.98, warm: 0, sharp: 0.15)       // anime
        case 5: return PicPreset(sat: 0.92, con: 0.97, gamma: 1.12, warm: 0.02, sharp: -0.05)   // night
        default: return PicPreset(sat: 1, con: 1, gamma: 1, warm: 0, sharp: 0)
        }
    }
    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

    func draw(in view: MTKView) {
        lock.lock()
        let tex = currentTex.flatMap { CVMetalTextureGetTexture($0) }
        let a: Float = first ? 1 : 0.06
        first = false
        p.denoise += (tDen - p.denoise) * a; p.sharp += (tSharp - p.sharp) * a; p.sat += (tSat - p.sat) * a
        p.lo += (tLo - p.lo) * a; p.hi += (tHi - p.hi) * a; p.gamma += (tGamma - p.gamma) * a
        let on = p.mode != 1 || p.picOn
        let pm = SPEnhanceRenderer.picPreset(p.pic)
        let plain = p.mode == 1   // enhancement off: picture mode only, no denoise / sharpen / auto levels
        var u: [Float] = [Float(p.inW), Float(p.inH), 0, 0, plain ? 0 : p.denoise, max(0, (plain ? 0 : p.sharp) + pm.sharp), (plain ? 1 : p.sat) * pm.sat, on ? 1 : 0, plain ? 0 : p.lo, plain ? 1 : p.hi, plain ? 1 : p.gamma, (p.compare && on) ? 0.5 : -1, pm.con, pm.gamma, pm.warm, 0]
        let fill = p.fill
        lock.unlock()
        guard let drawable = view.currentDrawable, let rpd = view.currentRenderPassDescriptor, let cb = queue.makeCommandBuffer() else { return }
        rpd.colorAttachments[0].loadAction = .clear
        guard let enc = cb.makeRenderCommandEncoder(descriptor: rpd) else { return }
        if let tex = tex, p.inW > 0 {
            let vw = Float(view.drawableSize.width), vh = Float(view.drawableSize.height)
            let va = vw / vh, ia = Float(p.inW) / Float(p.inH)
            var sx: Float = 1, sy: Float = 1
            if fill == 1 { sx = 1; sy = 1 }
            else if (fill == 0) == (ia > va) { sy = va / ia } else { sx = ia / va }
            if fill == 2 { let z = max(1 / sx, 1 / sy); sx *= z; sy *= z }
            u[2] = vw * sx; u[3] = vh * sy
            var rect: [Float] = [-sx, -sy, sx, sy]
            enc.setRenderPipelineState(pipeline)
            enc.setVertexBytes(&rect, length: 16, index: 0)
            enc.setFragmentBytes(&u, length: u.count * 4, index: 0)
            enc.setFragmentTexture(tex, index: 0)
            enc.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        }
        enc.endEncoding()
        cb.present(drawable)
        cb.commit()
    }
}
