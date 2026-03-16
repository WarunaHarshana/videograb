// ══════════════════════════════════════
//  VideoGrab v4.1 — Frontend Logic
// ══════════════════════════════════════

const $ = (sel) => document.querySelector(sel);
const downloadCards = {};
let activeCount = 0;
let batchMode = false;
let historyData = [];

// ── Init ──
document.addEventListener("DOMContentLoaded", async () => {
    // Load server info
    try {
        const res = await fetch("/api/info");
        const info = await res.json();
        $("#pathInput").value = info.savePath || "";
        renderTools(info.tools);
    } catch (e) {
        console.error("Failed to load info:", e);
    }

    // Theme
    initTheme();

    // Event listeners
    $("#urlInput").addEventListener("input", onUrlChange);
    $("#urlInput").addEventListener("keydown", (e) => {
        if (e.key === "Enter") startDownload();
    });
    $("#pasteBtn").addEventListener("click", pasteUrl);
    $("#downloadBtn").addEventListener("click", startDownload);
    $("#browseBtn").addEventListener("click", browseFolder);
    $("#openFolderBtn")?.addEventListener("click", openFolder);
    $("#clearBtn").addEventListener("click", clearCompleted);
    $("#historyBtn").addEventListener("click", toggleHistory);
    $("#closeHistoryBtn").addEventListener("click", toggleHistory);
    $("#batchToggle").addEventListener("click", toggleBatchMode);
    $("#settingsToggle").addEventListener("click", toggleSettings);
    $("#themeBtn").addEventListener("click", toggleTheme);
    $("#historySearch").addEventListener("input", filterHistory);
    $("#exportHistoryBtn").addEventListener("click", exportHistory);

    // Keyboard shortcuts
    document.addEventListener("keydown", handleKeyboard);

    // Drag-and-drop support
    setupDragDrop();

    // Global Ctrl+V paste handler
    document.addEventListener("paste", onGlobalPaste);

    // Focus URL input
    $("#urlInput").focus();
});

// ── Theme ──
function initTheme() {
    const saved = localStorage.getItem("videograb-theme");
    if (saved === "light") {
        document.documentElement.classList.add("light-theme");
        $("#themeBtn").textContent = "☀️";
    }
}

function toggleTheme() {
    const isLight = document.documentElement.classList.toggle("light-theme");
    localStorage.setItem("videograb-theme", isLight ? "light" : "dark");
    $("#themeBtn").textContent = isLight ? "☀️" : "🌙";
}

// ── Keyboard Shortcuts ──
function handleKeyboard(e) {
    // Ctrl+Enter — start download
    if (e.ctrlKey && e.key === "Enter") {
        e.preventDefault();
        startDownload();
        return;
    }
    // Ctrl+Shift+H — toggle history
    if (e.ctrlKey && e.shiftKey && e.key === "H") {
        e.preventDefault();
        toggleHistory();
        return;
    }
    // Escape — close panels
    if (e.key === "Escape") {
        const histSection = $("#historySection");
        if (histSection.style.display !== "none") {
            toggleHistory();
        }
        const settingsPanel = $("#settingsPanel");
        if (settingsPanel.style.display !== "none") {
            toggleSettings();
        }
    }
}

// ── Batch Mode ──
function toggleBatchMode() {
    batchMode = !batchMode;
    const btn = $("#batchToggle");
    const singleRow = $("#singleUrlRow");
    const batchRow = $("#batchUrlRow");

    btn.classList.toggle("active", batchMode);
    singleRow.style.display = batchMode ? "none" : "";
    batchRow.style.display = batchMode ? "" : "none";

    if (batchMode) {
        $("#batchUrlInput").focus();
    } else {
        $("#urlInput").focus();
    }
}

// ── Settings Panel ──
function toggleSettings() {
    const panel = $("#settingsPanel");
    const arrow = $("#settingsArrow");
    const isVisible = panel.style.display !== "none";
    panel.style.display = isVisible ? "none" : "";
    arrow.textContent = isVisible ? "▶" : "▼";
}

