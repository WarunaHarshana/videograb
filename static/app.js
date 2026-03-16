// ══════════════════════════════════════
//  VideoGrab v4.0 — Frontend Logic
// ══════════════════════════════════════

const $ = (sel) => document.querySelector(sel);
const downloadCards = {};

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

    // Event listeners
    $("#urlInput").addEventListener("input", onUrlChange);
    $("#urlInput").addEventListener("keydown", (e) => {
        if (e.key === "Enter") startDownload();
    });
    $("#pasteBtn").addEventListener("click", pasteUrl);
    $("#downloadBtn").addEventListener("click", startDownload);
    $("#browseBtn").addEventListener("click", browseFolder);
    $("#openFolderBtn").addEventListener("click", openFolder);
    $("#clearBtn").addEventListener("click", clearCompleted);

    // Focus URL input
    $("#urlInput").focus();
});

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
            $("#urlInput").value = text.trim();
            onUrlChange();
            $("#urlInput").focus();
        }
    } catch {
        // Fallback: try using execCommand
        $("#urlInput").focus();
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

// ── Start Download ──
async function startDownload() {
    const url = $("#urlInput").value.trim();
    const savePath = $("#pathInput").value.trim();
    const useCookies = $("#cookieCheck").checked;

    if (!url) {
        shakeElement($("#urlInput").parentElement);
        return;
    }

    const btn = $("#downloadBtn");
    btn.disabled = true;

    try {
        const res = await fetch("/api/download", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ url, savePath, useCookies }),
        });

        const data = await res.json();

        if (!res.ok) {
            alert(data.error || "Download failed");
            btn.disabled = false;
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

        // Start listening for progress
        listenProgress(data.id, savePath);

    } catch (e) {
        alert("Failed to start download: " + e.message);
    }

    btn.disabled = false;
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
            <span class="dl-site-icon">${icon}</span>
            <span class="dl-site-name">${site}</span>
            <span class="dl-title">${shortUrl}</span>
            <button class="btn btn-cancel" onclick="cancelDownload('${id}')" title="Cancel">✕</button>
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

    list.insertBefore(card, list.firstChild);
    downloadCards[id] = card;
}

// ── Listen for progress via SSE ──
function listenProgress(dlId, savePath) {
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
                break;
            }

            case "title": {
                const titleEl = $(`#dl-${dlId} .dl-title`);
                if (titleEl) titleEl.textContent = evt.data.title;
                break;
            }

            case "status": {
                const statusEl = $(`#status-${dlId}`);
                if (statusEl) statusEl.textContent = evt.data.message;
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
                // Hide cancel, show actions
                const cancelBtn = $(`#dl-${dlId} .btn-cancel`);
                if (cancelBtn) cancelBtn.style.display = "none";

                // Add actions row
                const card = $(`#dl-${dlId}`);
                if (card) {
                    const actions = document.createElement("div");
                    actions.className = "dl-actions";
                    actions.innerHTML = `
                        <button class="btn btn-open-folder" onclick="openSaveFolder('${savePath}')">
                            📂 Open Folder
                        </button>
                    `;
                    card.appendChild(actions);
                }

                // Mark as complete
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
    };
}

// ── Cancel Download ──
async function cancelDownload(dlId) {
    try {
        await fetch("/api/cancel", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ id: dlId }),
        });

        // Animate card removal
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
