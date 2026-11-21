const { contextBridge } = require("electron");
contextBridge.exposeInMainWorld("atomos", {
  platform: process.platform,
  version: process.versions.electron
});