// ── Toast Notifications ──
function showToast(message, type = "info") {
    const container = $("#toastContainer");
    const toast = document.createElement("div");
    toast.className = `toast toast-${type}`;
    toast.textContent = message;
    container.appendChild(toast);

    // Trigger animation
    requestAnimationFrame(() => toast.classList.add("show"));

    // Auto-dismiss
    setTimeout(() => {
        toast.classList.remove("show");
        toast.classList.add("hide");
        setTimeout(() => toast.remove(), 300);
    }, 4000);
}

// ── Drag & Drop ──
function setupDragDrop() {
    const app = $(".app");
    let dragCounter = 0;

    app.addEventListener("dragenter", (e) => {
        e.preventDefault();
        dragCounter++;
        app.classList.add("drag-over");
    });

    app.addEventListener("dragleave", (e) => {
        e.preventDefault();
        dragCounter--;
        if (dragCounter <= 0) {
            dragCounter = 0;
            app.classList.remove("drag-over");
        }
    });

    app.addEventListener("dragover", (e) => {
        e.preventDefault();
    });

    app.addEventListener("drop", (e) => {
        e.preventDefault();
        dragCounter = 0;
        app.classList.remove("drag-over");

        const uriList = e.dataTransfer.getData("text/uri-list");
        const text = e.dataTransfer.getData("text/plain");
        const url = uriList || text;

        if (url && /^https?:\/\//i.test(url.trim())) {
            if (batchMode) {
                const ta = $("#batchUrlInput");
                ta.value = (ta.value ? ta.value + "\n" : "") + url.trim();
            } else {
                $("#urlInput").value = url.trim();
                onUrlChange();
            }
            startDownload();
        }
    });
}

// ── Global Paste (Ctrl+V anywhere on page) ──
function onGlobalPaste(e) {
    const tag = document.activeElement?.tagName;
    if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return;

    const text = e.clipboardData?.getData("text/plain")?.trim();
    if (text && /^https?:\/\//i.test(text)) {
        e.preventDefault();
        if (batchMode) {
            const ta = $("#batchUrlInput");
            ta.value = (ta.value ? ta.value + "\n" : "") + text;
            ta.focus();
        } else {
            $("#urlInput").value = text;
            onUrlChange();
            $("#urlInput").focus();
        }
    }
}

// ── Active download count ──
function updateActiveCount(delta) {
    activeCount = Math.max(0, activeCount + delta);
    const badge = $("#activeCount");
    if (badge) {
        badge.textContent = activeCount;
        badge.style.display = activeCount > 0 ? "" : "none";
    }
}

// ── Notifications ──
function requestNotificationPermission() {
    if ("Notification" in window && Notification.permission === "default") {
        Notification.requestPermission();
    }
}

function showNotification(title, body) {
    if ("Notification" in window && Notification.permission === "granted") {
        new Notification(title, { body, icon: "/static/icon.png" });
    }
}

// ── Tools status ──
function renderTools(tools) {
    const container = $("#toolsStatus");
    for (const [name, ok] of Object.entries(tools)) {
        const badge = document.createElement("span");
        badge.className = `tool-badge ${ok ? "ok" : "missing"}`;
        badge.textContent = `${ok ? "✓" : "✗"} ${name}`;
        container.appendChild(badge);
    }
}

