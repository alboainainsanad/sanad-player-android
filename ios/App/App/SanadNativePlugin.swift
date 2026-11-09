import Foundation
import UIKit
import Capacitor

@objc(SanadNativePlugin)
public class SanadNativePlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "SanadNativePlugin"
    public let jsName = "SanadNative"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "info", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "play", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "addSubtitle", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "useMt", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "download", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "dlStatus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "dlRemove", returnType: CAPPluginReturnPromise)
    ]
    static weak var shared: SanadNativePlugin?
    weak var playerVC: SPPlayerViewController?

    public override func load() { SanadNativePlugin.shared = self }

    func emit(_ ev: String, _ data: [String: Any]) { notifyListeners(ev, data: data) }

    @objc func info(_ call: CAPPluginCall) {
        let b = UIScreen.main.nativeBounds
        call.resolve(["w": max(b.width, b.height), "h": min(b.width, b.height), "model": UIDevice.current.model, "sdk": UIDevice.current.systemVersion])
    }

    @objc func play(_ call: CAPPluginCall) {
        let opts = call.options as? [String: Any] ?? [:]
        DispatchQueue.main.async {
            guard let root = self.bridge?.viewController else { call.reject("no view"); return }
            let vc = SPPlayerViewController(opts: opts) { result in call.resolve(result) }
            vc.modalPresentationStyle = .fullScreen
            self.playerVC = vc
            root.present(vc, animated: true)
        }
    }

    @objc func useMt(_ call: CAPPluginCall) {
        DispatchQueue.main.async { self.playerVC?.useMt(); call.resolve() }
    }

    @objc func addSubtitle(_ call: CAPPluginCall) {
        let text = call.getString("text") ?? ""
        let key = call.getString("key") ?? ""
        let label = call.getString("label") ?? "عربي"
        DispatchQueue.main.async {
            self.playerVC?.addExternalSub(text: text, label: label, key: key)
            call.resolve()
        }
    }

    // ---------------- downloads (Documents/Sanad Player, visible in the Files app) ----------------
    @objc func download(_ call: CAPPluginCall) {
        guard let s = call.getString("url"), let url = URL(string: s) else { call.reject("bad url"); return }
        let title = (call.getString("title") ?? "video").replacingOccurrences(of: "[\\\\/:*?\"<>|]", with: "_", options: .regularExpression)
        let ext = call.getString("ext") ?? "mp4"
        let id = SPDownloads.shared.start(url: url, fileName: "\(title).\(ext)")
        call.resolve(["id": id, "file": "Sanad Player/\(title).\(ext)"])
    }

    @objc func dlStatus(_ call: CAPPluginCall) {
        let ids = call.getArray("ids", String.self) ?? []
        call.resolve(["list": ids.map { SPDownloads.shared.status($0) }])
    }

    @objc func dlRemove(_ call: CAPPluginCall) {
        SPDownloads.shared.remove(call.getString("id") ?? "")
        call.resolve()
    }
}

final class SPDownloads: NSObject, URLSessionDownloadDelegate {
    static let shared = SPDownloads()
    struct Item { var task: URLSessionDownloadTask?; var fileName: String; var got: Int64 = 0; var total: Int64 = 0; var status = "run"; var file: URL? }
    var items: [String: Item] = [:]
    let lock = NSLock()
    lazy var session: URLSession = {
        let c = URLSessionConfiguration.default
        c.httpAdditionalHeaders = ["User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"]
        c.timeoutIntervalForRequest = 60
        return URLSession(configuration: c, delegate: self, delegateQueue: nil)
    }()
    var dir: URL {
        let d = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Sanad Player", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }
    func start(url: URL, fileName: String) -> String {
        let t = session.downloadTask(with: url)
        let id = String(t.taskIdentifier) + "-" + String(Int(Date().timeIntervalSince1970))
        lock.lock(); items[id] = Item(task: t, fileName: fileName); lock.unlock()
        t.taskDescription = id
        t.resume()
        return id
    }
    func status(_ id: String) -> [String: Any] {
        lock.lock(); defer { lock.unlock() }
        guard let it = items[id] else {
            // maybe finished in a previous launch: look for a file with a known name is not possible without the name → report gone
            return ["id": id, "status": "gone"]
        }
        var o: [String: Any] = ["id": id, "status": it.status, "got": it.got, "total": it.total, "reason": 0]
        if let f = it.file { o["uri"] = f.absoluteString }
        return o
    }
    func remove(_ id: String) {
        lock.lock(); let it = items.removeValue(forKey: id); lock.unlock()
        it?.task?.cancel()
        if let f = it?.file { try? FileManager.default.removeItem(at: f) }
    }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let id = downloadTask.taskDescription else { return }
        lock.lock(); items[id]?.got = totalBytesWritten; items[id]?.total = max(0, totalBytesExpectedToWrite); lock.unlock()
    }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let id = downloadTask.taskDescription else { return }
        lock.lock(); let name = items[id]?.fileName ?? "video.mp4"; lock.unlock()
        let dest = dir.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: dest)
        do {
            try FileManager.default.moveItem(at: location, to: dest)
            lock.lock(); items[id]?.status = "done"; items[id]?.file = dest; lock.unlock()
        } catch {
            lock.lock(); items[id]?.status = "err"; lock.unlock()
        }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let id = task.taskDescription, error != nil else { return }
        lock.lock(); if items[id]?.status != "done" { items[id]?.status = "err" }; lock.unlock()
    }
}
