const { app, BrowserWindow, desktopCapturer, session, ipcMain } = require("electron");
const path = require("node:path");

let selectedSourceId = null;
ipcMain.handle("atomos:list-sources", async () => {
  const sources = await desktopCapturer.getSources({
    types: ["screen", "window"], fetchWindowIcons: false,
    thumbnailSize: { width: 320, height: 180 }
  });
  return sources.map(source => ({
    id: source.id, name: source.name,
    type: source.id.startsWith("screen:") ? "Display" : "Window",
    thumbnail: source.thumbnail.toDataURL()
  }));
});
ipcMain.handle("atomos:select-source", async (_event, sourceId) => {
  if (typeof sourceId !== "string" || sourceId.length > 512) return false;
  selectedSourceId = sourceId;
  return true;
});

function createWindow() {
  const win = new BrowserWindow({
    width: 1100, height: 820, minWidth: 800, minHeight: 680,
    backgroundColor: "#090d18", title: "Atomos Mirror",
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true, nodeIntegration: false, sandbox: true
    }
  });
  win.loadFile("index.html");
}
app.whenReady().then(() => {
  session.defaultSession.setDisplayMediaRequestHandler(async (_request, callback) => {
    try {
      const sources = await desktopCapturer.getSources({ types: ["screen", "window"], fetchWindowIcons: false });
      const chosen = sources.find(source => source.id === selectedSourceId)
        || sources.find(source => source.id.startsWith("screen:")) || sources[0];
      if (!chosen) return callback({});
      callback({ video: chosen, audio: "loopback" });
    } catch { callback({}); }
  }, { useSystemPicker: false });
  createWindow();
  app.on("activate", () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});
app.on("window-all-closed", () => { if (process.platform !== "darwin") app.quit(); });