// ── URL Detection ──
function detectSite(url) {
    const u = url.toLowerCase();
    if (u.includes("youtube") || u.includes("youtu.be")) return ["▶", "YouTube"];
    if (u.includes("vimeo")) return ["◉", "Vimeo"];
    if (u.includes("facebook") || u.includes("fb.watch")) return ["f", "Facebook"];
    if (u.includes("instagram")) return ["◎", "Instagram"];
    if (u.includes("tiktok")) return ["♪", "TikTok"];
    if (u.includes("twitter") || u.includes("x.com")) return ["✦", "Twitter/X"];
    if (u.includes("twitch")) return ["◆", "Twitch"];
    if (u.includes("bilibili")) return ["▶", "Bilibili"];
    if (u.includes("dailymotion")) return ["◉", "Dailymotion"];
    if (u.includes("porn") || u.includes("xvideos") || u.includes("xhamster") || u.includes("xnxx")) return ["🔞", "Adult"];
    if (u.includes("keep2share") || u.includes("tezfiles") || u.includes("rapidgator")) return ["📁", "File Host"];
    if (u.includes("mega.nz")) return ["📁", "MEGA"];
    if (u.includes("mediafire")) return ["📁", "MediaFire"];
    if (u.includes("gofile")) return ["📁", "GoFile"];
    if (u.includes("drive.google")) return ["📁", "Google Drive"];
    if (u.includes("dropbox")) return ["📁", "Dropbox"];
    if (/\.(mp4|mkv|avi|mov|zip|rar)(\?|$)/.test(u)) return ["📎", "Direct"];
    return ["◉", ""];
}

function onUrlChange() {
    const url = $("#urlInput").value.trim();
    const [icon, site] = detectSite(url);
    $("#urlIcon").textContent = icon;
    const siteEl = $("#urlSite");
    if (site) {
        siteEl.textContent = site;
        siteEl.classList.add("visible");
    } else {
        siteEl.classList.remove("visible");
    }
}

// ── Paste ──
async function pasteUrl() {
    try {
        const text = await navigator.clipboard.readText();
        if (text) {
            if (batchMode) {
                const ta = $("#batchUrlInput");
                ta.value = (ta.value ? ta.value + "\n" : "") + text.trim();
                ta.focus();
            } else {
                $("#urlInput").value = text.trim();
                onUrlChange();
                $("#urlInput").focus();
            }
        }
    } catch {
        if (batchMode) {
            $("#batchUrlInput").focus();
        } else {
            $("#urlInput").focus();
        }
        document.execCommand("paste");
    }
}

// ── Browse Folder ──
async function browseFolder() {
    const currentPath = $("#pathInput").value.trim();
    try {
        const res = await fetch("/api/browse", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ path: currentPath }),
        });
        const data = await res.json();
        if (data.path) {
            $("#pathInput").value = data.path;
        }
    } catch (e) {
        console.error("Failed to browse folder:", e);
    }
}

// ── Open Folder ──
async function openFolder() {
    const path = $("#pathInput").value.trim();
    try {
        await fetch("/api/open-folder", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ path }),
        });
    } catch (e) {
        console.error("Failed to open folder:", e);
    }
}

// ── Collect options from UI ──
function getOptions() {
    return {
        savePath: $("#pathInput").value.trim(),
        useCookies: $("#cookieCheck").checked,
        format: $("#formatSelect").value,
        cookieBrowser: $("#cookieBrowser").value,
        writeSubs: $("#subsCheck").checked,
        subLangs: $("#subLangSelect").value,
        noCertCheck: $("#noCertCheck").checked,
        playlist: $("#playlistCheck").checked,
        speedLimit: $("#speedLimitSelect").value,
        outputFormat: $("#outputFormatSelect").value,
        proxy: $("#proxyInput").value.trim(),
    };
}

// ── Start Download ──
async function startDownload() {
    const btn = $("#downloadBtn");
    btn.disabled = true;

    try {
        if (batchMode) {
            await startBatchDownload();
        } else {
            await startSingleDownload();
        }
    } catch (e) {
        showToast("Failed to start download: " + e.message, "error");
    }

    btn.disabled = false;
}

async function startSingleDownload() {
    const url = $("#urlInput").value.trim();
    if (!url) {
        shakeElement($("#urlInput").parentElement);
        return;
    }

    const opts = getOptions();

    const res = await fetch("/api/download", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ url, ...opts }),
    });

    const data = await res.json();

    if (!res.ok) {
        showToast(data.error || "Download failed", "error");
        return;
    }

    // Clear URL
    $("#urlInput").value = "";
    onUrlChange();

    // Hide empty state
    const empty = $("#emptyState");
    if (empty) empty.style.display = "none";

    // Create download card
    createCard(data);

    // Track active count
    updateActiveCount(1);

    // Start listening for progress
    listenProgress(data.id, opts.savePath);

    // Request notification permission on first download
    requestNotificationPermission();
}

