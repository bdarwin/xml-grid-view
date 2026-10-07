import { render } from "preact";
import { App } from "./App";
import { createHostBridge } from "./host";
import { WorkerClient } from "./workerClient";
import "./styles.css";

const host = createHostBridge();
const worker = new WorkerClient();
render(<App host={host} worker={worker} />, document.getElementById("root") ?? document.body);
if (worker.inline) host.post({ type: "error", message: "Web Workers are unavailable; parsing runs on the UI thread." });
