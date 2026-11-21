const { app, BrowserWindow, desktopCapturer, session } = require("electron");
const path = require("node:path");

function createWindow() {
  const win = new BrowserWindow({
    width: 1040, height: 760, minWidth: 760, minHeight: 620,
    backgroundColor: "#090d18",
    title: "Atomos Mirror",
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });
  win.loadFile("index.html");
}

app.whenReady().then(() => {
  session.defaultSession.setDisplayMediaRequestHandler(async (_request, callback) => {
    try {
      const sources = await desktopCapturer.getSources({ types: ["screen", "window"], fetchWindowIcons: false });
      const screen = sources.find(source => source.id.startsWith("screen:")) || sources[0];
      if (!screen) return callback({});
      callback({ video: screen, audio: "loopback" });
    } catch {
      callback({});
    }
  }, { useSystemPicker: false });
  createWindow();
  app.on("activate", () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});

app.on("window-all-closed", () => { if (process.platform !== "darwin") app.quit(); });