async function startBatchDownload() {
    const text = $("#batchUrlInput").value.trim();
    if (!text) {
        shakeElement($("#batchUrlInput"));
        return;
    }

    const urls = text.split("\n").map(u => u.trim()).filter(u => u && /^https?:\/\//i.test(u));
    if (urls.length === 0) {
        showToast("No valid URLs found", "error");
        return;
    }

    const opts = getOptions();

    const res = await fetch("/api/download", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ urls, ...opts }),
    });

    const data = await res.json();

    if (!res.ok) {
        showToast(data.error || "Batch download failed", "error");
        return;
    }

    // Clear batch input
    $("#batchUrlInput").value = "";

    // Hide empty state
    const empty = $("#emptyState");
    if (empty) empty.style.display = "none";

    // Process results
    let started = 0;
    let errors = 0;
    for (const result of (data.results || [])) {
        if (result.error) {
            errors++;
            showToast(`${result.url.substring(0, 40)}... — ${result.error}`, "warning");
        } else {
            started++;
            createCard(result);
            updateActiveCount(1);
            listenProgress(result.id, opts.savePath);
        }
    }

    if (started > 0) showToast(`Started ${started} download(s)`, "success");
    if (errors > 0) showToast(`${errors} URL(s) skipped`, "warning");

    requestNotificationPermission();
}

// ── Create Card ──
function createCard({ id, icon, site, url }) {
    const list = $("#downloadsList");

    const card = document.createElement("div");
    card.className = "dl-card";
    card.id = `dl-${id}`;

    const shortUrl = url.length > 55 ? url.substring(0, 55) + "..." : url;

    card.innerHTML = `
        <div class="dl-top">
            <span class="dl-site-icon"></span>
            <span class="dl-site-name"></span>
            <span class="dl-title"></span>
            <button class="btn btn-cancel" title="Cancel">✕</button>
        </div>
        <div class="dl-progress-wrap">
            <div class="dl-progress-bar" id="bar-${id}"></div>
        </div>
        <div class="dl-stats">
            <span class="dl-status" id="status-${id}">Starting...</span>
            <span class="dl-speed" id="speed-${id}"></span>
            <span class="dl-size" id="size-${id}"></span>
            <span class="dl-eta" id="eta-${id}"></span>
            <span class="dl-percent" id="pct-${id}">0%</span>
        </div>
    `;

    card.querySelector(".dl-site-icon").textContent = icon;
    card.querySelector(".dl-site-name").textContent = site;
    card.querySelector(".dl-title").textContent = shortUrl;
    card.querySelector(".btn-cancel").addEventListener("click", () => cancelDownload(id));

    list.insertBefore(card, list.firstChild);
    downloadCards[id] = card;
}

