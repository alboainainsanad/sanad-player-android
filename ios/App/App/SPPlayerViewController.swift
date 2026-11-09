import UIKit
import AVFoundation
import MetalKit

final class SPPlayerViewController: UIViewController, AVPlayerItemLegibleOutputPushDelegate {
    let opts: [String: Any]
    let onFinish: ([String: Any]) -> Void
    let player = AVPlayer()
    var item: AVPlayerItem?
    var videoOut: AVPlayerItemVideoOutput?
    var legibleOut: AVPlayerItemLegibleOutput?
    // machine translation of embedded subtitles → Arabic (Google gtx, cached)
    var mtOn = false
    var mtCache: [String: String] = [:]
    var mtPending = Set<String>()
    var curCue = ""
    var mtGroup: AVMediaSelectionGroup?

    func mtTranslate(_ text: String, done: @escaping (String?) -> Void) {
        var c = URLComponents(string: "https://translate.googleapis.com/translate_a/single")!
        c.queryItems = [URLQueryItem(name: "client", value: "gtx"), URLQueryItem(name: "sl", value: "auto"), URLQueryItem(name: "tl", value: "ar"), URLQueryItem(name: "dt", value: "t"), URLQueryItem(name: "q", value: text)]
        guard let u = c.url else { done(nil); return }
        URLSession.shared.dataTask(with: u) { d, _, _ in
            guard let d = d, let j = try? JSONSerialization.jsonObject(with: d) as? [Any], let segs = j.first as? [Any] else { done(nil); return }
            let out = segs.compactMap { ($0 as? [Any])?.first as? String }.joined()
            done(out.isEmpty ? nil : out)
        }.resume()
    }

