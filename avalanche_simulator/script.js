import { describeCell, getColumnSnapshot, runBasicEmitterSimulation } from "./simulator.mjs";

const stepSlider = document.getElementById("step-slider");
const stepBackButton = document.getElementById("step-back");
const stepForwardButton = document.getElementById("step-forward");
const stepLabel = document.getElementById("step-label");
const simulationStatus = document.getElementById("simulation-status");
const stats = document.getElementById("stats");
const heightmap = document.getElementById("heightmap");

const simulation = runBasicEmitterSimulation();
let currentStepIndex = simulation.finalStepIndex;

stepSlider.min = "0";
stepSlider.max = String(simulation.finalStepIndex);
stepSlider.step = "1";
stepSlider.value = String(currentStepIndex);

simulationStatus.textContent = simulation.blocked
  ? `Emitter blocked after ${simulation.finalStepIndex} placements.`
  : `Emitter did not block within ${simulation.finalStepIndex} simulated placements.`;

function setStatEntries(snapshot) {
  const snapshotColumns = Array.from(snapshot.columns.values());
  const snapshotTotalLayers = snapshotColumns.reduce((total, column) => total + (column.stableSandBlocks * 16) + column.activeLayers, 0);
  const snapshotOccupiedColumns = snapshotColumns.filter(column => (column.stableSandBlocks * 16) + column.activeLayers > 0).length;
  const statEntries = [
    ["Step", String(snapshot.stepIndex)],
    ["Total layers", String(snapshotTotalLayers)],
    ["Occupied columns", String(snapshotOccupiedColumns)],
    ["Topples this step", String(snapshot.stepSummary.topples)],
    ["Blocked", snapshot.stepSummary.blocked ? "yes" : "no"],
  ];

  stats.innerHTML = statEntries
    .map(([label, value]) => `<div><dt>${label}</dt><dd>${value}</dd></div>`)
    .join("");
}

function renderHeightmap(snapshot) {
  const { minX, maxX, minZ, maxZ } = snapshot.bounds;
  const headerCells = [`<th></th>`];
  for (let x = minX; x <= maxX; x += 1) {
    headerCells.push(`<th>${x}</th>`);
  }

  const rows = [`<tr>${headerCells.join("")}</tr>`];
  for (let z = minZ; z <= maxZ; z += 1) {
    const cells = [`<th>${z}</th>`];
    for (let x = minX; x <= maxX; x += 1) {
      const column = getColumnSnapshot(snapshot, x, z);
      const text = describeCell(column);
      const classNames = [];
      if (text === "0") {
        classNames.push("heightmap__cell--zero", "td--zero");
      }
      const isEmitter = x === 0 && z === 0;
      if (isEmitter) {
        classNames.push("td--emitter");
      }
      const content = isEmitter ? `<strong>${text}</strong>` : text;
      const classAttribute = classNames.length > 0 ? ` class="${classNames.join(" ")}"` : "";
      cells.push(`<td${classAttribute}>${content}</td>`);
    }
    rows.push(`<tr>${cells.join("")}</tr>`);
  }

  heightmap.innerHTML = rows.join("");
}

function renderStep(stepIndex) {
  currentStepIndex = stepIndex;
  stepSlider.value = String(stepIndex);
  stepLabel.textContent = `Step ${stepIndex} of ${simulation.finalStepIndex}`;
  stepBackButton.disabled = stepIndex <= 0;
  stepForwardButton.disabled = stepIndex >= simulation.finalStepIndex;

  const snapshot = simulation.snapshots[stepIndex];
  setStatEntries(snapshot);
  renderHeightmap(snapshot);
}

stepSlider.addEventListener("input", () => {
  renderStep(Number.parseInt(stepSlider.value, 10));
});

stepBackButton.addEventListener("click", () => {
  renderStep(Math.max(0, currentStepIndex - 1));
});

stepForwardButton.addEventListener("click", () => {
  renderStep(Math.min(simulation.finalStepIndex, currentStepIndex + 1));
});

renderStep(currentStepIndex);