// ── Listen for progress via SSE (with reconnection) ──
function listenProgress(dlId, savePath) {
    let retryCount = 0;
    const maxRetries = 3;
    let title = "";

    function connect() {
        const source = new EventSource(`/api/progress/${dlId}`);

        source.onmessage = (e) => {
            const evt = JSON.parse(e.data);

            switch (evt.type) {
                case "progress": {
                    const pct = evt.data.percent;
                    const bar = $(`#bar-${dlId}`);
                    if (bar) bar.style.width = pct + "%";
                    const pctEl = $(`#pct-${dlId}`);
                    if (pctEl) pctEl.textContent = pct.toFixed(1) + "%";
                    if (evt.data.speed) {
                        const speedEl = $(`#speed-${dlId}`);
                        if (speedEl) speedEl.textContent = evt.data.speed;
                    }
                    if (evt.data.size) {
                        const sizeEl = $(`#size-${dlId}`);
                        if (sizeEl) sizeEl.textContent = evt.data.size;
                    }
                    if (evt.data.eta) {
                        const etaEl = $(`#eta-${dlId}`);
                        if (etaEl) etaEl.textContent = "ETA " + evt.data.eta;
                    }
                    const statusEl = $(`#status-${dlId}`);
                    if (statusEl) statusEl.textContent = "Downloading";
                    retryCount = 0;
                    break;
                }

                case "title": {
                    title = evt.data.title;
                    const titleEl = $(`#dl-${dlId} .dl-title`);
                    if (titleEl) titleEl.textContent = evt.data.title;
                    break;
                }

                case "status": {
                    const statusEl = $(`#status-${dlId}`);
                    if (statusEl) statusEl.textContent = evt.data.message;
                    break;
                }

                case "playlist": {
                    const statusEl = $(`#status-${dlId}`);
                    if (statusEl) statusEl.textContent = `Item ${evt.data.current} of ${evt.data.total}`;
                    break;
                }

                case "complete": {
                    const bar = $(`#bar-${dlId}`);
                    if (bar) {
                        bar.style.width = "100%";
                        bar.classList.add("complete");
                    }
                    const pctEl = $(`#pct-${dlId}`);
                    if (pctEl) {
                        pctEl.textContent = "100%";
                        pctEl.classList.add("complete");
                    }
                    const statusEl = $(`#status-${dlId}`);
                    if (statusEl) {
                        statusEl.textContent = "Done ✓";
                        statusEl.classList.add("success");
                    }
                    const cancelBtn = $(`#dl-${dlId} .btn-cancel`);
                    if (cancelBtn) cancelBtn.style.display = "none";

                    const card = $(`#dl-${dlId}`);
                    if (card) {
                        const actions = document.createElement("div");
                        actions.className = "dl-actions";
                        const btn = document.createElement("button");
                        btn.className = "btn btn-open-folder";
                        btn.textContent = "Open Folder";
                        btn.addEventListener("click", () => openSaveFolder(savePath));
                        actions.appendChild(btn);
                        card.appendChild(actions);
                    }

                    showNotification("Download complete", title || "Video downloaded");
                    showToast(`Downloaded: ${title || "video"}`, "success");

                    updateActiveCount(-1);
                    if (downloadCards[dlId]) downloadCards[dlId].dataset.finished = "true";
                    source.close();
                    break;
                }

                case "error": {
                    const bar = $(`#bar-${dlId}`);
                    if (bar) bar.classList.add("error");
                    const statusEl = $(`#status-${dlId}`);
                    if (statusEl) {
                        statusEl.textContent = evt.data.message;
                        statusEl.classList.add("error");
                    }
                    const cancelBtn = $(`#dl-${dlId} .btn-cancel`);
                    if (cancelBtn) cancelBtn.style.display = "none";
                    showToast(evt.data.message, "error");
                    updateActiveCount(-1);
                    if (downloadCards[dlId]) downloadCards[dlId].dataset.finished = "true";
                    source.close();
                    break;
                }

                case "cancelled":
                case "removed": {
                    source.close();
                    break;
                }
            }
        };

        source.onerror = () => {
            source.close();
            retryCount++;
            if (retryCount <= maxRetries) {
                const statusEl = $(`#status-${dlId}`);
                if (statusEl) statusEl.textContent = `Reconnecting (${retryCount}/${maxRetries})...`;
                setTimeout(connect, 2000);
            } else {
                const statusEl = $(`#status-${dlId}`);
                if (statusEl) {
                    statusEl.textContent = "Connection lost";
                    statusEl.classList.add("error");
                }
            }
        };
    }

    connect();
}

// ── Cancel Download ──
async function cancelDownload(dlId) {
    try {
        await fetch("/api/cancel", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ id: dlId }),
        });

        updateActiveCount(-1);

        const card = $(`#dl-${dlId}`);
        if (card) {
            card.classList.add("removing");
            setTimeout(() => {
                card.remove();
                delete downloadCards[dlId];
                checkEmpty();
            }, 300);
        }
    } catch (e) {
        console.error("Cancel failed:", e);
    }
}

// ── Open save folder ──
async function openSaveFolder(path) {
    try {
        await fetch("/api/open-folder", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ path }),
        });
    } catch (e) {
        console.error("Failed to open folder:", e);
    }
}

