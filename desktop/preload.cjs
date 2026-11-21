const { contextBridge, ipcRenderer } = require("electron");
contextBridge.exposeInMainWorld("atomos", {
  platform: process.platform,
  version: process.versions.electron,
  listSources: () => ipcRenderer.invoke("atomos:list-sources"),
  selectSource: sourceId => ipcRenderer.invoke("atomos:select-source", sourceId)
});