    func showMt(_ raw: String) {
        let k = raw.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression).trimmingCharacters(in: .whitespacesAndNewlines)
        curCue = k
        if k.isEmpty { subtitleL.text = nil; return }
        if let hit = mtCache[k] { subtitleL.text = " " + hit + " "; return }
        if mtPending.contains(k) { return }
        mtPending.insert(k)
        mtTranslate(k) { [weak self] r in DispatchQueue.main.async {
            guard let s = self else { return }
            s.mtPending.remove(k)
            if let r = r { s.mtCache[k] = r; if s.curCue == k { s.subtitleL.text = " " + r + " " } }
        } }
    }

    /// called by the web layer: pick the embedded non-Arabic subtitle and translate it live
    func useMt() {
        guard !live, let it = item, let leg = mtGroup else { return }
        let o = leg.options.first(where: { ($0.extendedLanguageTag ?? $0.locale?.identifier ?? "").lowercased().hasPrefix("en") }) ?? leg.options.first
        guard let opt = o else { return }
        it.select(opt, in: leg)
        extCues = []
        mtOn = true
        badgeExtra = "🤖 ترجمة عربية آلية ← " + opt.displayName
        updateBadge()
    }
    let mtk = MTKView()
    var renderer: SPEnhanceRenderer?
    var link: CADisplayLink?
    var timeObs: Any?
    var statusObs: NSKeyValueObservation?
    var live = false
    var key = ""
    var chans: [[String: Any]] = []
    var chIndex = 0
    var ended = false
    var finished = false
    var retries = 0
    var extCues: [(Double, Double, String)] = []
    var extLabel = ""
    var askedSubs = false
    var badgeExtra = ""
    var embeddedSubText: String?

    // UI
    let top = UIView(), bottom = UIView()
    let titleL = UILabel(), subL = UILabel(), badgeL = UILabel()
    let subtitleL = UILabel()
    let spinner = UIActivityIndicatorView(style: .large)
    let errL = UILabel()
    let slider = UISlider()
    let curL = UILabel(), durL = UILabel()
    let playB = UIButton(type: .system)
    let enhB = UIButton(type: .system), cmpB = UIButton(type: .system), aspB = UIButton(type: .system), trkB = UIButton(type: .system)
    var hideTimer: Timer?
    var uiShown = true
    var seeking = false
    let ENH = ["✨ تلقائي", "⚪ إيقاف", "✨ عادي", "✨ قوي"]
    let ASP = ["ملاءمة", "تعبئة", "تكبير"]
    var listView: UITableView?

    init(opts: [String: Any], onFinish: @escaping ([String: Any]) -> Void) {
        self.opts = opts
        self.onFinish = onFinish
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError() }

    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .landscape }

    func str(_ k: String, _ d: String = "") -> String { opts[k] as? String ?? d }
    func num(_ k: String, _ d: Double = 0) -> Double { (opts[k] as? NSNumber)?.doubleValue ?? d }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        UIApplication.shared.isIdleTimerDisabled = true
        live = opts["live"] as? Bool ?? false
        chans = opts["list"] as? [[String: Any]] ?? []
        chIndex = Int(num("index"))
        mtk.frame = view.bounds
        mtk.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(mtk)
        renderer = SPEnhanceRenderer(view: mtk)
        mtk.delegate = renderer
        renderer?.p.mode = Int(num("enh"))
        renderer?.p.year = Int(num("year"))
        buildUI()
        let tap = UITapGestureRecognizer(target: self, action: #selector(toggleUI))
        view.addGestureRecognizer(tap)
        if live {
            let sw1 = UISwipeGestureRecognizer(target: self, action: #selector(swipeUp)); sw1.direction = .up; view.addGestureRecognizer(sw1)
            let sw2 = UISwipeGestureRecognizer(target: self, action: #selector(swipeDown)); sw2.direction = .down; view.addGestureRecognizer(sw2)
        }
        link = CADisplayLink(target: self, selector: #selector(tick))
        link?.add(to: .main, forMode: .common)
        timeObs = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 4), queue: .main) { [weak self] _ in self?.timeTick() }
        NotificationCenter.default.addObserver(self, selector: #selector(didEnd), name: .AVPlayerItemDidPlayToEndTime, object: nil)
        if live { startChannel() } else { startVod(at: num("start") / 1000.0) }
        showUI()
    }

    // MARK: UI
    func pill(_ b: UIButton, _ t: String) {
        b.setTitle(t, for: .normal)
        b.setTitleColor(.white, for: .normal)
        b.titleLabel?.font = .systemFont(ofSize: 14, weight: .semibold)
        b.backgroundColor = UIColor(white: 0.15, alpha: 0.75)
        b.layer.cornerRadius = 17
        b.contentEdgeInsets = UIEdgeInsets(top: 7, left: 12, bottom: 7, right: 12)
    }

    func buildUI() {
        let g = CAGradientLayer()
        g.colors = [UIColor(white: 0, alpha: 0.8).cgColor, UIColor.clear.cgColor]
        top.layer.insertSublayer(g, at: 0)
        for v in [top, bottom, subtitleL, spinner, errL] { v.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(v) }
        let close = UIButton(type: .system); pill(close, "✕"); close.addTarget(self, action: #selector(closeTap), for: .touchUpInside)
        pill(enhB, ENH[min(3, max(0, renderer?.p.mode ?? 0))]); enhB.addTarget(self, action: #selector(enhTap), for: .touchUpInside)
        pill(cmpB, "⇔ قبل/بعد"); cmpB.alpha = 0.8; cmpB.addTarget(self, action: #selector(cmpTap), for: .touchUpInside)
        pill(aspB, "⛶ " + ASP[0]); aspB.addTarget(self, action: #selector(aspTap), for: .touchUpInside)
        pill(trkB, "💬 الصوت والترجمة"); trkB.showsMenuAsPrimaryAction = true
        titleL.font = .boldSystemFont(ofSize: 17); titleL.textColor = .white
        subL.font = .systemFont(ofSize: 12.5); subL.textColor = UIColor(white: 1, alpha: 0.8)
        badgeL.font = .systemFont(ofSize: 12); badgeL.textColor = UIColor(red: 0.77, green: 0.71, blue: 0.99, alpha: 1)
        for l in [titleL, subL, badgeL] { l.textAlignment = .right; l.lineBreakMode = .byTruncatingTail }
        let tcol = UIStackView(arrangedSubviews: [titleL, subL, badgeL]); tcol.axis = .vertical; tcol.spacing = 1
        var buttons: [UIView] = [close, tcol, enhB, cmpB, aspB, trkB]
        if live && chans.count > 1 {
            let up = UIButton(type: .system); pill(up, "▲"); up.addTarget(self, action: #selector(swipeDown), for: .touchUpInside)
            let dn = UIButton(type: .system); pill(dn, "▼"); dn.addTarget(self, action: #selector(swipeUp), for: .touchUpInside)
            let ls = UIButton(type: .system); pill(ls, "☰ القنوات"); ls.addTarget(self, action: #selector(listTap), for: .touchUpInside)
            buttons += [up, dn, ls]
        }
        let row = UIStackView(arrangedSubviews: buttons)
        row.axis = .horizontal; row.spacing = 6; row.alignment = .center
        row.semanticContentAttribute = .forceRightToLeft
        tcol.setContentHuggingPriority(.defaultLow, for: .horizontal)
        tcol.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        row.translatesAutoresizingMaskIntoConstraints = false
        top.addSubview(row)
        // bottom bar
        playB.setTitle("⏸", for: .normal); playB.titleLabel?.font = .systemFont(ofSize: 26); playB.setTitleColor(.white, for: .normal)
        playB.addTarget(self, action: #selector(playTap), for: .touchUpInside)
        let back10 = UIButton(type: .system); back10.setTitle("⟲ ١٠", for: .normal); back10.setTitleColor(.white, for: .normal); back10.addTarget(self, action: #selector(back10Tap), for: .touchUpInside)
        let fwd10 = UIButton(type: .system); fwd10.setTitle("١٠ ⟳", for: .normal); fwd10.setTitleColor(.white, for: .normal); fwd10.addTarget(self, action: #selector(fwd10Tap), for: .touchUpInside)
        for l in [curL, durL] { l.font = .monospacedDigitSystemFont(ofSize: 13, weight: .medium); l.textColor = .white; l.text = "0:00" }
        slider.minimumTrackTintColor = UIColor(red: 0.23, green: 0.51, blue: 0.96, alpha: 1)
        slider.addTarget(self, action: #selector(sliderChanged), for: .valueChanged)
        slider.addTarget(self, action: #selector(sliderDone), for: [.touchUpInside, .touchUpOutside])
        let brow = UIStackView(arrangedSubviews: live ? [playB] : [back10, playB, fwd10, curL, slider, durL])
        brow.axis = .horizontal; brow.spacing = 12; brow.alignment = .center
        brow.translatesAutoresizingMaskIntoConstraints = false
        bottom.addSubview(brow)
        bottom.backgroundColor = UIColor(white: 0, alpha: 0.45)
        subtitleL.numberOfLines = 0; subtitleL.textAlignment = .center
        subtitleL.font = .boldSystemFont(ofSize: CGFloat(20 * num("subScale", 1.25)))
        subtitleL.textColor = .white
        subtitleL.shadowColor = .black; subtitleL.shadowOffset = CGSize(width: 1, height: 1)
        subtitleL.backgroundColor = UIColor(white: 0, alpha: 0.35)
        subtitleL.layer.cornerRadius = 6; subtitleL.clipsToBounds = true
        spinner.color = .white; spinner.hidesWhenStopped = true
        errL.numberOfLines = 0; errL.textAlignment = .center; errL.textColor = .white; errL.font = .systemFont(ofSize: 15)
        errL.backgroundColor = UIColor(white: 0.1, alpha: 0.85); errL.layer.cornerRadius = 12; errL.clipsToBounds = true; errL.isHidden = true
        errL.isUserInteractionEnabled = true
        errL.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(retryTap)))
        let sg = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            top.topAnchor.constraint(equalTo: view.topAnchor), top.leadingAnchor.constraint(equalTo: view.leadingAnchor), top.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            row.topAnchor.constraint(equalTo: top.topAnchor, constant: 10), row.bottomAnchor.constraint(equalTo: top.bottomAnchor, constant: -14),
            row.leadingAnchor.constraint(equalTo: sg.leadingAnchor, constant: 10), row.trailingAnchor.constraint(equalTo: sg.trailingAnchor, constant: -10),
            bottom.bottomAnchor.constraint(equalTo: view.bottomAnchor), bottom.leadingAnchor.constraint(equalTo: view.leadingAnchor), bottom.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            brow.topAnchor.constraint(equalTo: bottom.topAnchor, constant: 8), brow.bottomAnchor.constraint(equalTo: sg.bottomAnchor, constant: -6),
            brow.leadingAnchor.constraint(equalTo: sg.leadingAnchor, constant: 16), brow.trailingAnchor.constraint(equalTo: sg.trailingAnchor, constant: -16),
            subtitleL.centerXAnchor.constraint(equalTo: view.centerXAnchor), subtitleL.bottomAnchor.constraint(equalTo: view.bottomAnchor, constant: -28),
            subtitleL.widthAnchor.constraint(lessThanOrEqualTo: view.widthAnchor, multiplier: 0.86),
            spinner.centerXAnchor.constraint(equalTo: view.centerXAnchor), spinner.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            errL.centerXAnchor.constraint(equalTo: view.centerXAnchor), errL.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            errL.widthAnchor.constraint(lessThanOrEqualToConstant: 420)
        ])
        DispatchQueue.main.async { g.frame = self.top.bounds.insetBy(dx: 0, dy: -20) }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        top.layer.sublayers?.first?.frame = top.bounds
    }

    @objc func toggleUI() { if listView != nil { closeList(); return }; uiShown ? hideUI() : showUI() }
    func showUI() {
        uiShown = true
        UIView.animate(withDuration: 0.2) { self.top.alpha = 1; self.bottom.alpha = 1 }
        hideTimer?.invalidate()
        hideTimer = Timer.scheduledTimer(withTimeInterval: 4, repeats: false) { [weak self] _ in if self?.player.rate ?? 0 > 0 { self?.hideUI() } }
    }
    func hideUI() { uiShown = false; UIView.animate(withDuration: 0.25) { self.top.alpha = 0; self.bottom.alpha = 0 } }

    // MARK: playback
    func fixURL(_ s: String) -> URL? {
        var u = s
        // Xtream live: MPEG-TS over HTTP isn't playable by AVPlayer, the same stream is offered as HLS
        if u.range(of: "/live/[^/]+/[^/]+/\\d+\\.ts", options: .regularExpression) != nil { u = String(u.dropLast(3)) + ".m3u8" }
        return URL(string: u) ?? URL(string: u.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? u)
    }

    func load(_ urlString: String, at: Double) {
        errL.isHidden = true
        spinner.startAnimating()
        guard let url = fixURL(urlString) else { showError("رابط غير صالح"); return }
        let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": ["User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"]])
        let it = AVPlayerItem(asset: asset)
        it.preferredForwardBufferDuration = live ? 6 : 20
        let vo = AVPlayerItemVideoOutput(pixelBufferAttributes: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA, kCVPixelBufferMetalCompatibilityKey as String: true])
        it.add(vo)
        let lo = AVPlayerItemLegibleOutput(mediaSubtypesForNativeRepresentation: [])
        lo.suppressesPlayerRendering = true
        lo.setDelegate(self, queue: .main)
        it.add(lo)
        videoOut = vo; legibleOut = lo; item = it
        statusObs = it.observe(\.status, options: [.new]) { [weak self] it, _ in
            DispatchQueue.main.async { self?.statusChanged(it, at: at) }
        }
        player.replaceCurrentItem(with: it)
        player.play()
        playB.setTitle("⏸", for: .normal)
    }

    func statusChanged(_ it: AVPlayerItem, at: Double) {
        if it.status == .readyToPlay {
            spinner.stopAnimating(); retries = 0
            if at > 1 && !live { player.seek(to: CMTime(seconds: at, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero) }
            Task { await self.selectTracks(it) }
        } else if it.status == .failed {
            let e = it.error as NSError?
            if retries < 2 { retries += 1; let pos = player.currentTime().seconds; DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { self.reload(pos.isFinite ? pos : 0) }; return }
            spinner.stopAnimating()
            let ext = (URL(string: currentURL())?.pathExtension ?? "").lowercased()
            var msg = "تعذّر التشغيل"
            if ["mkv", "avi", "wmv", "flv", "ts"].contains(ext) && !live { msg = "صيغة الملف (\(ext.uppercased())) غير مدعومة في مشغّل الآيفون.\nجرّب نسخة ثانية من الفيلم (MP4)" }
            showError(msg + "\n" + (e?.localizedDescription ?? "") + "\n\nاضغط لإعادة المحاولة")
        }
    }

    func currentURL() -> String {
        if live, chIndex < chans.count, let u = chans[chIndex]["u"] as? String { return u }
        return str("url")
    }

    func reload(_ pos: Double) { if live { startChannel() } else { load(str("url"), at: pos) } }

    func startVod(at: Double) {
        key = str("key")
        titleL.text = str("title"); subL.text = str("sub")
        load(str("url"), at: at)
    }

    func startChannel() {
        var name = str("title"), now = "", url = str("url")
        if chIndex >= 0 && chIndex < chans.count {
            let c = chans[chIndex]
            url = c["u"] as? String ?? url; name = c["n"] as? String ?? name; now = c["e"] as? String ?? ""; key = c["k"] as? String ?? key
        }
        titleL.text = (chans.isEmpty ? "" : "\(chIndex + 1)  ") + name
        subL.text = now
        load(url, at: 0)
        showUI()
        listView?.reloadData()
    }

    func zap(_ d: Int) {
        guard chans.count > 0 else { return }
        chIndex = (chIndex + d + chans.count) % chans.count
        retries = 0
        startChannel()
    }

    func selectTracks(_ it: AVPlayerItem) async {
        let asset = it.asset
        var hasAr = false
        var menuActions: [UIMenuElement] = []
        if let aud = try? await asset.loadMediaSelectionGroup(for: .audible) {
            let pa = str("prefAudio")
            if !pa.isEmpty, let o = aud.options.first(where: { ($0.extendedLanguageTag ?? $0.locale?.identifier ?? "").lowercased().hasPrefix(pa) }) {
                it.select(o, in: aud)
            }
            let acts = aud.options.map { o in
                UIAction(title: "🔊 " + o.displayName) { _ in it.select(o, in: aud) }
            }
            if acts.count > 1 { menuActions.append(UIMenu(title: "الصوت", options: .displayInline, children: acts)) }
        }
        if let leg = try? await asset.loadMediaSelectionGroup(for: .legible) {
            mtGroup = leg
            let ps = str("prefSub", "ar")
            if let o = leg.options.first(where: { ($0.extendedLanguageTag ?? $0.locale?.identifier ?? "").lowercased().hasPrefix(ps) }) {
                hasAr = true
                it.select(o, in: leg)
            }
            var acts: [UIMenuElement] = [UIAction(title: "إيقاف الترجمة المدمجة") { [weak self] _ in it.select(nil, in: leg); self?.subtitleL.text = nil }]
            acts += leg.options.map { o in UIAction(title: "💬 " + o.displayName) { [weak self] _ in it.select(o, in: leg); self?.extCues = []; self?.mtOn = false } }
            if !leg.options.isEmpty { acts.append(UIAction(title: "🤖 ترجم المدمجة للعربي") { [weak self] _ in self?.useMt() }) }
            menuActions.append(UIMenu(title: "الترجمة", options: .displayInline, children: acts))
        }
        do {
            menuActions.append(UIAction(title: "حجم الترجمة: أكبر") { [weak self] _ in guard let s = self else { return }; s.subtitleL.font = .boldSystemFont(ofSize: min(44, s.subtitleL.font.pointSize + 3)) })
            menuActions.append(UIAction(title: "حجم الترجمة: أصغر") { [weak self] _ in guard let s = self else { return }; s.subtitleL.font = .boldSystemFont(ofSize: max(12, s.subtitleL.font.pointSize - 3)) })
            if !extCues.isEmpty { menuActions.append(UIAction(title: "إيقاف الترجمة الخارجية") { [weak self] _ in self?.extCues = []; self?.subtitleL.text = nil }) }
            trkB.menu = UIMenu(title: "", children: menuActions)
            if !live && !askedSubs {
                askedSubs = true
                if hasAr { badgeExtra = "💬 ترجمة عربية مدمجة" }
                else if extCues.isEmpty { SanadNativePlugin.shared?.emit("needSubs", ["key": str("key"), "hasOther": !(mtGroup?.options.isEmpty ?? true)]) }
            }
        }
    }

    // embedded subtitles (pushed by AVFoundation)
    func legibleOutput(_ output: AVPlayerItemLegibleOutput, didOutputAttributedStrings strings: [NSAttributedString], nativeSampleBuffers nativeSamples: [Any], forItemTime itemTime: CMTime) {
        guard extCues.isEmpty else { return }
        let t = strings.map { $0.string }.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
        if mtOn { showMt(t); return }
        subtitleL.text = t.isEmpty ? nil : " " + t + " "
    }

    // external subtitles found by the web layer
    func addExternalSub(text: String, label: String, key: String) {
        guard !live, key == str("key") else { return }
        extCues = SPPlayerViewController.parseSrt(text)
        mtOn = false
        extLabel = label
        badgeExtra = "💬 ترجمة عربية: " + label
    }

    static func parseSrt(_ txt: String) -> [(Double, Double, String)] {
        var out: [(Double, Double, String)] = []
        let norm = txt.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        let re = try! NSRegularExpression(pattern: "(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})[,.](\\d{1,3})")
        for block in norm.components(separatedBy: "\n\n") {
            let lines = block.components(separatedBy: "\n")
            guard let i = lines.firstIndex(where: { re.firstMatch(in: $0, range: NSRange($0.startIndex..., in: $0)) != nil }) else { continue }
            let l = lines[i]
            guard let m = re.firstMatch(in: l, range: NSRange(l.startIndex..., in: l)) else { continue }
            func g(_ k: Int) -> Double { let r = m.range(at: k); if r.location == NSNotFound { return 0 }; return Double((l as NSString).substring(with: r)) ?? 0 }
            func ms(_ k: Int) -> Double { let r = m.range(at: k); let s = (l as NSString).substring(with: r); return (Double(s.padding(toLength: 3, withPad: "0", startingAt: 0)) ?? 0) / 1000 }
            let st = g(1) * 3600 + g(2) * 60 + g(3) + ms(4)
            let en = g(5) * 3600 + g(6) * 60 + g(7) + ms(8)
            let text = lines[(i + 1)...].joined(separator: "\n").replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression).trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty { out.append((st, en, text)) }
        }
        return out.sorted { $0.0 < $1.0 }
    }

    @objc func tick() {
        guard let vo = videoOut, let r = renderer else { return }
        let t = vo.itemTime(forHostTime: CACurrentMediaTime())
        if vo.hasNewPixelBuffer(forItemTime: t), let pb = vo.copyPixelBuffer(forItemTime: t, itemTimeForDisplay: nil) {
            r.push(pb, screenH: Int(min(UIScreen.main.nativeBounds.width, UIScreen.main.nativeBounds.height)))
        }
    }

    func timeTick() {
        let ct = player.currentTime().seconds
        if !extCues.isEmpty && ct.isFinite {
            let c = extCues.first(where: { ct >= $0.0 && ct <= $0.1 })
            subtitleL.text = c.map { " " + $0.2 + " " }
        }
        if !live, let d = item?.duration.seconds, d.isFinite, d > 0, !seeking {
            slider.value = Float(ct / d)
            curL.text = fmt(ct); durL.text = fmt(d)
        }
        updateBadge()
    }

    func fmt(_ s: Double) -> String {
        guard s.isFinite else { return "--:--" }
        let t = Int(s), h = t / 3600, m = t % 3600 / 60, sec = t % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, sec) : String(format: "%d:%02d", m, sec)
    }

    func updateBadge() {
        guard let p = renderer?.p else { badgeL.text = badgeExtra; return }
        var b = ""
        if p.inH > 0 { b += "\(p.inH)p"; if p.mode != 1 && p.outH > p.inH { b += " → \(p.outH)p" } }
        if p.mode == 1 { b += "  · بدون تحسين" } else {
            b += "  · ✨ " + (p.mode == 0 ? "تلقائي" : p.mode == 2 ? "عادي" : "قوي")
            if p.oldFilm { b += " · 🎞 ترميم فيلم قديم" } else {
                if p.faded { b += " · إصلاح ألوان باهتة" }
                if p.noisy { b += " · إزالة تشويش" }
                if p.dark { b += " · تفتيح" }
            }
        }
        if !badgeExtra.isEmpty { b += "  · " + badgeExtra }
        badgeL.text = b
    }

    func showError(_ m: String) { errL.text = "\n  " + m.replacingOccurrences(of: "\n", with: "  \n  ") + "  \n"; errL.isHidden = false }

    // MARK: actions
    @objc func playTap() { if player.rate > 0 { player.pause(); playB.setTitle("▶︎", for: .normal) } else { player.play(); playB.setTitle("⏸", for: .normal) }; showUI() }
    @objc func back10Tap() { seekBy(-10) }
    @objc func fwd10Tap() { seekBy(10) }
    func seekBy(_ s: Double) { let t = player.currentTime().seconds + s; player.seek(to: CMTime(seconds: max(0, t), preferredTimescale: 600)); showUI() }
    @objc func sliderChanged() { seeking = true; if let d = item?.duration.seconds, d.isFinite { curL.text = fmt(Double(slider.value) * d) }; showUI() }
    @objc func sliderDone() { if let d = item?.duration.seconds, d.isFinite { player.seek(to: CMTime(seconds: Double(slider.value) * d, preferredTimescale: 600)) { [weak self] _ in self?.seeking = false } } else { seeking = false } }
    @objc func enhTap() { guard let r = renderer else { return }; r.p.mode = (r.p.mode + 1) % 4; r.chooseBase(); enhB.setTitle(ENH[r.p.mode], for: .normal); SanadNativePlugin.shared?.emit("enhMode", ["mode": r.p.mode]); updateBadge(); showUI() }
    @objc func cmpTap() { guard let r = renderer else { return }; r.p.compare.toggle(); cmpB.alpha = r.p.compare ? 1 : 0.8; cmpB.setTitle(r.p.compare ? "⇔ يسار: الأصل" : "⇔ قبل/بعد", for: .normal); showUI() }
    @objc func aspTap() { guard let r = renderer else { return }; r.p.fill = (r.p.fill + 1) % 3; aspB.setTitle("⛶ " + ASP[r.p.fill], for: .normal); showUI() }
    @objc func swipeUp() { zap(1) }
    @objc func swipeDown() { zap(-1) }
    @objc func retryTap() { retries = 0; reload(player.currentTime().seconds.isFinite ? player.currentTime().seconds : 0) }
    @objc func closeTap() { finish() }
    @objc func didEnd(_ n: Notification) {
        guard (n.object as? AVPlayerItem) === item, !live else { return }
        ended = true
        finish()
    }

    @objc func listTap() {
        if listView != nil { closeList(); return }
        let t = UITableView(frame: .zero, style: .plain)
        t.backgroundColor = UIColor(red: 12/255, green: 14/255, blue: 18/255, alpha: 0.94)
        t.dataSource = self; t.delegate = self
        t.register(UITableViewCell.self, forCellReuseIdentifier: "c")
        t.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(t)
        NSLayoutConstraint.activate([t.topAnchor.constraint(equalTo: view.topAnchor), t.bottomAnchor.constraint(equalTo: view.bottomAnchor),
                                     t.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor), t.widthAnchor.constraint(equalToConstant: 300)])
        listView = t
        t.reloadData()
        if chIndex < chans.count { t.scrollToRow(at: IndexPath(row: chIndex, section: 0), at: .middle, animated: false) }
        hideUI()
    }
    func closeList() { listView?.removeFromSuperview(); listView = nil }

    func finish() {
        guard !finished else { return }
        finished = true
        let pos = player.currentTime().seconds
        let dur = item?.duration.seconds ?? 0
        player.pause()
        link?.invalidate()
        if let o = timeObs { player.removeTimeObserver(o) }
        UIApplication.shared.isIdleTimerDisabled = false
        let r: [String: Any] = ["pos": pos.isFinite ? pos : 0, "dur": dur.isFinite ? dur : 0, "ended": ended, "key": key, "index": chIndex, "enh": renderer?.p.mode ?? 0]
        dismiss(animated: true) { self.onFinish(r) }
    }
}

extension SPPlayerViewController: UITableViewDataSource, UITableViewDelegate {
    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { chans.count }
    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let c = tableView.dequeueReusableCell(withIdentifier: "c", for: indexPath)
        let ch = chans[indexPath.row]
        let now = ch["e"] as? String ?? ""
        c.textLabel?.numberOfLines = 2
        c.textLabel?.textAlignment = .right
        c.textLabel?.text = "\(indexPath.row + 1)  \(ch["n"] as? String ?? "")" + (now.isEmpty ? "" : "\n\(now)")
        c.textLabel?.textColor = indexPath.row == chIndex ? UIColor(red: 0.77, green: 0.71, blue: 0.99, alpha: 1) : .white
        c.textLabel?.font = .systemFont(ofSize: 14)
        c.backgroundColor = .clear
        return c
    }
    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        chIndex = indexPath.row
        closeList()
        startChannel()
    }
}