// ── Clear completed ──
function clearCompleted() {
    Object.entries(downloadCards).forEach(([id, card]) => {
        if (card.dataset.finished === "true") {
            card.classList.add("removing");
            setTimeout(() => {
                card.remove();
                delete downloadCards[id];
                checkEmpty();
            }, 300);
        }
    });
}

// ── Check if downloads list is empty ──
function checkEmpty() {
    const empty = $("#emptyState");
    if (!empty) return;
    const hasCards = Object.keys(downloadCards).length > 0;
    empty.style.display = hasCards ? "none" : "block";
}

// ── History Panel ──
async function toggleHistory() {
    const section = $("#historySection");
    const downloadsSection = $(".downloads-section");
    const isVisible = section.style.display !== "none";

    if (isVisible) {
        section.style.display = "none";
        downloadsSection.style.display = "";
    } else {
        section.style.display = "";
        downloadsSection.style.display = "none";
        await loadHistory();
    }
}

async function loadHistory() {
    const list = $("#historyList");
    list.innerHTML = '<div class="history-loading">Loading...</div>';

    try {
        const res = await fetch("/api/history");
        historyData = await res.json();
        renderHistory(historyData);
    } catch (e) {
        list.innerHTML = '<div class="history-empty">Failed to load history</div>';
    }
}

function renderHistory(data) {
    const list = $("#historyList");

    if (!data.length) {
        list.innerHTML = '<div class="history-empty">No downloads yet</div>';
        return;
    }

    list.innerHTML = "";
    for (const entry of data.slice().reverse()) {
        const item = document.createElement("div");
        item.className = `history-item history-${entry.status}`;

        const time = new Date(entry.time);
        const timeStr = time.toLocaleDateString() + " " + time.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });

        const titleEl = document.createElement("span");
        titleEl.className = "history-title";
        titleEl.textContent = entry.title || entry.url;
        titleEl.title = entry.url;

        const statusEl = document.createElement("span");
        statusEl.className = `history-status history-status-${entry.status}`;
        statusEl.textContent = entry.status === "success" ? "✓" : entry.status === "failed" ? "✗" : "!";

        const timeEl = document.createElement("span");
        timeEl.className = "history-time";
        timeEl.textContent = timeStr;

        item.appendChild(statusEl);
        item.appendChild(titleEl);
        item.appendChild(timeEl);

        item.style.cursor = "pointer";
        item.title = "Click to download again";
        item.addEventListener("click", () => {
            $("#urlInput").value = entry.url;
            onUrlChange();
            toggleHistory();
            $("#urlInput").focus();
        });

        list.appendChild(item);
    }
}

function filterHistory() {
    const query = $("#historySearch").value.toLowerCase().trim();
    if (!query) {
        renderHistory(historyData);
        return;
    }
    const filtered = historyData.filter(e =>
        (e.title || "").toLowerCase().includes(query) ||
        (e.url || "").toLowerCase().includes(query)
    );
    renderHistory(filtered);
}

function exportHistory() {
    if (!historyData.length) {
        showToast("No history to export", "info");
        return;
    }
    const blob = new Blob([JSON.stringify(historyData, null, 2)], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `videograb-history-${new Date().toISOString().slice(0, 10)}.json`;
    a.click();
    URL.revokeObjectURL(url);
    showToast("History exported", "success");
}

// ── Shake animation ──
function shakeElement(el) {
    el.style.animation = "none";
    el.offsetHeight; // trigger reflow
    el.style.animation = "shake 0.4s ease";
    setTimeout(() => { el.style.animation = ""; }, 400);
}

// Add shake keyframes
const style = document.createElement("style");
style.textContent = `
    @keyframes shake {
        0%, 100% { transform: translateX(0); }
        20% { transform: translateX(-6px); }
        40% { transform: translateX(6px); }
        60% { transform: translateX(-4px); }
        80% { transform: translateX(4px); }
    }
`;
document.head.appendChild(style);
