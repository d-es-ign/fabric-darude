import { describeCell, getColumnSnapshot, normalizeSimulationOptions, runBasicEmitterSimulation } from "./simulator.mjs";

const stepSlider = document.getElementById("step-slider");
const stepInput = document.getElementById("step-input");
const maxTopplesInput = document.getElementById("max-topples-input");
const thresholdInput = document.getElementById("threshold-input");
const resetParametersButton = document.getElementById("reset-parameters");
const stepBackButton = document.getElementById("step-back");
const stepForwardButton = document.getElementById("step-forward");
const stepTotal = document.getElementById("step-total");
const simulationStatus = document.getElementById("simulation-status");
const stats = document.getElementById("stats");
const warnings = document.getElementById("warnings");
const heightmap = document.getElementById("heightmap");
const heightmapScale = document.getElementById("heightmap-scale");
const toppleLog = document.getElementById("topple-log");
const originHeightChartCanvas = document.getElementById("origin-height-chart");
const topplesChartCanvas = document.getElementById("topples-chart");
const TOPPLE_ZERO_FLOOR = 0.5;

let simulationOptions = normalizeSimulationOptions();
let simulation = runBasicEmitterSimulation(simulationOptions);
let currentStepIndex = Math.min(21, simulation.finalStepIndex);
let originHeights = [];
let maxOriginHeight = 0;
let topplesPerStep = [];
let maxTopplesPerStep = 0;
let xAxisTickValues = [];
let yAxisStepSize = 1;
let toppleTickValues = [1];

function nextPowerOfTwoAbove(value) {
  const normalizedValue = Math.max(1, value);
  return 2 ** Math.ceil(Math.log2(normalizedValue + 1));
}

function buildXAxisTickValues(finalStepIndex) {
  const sectionCount = Math.min(12, Math.max(6, Math.floor(finalStepIndex / 40) + 1));
  const values = new Set([0, finalStepIndex]);
  for (let index = 1; index < sectionCount; index += 1) {
    values.add(Math.round((finalStepIndex * index) / sectionCount));
  }
  return Array.from(values).sort((left, right) => left - right);
}

function buildPowerOfTwoTickValues(maxValue) {
  const values = [TOPPLE_ZERO_FLOOR];
  let current = 1;
  while (current <= maxValue) {
    values.push(current);
    current *= 2;
  }
  return values;
}

function refreshDerivedSimulationState() {
  originHeights = simulation.snapshots.map((snapshot) => {
    const column = getColumnSnapshot(snapshot, 0, 0);
    return (column.stableSandBlocks * 16) + column.activeLayers;
  });
  maxOriginHeight = Math.max(0, ...originHeights);
  topplesPerStep = simulation.snapshots.map((snapshot) => snapshot.stepSummary.topples);
  maxTopplesPerStep = Math.max(0, ...topplesPerStep);
  xAxisTickValues = buildXAxisTickValues(simulation.finalStepIndex);
  yAxisStepSize = Math.min(4, Math.max(1, Math.ceil(maxOriginHeight / 8)));
  toppleTickValues = buildPowerOfTwoTickValues(
    nextPowerOfTwoAbove(simulationOptions.maxTopplesPerTick === 0 ? Math.max(1, maxTopplesPerStep) : simulationOptions.maxTopplesPerTick),
  );
}

refreshDerivedSimulationState();

function fitChartWidth() {
  const chartHeight = originHeightChartCanvas.parentElement ? originHeightChartCanvas.parentElement.clientHeight : 270;
  originHeightChartCanvas.style.height = `${chartHeight}px`;
  topplesChartCanvas.style.height = `${chartHeight}px`;
}

let originHeightChart = null;

const plottedTopples = topplesPerStep.map((value) => Math.max(TOPPLE_ZERO_FLOOR, value));

let topplesChart = null;

function initializeCharts() {
  if (typeof Chart === "undefined") {
    return;
  }

  try {
    originHeightChart = new Chart(originHeightChartCanvas, {
      type: "line",
      data: {
        datasets: [
          {
            label: "Origin height",
            data: originHeights.map((value, index) => ({ x: index, y: value })),
            borderColor: "#f2a23a",
            backgroundColor: "rgba(242, 162, 58, 0.2)",
            borderWidth: 2,
            pointRadius: 0,
            tension: 0.15,
          },
          {
            label: "Current step",
            data: [],
            borderColor: "#8b0000",
            backgroundColor: "#8b0000",
            pointRadius: 4,
            pointHoverRadius: 4,
            showLine: false,
          },
        ],
      },
      options: {
        animation: false,
        maintainAspectRatio: false,
        responsive: false,
        interaction: {
          mode: "nearest",
          axis: "x",
          intersect: false,
        },
        plugins: {
          legend: { display: false },
          tooltip: {
            callbacks: {
              title: (items) => items.length === 0 ? "" : `Step ${items[0].parsed.x}`,
              label: (item) => `Height: ${item.parsed.y}`,
            },
          },
        },
        scales: {
          x: {
            type: "linear",
            min: 0,
            max: simulation.finalStepIndex,
            title: { display: true, text: "Step", color: "#f4f4f4", font: { size: 10 } },
            ticks: {
              color: "#f4f4f4",
              autoSkip: false,
              callback: (value) => xAxisTickValues.includes(Number(value)) ? value : "",
              font: { size: 10 },
            },
            grid: { color: "rgba(255,255,255,0.08)" },
          },
          y: {
            title: { display: true, text: "Height", color: "#f4f4f4", font: { size: 10 } },
            beginAtZero: true,
            suggestedMax: Math.ceil(maxOriginHeight / yAxisStepSize) * yAxisStepSize,
            ticks: { color: "#f4f4f4", stepSize: yAxisStepSize, font: { size: 10 } },
            grid: { color: "rgba(255,255,255,0.08)" },
          },
        },
      },
    });

    topplesChart = new Chart(topplesChartCanvas, {
      type: "line",
      data: {
        datasets: [
          {
            label: "Topples per step",
            data: plottedTopples.map((value, index) => ({ x: index, y: value })),
            borderColor: "#20b2aa",
            backgroundColor: "rgba(32, 178, 170, 0.2)",
            borderWidth: 2,
            pointRadius: 0,
            tension: 0.15,
          },
          {
            label: "Current step",
            data: [],
            borderColor: "#8b0000",
            backgroundColor: "#8b0000",
            pointRadius: 4,
            pointHoverRadius: 4,
            showLine: false,
          },
        ],
      },
      options: {
        animation: false,
        maintainAspectRatio: false,
        responsive: false,
        interaction: {
          mode: "nearest",
          axis: "x",
          intersect: false,
        },
        plugins: {
          legend: { display: false },
          tooltip: {
            callbacks: {
              title: (items) => items.length === 0 ? "" : `Step ${items[0].parsed.x}`,
              label: (item) => `Topples: ${topplesPerStep[item.dataIndex]}`,
            },
          },
        },
        scales: {
          x: {
            type: "linear",
            min: 0,
            max: simulation.finalStepIndex,
            title: { display: true, text: "Step", color: "#f4f4f4", font: { size: 10 } },
            ticks: {
              color: "#f4f4f4",
              autoSkip: false,
              callback: (value) => xAxisTickValues.includes(Number(value)) ? value : "",
              font: { size: 10 },
            },
            grid: { color: "rgba(255,255,255,0.08)" },
          },
          y: {
            type: "logarithmic",
            title: { display: true, text: "Topples", color: "#f4f4f4", font: { size: 10 } },
            min: TOPPLE_ZERO_FLOOR,
            max: nextPowerOfTwoAbove(simulationOptions.maxTopplesPerTick === 0 ? Math.max(1, maxTopplesPerStep) : simulationOptions.maxTopplesPerTick),
            ticks: {
              color: "#f4f4f4",
              callback: (value) => {
                const numericValue = Number(value);
                if (!toppleTickValues.includes(numericValue)) {
                  return "";
                }
                return numericValue === TOPPLE_ZERO_FLOOR ? "0" : value;
              },
              autoSkip: false,
              font: { size: 10 },
            },
            afterBuildTicks: (axis) => {
              axis.ticks = toppleTickValues.map((value) => ({ value }));
            },
            grid: { color: "rgba(255,255,255,0.08)" },
          },
        },
      },
    });
  } catch (error) {
    console.error("Failed to initialize charts", error);
    originHeightChart = null;
    topplesChart = null;
  }
}

function syncSimulationControls() {
  stepSlider.max = String(simulation.finalStepIndex);
  stepInput.max = String(simulation.finalStepIndex);
  stepTotal.textContent = String(simulation.finalStepIndex);
  simulationStatus.textContent = simulation.blocked
    ? `Emitter blocked after ${simulation.finalStepIndex} placements.`
    : `Emitter did not block within ${simulation.finalStepIndex} simulated placements.`;
  maxTopplesInput.value = String(simulationOptions.maxTopplesPerTick);
  thresholdInput.value = String(simulationOptions.slopeThreshold);
}

function updateChartData() {
  if (!originHeightChart || !topplesChart) {
    return;
  }

  originHeightChart.data.datasets[0].data = originHeights.map((value, index) => ({ x: index, y: value }));
  originHeightChart.data.datasets[1].data = [{ x: currentStepIndex, y: originHeights[currentStepIndex] }];
  originHeightChart.options.scales.x.max = simulation.finalStepIndex;
  originHeightChart.options.scales.x.ticks.callback = (value) => xAxisTickValues.includes(Number(value)) ? value : "";
  originHeightChart.options.scales.y.suggestedMax = Math.ceil(maxOriginHeight / yAxisStepSize) * yAxisStepSize;
  originHeightChart.options.scales.y.ticks.stepSize = yAxisStepSize;

  topplesChart.data.datasets[0].data = topplesPerStep.map((value, index) => ({ x: index, y: Math.max(TOPPLE_ZERO_FLOOR, value) }));
  topplesChart.data.datasets[1].data = [{ x: currentStepIndex, y: Math.max(TOPPLE_ZERO_FLOOR, topplesPerStep[currentStepIndex]) }];
  topplesChart.options.scales.x.max = simulation.finalStepIndex;
  topplesChart.options.scales.x.ticks.callback = (value) => xAxisTickValues.includes(Number(value)) ? value : "";
  topplesChart.options.scales.y.max = nextPowerOfTwoAbove(simulationOptions.maxTopplesPerTick === 0 ? Math.max(1, maxTopplesPerStep) : simulationOptions.maxTopplesPerTick);
}

function regenerateSimulation(nextStepIndex = 0) {
  simulation = runBasicEmitterSimulation(simulationOptions);
  refreshDerivedSimulationState();
  currentStepIndex = Math.max(0, Math.min(simulation.finalStepIndex, nextStepIndex));
  syncSimulationControls();
  updateChartData();
  fitChartWidth();
  const chartHeight = originHeightChartCanvas.parentElement ? originHeightChartCanvas.parentElement.clientHeight : 270;
  if (originHeightChart && topplesChart) {
    originHeightChart.resize(8000, chartHeight);
    topplesChart.resize(8000, chartHeight);
  }
  renderStep(currentStepIndex);
}

initializeCharts();
fitChartWidth();
{
  const chartHeight = originHeightChartCanvas.parentElement ? originHeightChartCanvas.parentElement.clientHeight : 270;
  if (originHeightChart && topplesChart) {
    originHeightChart.resize(8000, chartHeight);
    topplesChart.resize(8000, chartHeight);
  }
}

originHeightChartCanvas.addEventListener("click", (event) => {
  if (!originHeightChart) {
    return;
  }
  const elements = originHeightChart.getElementsAtEventForMode(event, "nearest", { intersect: false }, true);
  if (elements.length === 0) {
    return;
  }

  const clickedPoint = originHeightChart.data.datasets[elements[0].datasetIndex]?.data?.[elements[0].index];
  const nextStep = typeof clickedPoint === "object" && clickedPoint !== null ? clickedPoint.x : elements[0].index;
  if (!Number.isInteger(nextStep)) {
    return;
  }

  renderStep(Math.max(0, Math.min(simulation.finalStepIndex, nextStep)));
});

topplesChartCanvas.addEventListener("click", (event) => {
  if (!topplesChart) {
    return;
  }
  const elements = topplesChart.getElementsAtEventForMode(event, "nearest", { intersect: false }, true);
  if (elements.length === 0) {
    return;
  }

  const clickedPoint = topplesChart.data.datasets[elements[0].datasetIndex]?.data?.[elements[0].index];
  const nextStep = typeof clickedPoint === "object" && clickedPoint !== null ? clickedPoint.x : elements[0].index;
  if (!Number.isInteger(nextStep)) {
    return;
  }

  renderStep(Math.max(0, Math.min(simulation.finalStepIndex, nextStep)));
});

stepSlider.min = "0";
stepSlider.max = String(simulation.finalStepIndex);
stepSlider.step = "1";
stepSlider.value = String(currentStepIndex);
stepInput.min = "0";
stepInput.max = String(simulation.finalStepIndex);
stepInput.step = "1";
stepInput.value = String(currentStepIndex);
stepTotal.textContent = String(simulation.finalStepIndex);

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
    ["Cap hit", snapshot.stepSummary.capHit ? "yes" : "no"],
    ["Residual unstable", String(snapshot.stepSummary.residualUnstableCells ?? 0)],
    ["Non-origin 16+", String((snapshot.stepSummary.nonOriginOverflowViolations ?? []).length)],
    ["Mass conserved", snapshot.stepSummary.invariants?.massConserved ? "yes" : "no"],
    ["No negative heights", snapshot.stepSummary.invariants?.noNegativeHeights ? "yes" : "no"],
    ["Origin +1 before avalanche", snapshot.stepSummary.originPlacementIncrementOk ? "yes" : "no"],
    ["Downhill topples", snapshot.stepSummary.invariants?.topplesStrictlyDownhill ? "yes" : "no"],
    ["No teleportation", snapshot.stepSummary.invariants?.noTeleportation ? "yes" : "no"],
    ["Stable if cap not hit", snapshot.stepSummary.invariants?.locallyStableIfCapNotHit ? "yes" : "no"],
    ["Blocked", snapshot.stepSummary.blocked ? "yes" : "no"],
  ];

  stats.innerHTML = statEntries
    .map(([label, value]) => `<div><dt>${label}</dt><dd>${value}</dd></div>`)
    .join("");
}

function renderWarnings(snapshot) {
  const warningItems = [];
  if (snapshot.stepSummary.capHit) {
    warningItems.push(`Topple cap hit with ${snapshot.stepSummary.residualUnstableCells} still-toppleable cells remaining.`);
  }

  const violations = snapshot.stepSummary.nonOriginOverflowViolations ?? [];
  if (violations.length > 0) {
    const examples = violations.slice(0, 3).map((violation) => `(${violation.location.x}, ${violation.location.z})=${violation.beforeActiveHeight}`).join(", ");
    warningItems.push(`Non-origin 16+ active violations: ${violations.length}${examples ? ` | latest: ${examples}` : ""}`);
  }

  if (!snapshot.stepSummary.invariants?.massConserved) {
    warningItems.push("Mass conservation failed.");
  }

  if (!snapshot.stepSummary.invariants?.noNegativeHeights) {
    warningItems.push(`Negative height cells detected: ${snapshot.stepSummary.invariants.negativeHeightCells.length}.`);
  }

  if (!snapshot.stepSummary.originPlacementIncrementOk) {
    warningItems.push("Origin placement increment exceeded 1 before avalanche.");
  }

  if (!snapshot.stepSummary.invariants?.topplesStrictlyDownhill) {
    warningItems.push(`Invalid topple directions detected: ${snapshot.stepSummary.invariants.invalidToppleEvents.length}.`);
  }

  if (!snapshot.stepSummary.invariants?.noTeleportation) {
    warningItems.push(`Teleportation violations detected: ${snapshot.stepSummary.invariants.teleportationViolations.length}.`);
  }

  if (!snapshot.stepSummary.invariants?.locallyStableIfCapNotHit) {
    warningItems.push("Residual instability remained even though the topple cap was not hit.");
  }

  if (warningItems.length === 0) {
    warnings.innerHTML = "";
    return;
  }

  warnings.innerHTML = `
    <div class="warnings__title">Warnings</div>
    <ul class="warnings__list">
      ${warningItems.map((item) => `<li>${item}</li>`).join("")}
    </ul>
  `;
}

function renderToppleLog(snapshot) {
  const events = snapshot.stepSummary.events ?? [];
  if (events.length === 0) {
    toppleLog.innerHTML = '<li class="topple-log__item">No topples.</li>';
    return;
  }

  toppleLog.innerHTML = events.map((event) => {
    if (event.type === "settle") {
      return `<li class="topple-log__item"><code>(${event.location.x}, ${event.location.z})</code> settled ${event.beforeActiveHeight} → ${event.afterStableSandBlocks * 16}${event.afterActiveHeight > 0 ? `+${event.afterActiveHeight}` : ""}</li>`;
    }

    if (event.type === "violation") {
      return `<li class="topple-log__item"><code>(${event.location.x}, ${event.location.z})</code> overflow violation at active ${event.beforeActiveHeight}</li>`;
    }

    const targets = event.targets
      .filter((target) => target.layers > 0)
      .map((target) => `<li><code>(${target.x}, ${target.z})</code> ${target.before} → ${target.after}</li>`)
      .join("");
    return `<li class="topple-log__item"><code>(${event.source.x}, ${event.source.z})</code> ${event.sourceHeight} → ${event.resultingSourceHeight}; moved ${event.transferLayers} to:${targets ? `<ul class="topple-log__targets">${targets}</ul>` : ""}</li>`;
  }).join("");
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
  fitHeightmap();
}

function fitHeightmap() {
  const wrapper = heightmapScale.parentElement;
  if (!wrapper) {
    return;
  }

  heightmapScale.style.transform = "scale(1)";
  heightmapScale.style.marginTop = "0";
  const availableWidth = wrapper.clientWidth;
  const availableHeight = wrapper.clientHeight;
  const contentWidth = heightmap.offsetWidth;
  const contentHeight = heightmap.offsetHeight;
  if (availableWidth <= 0 || availableHeight <= 0 || contentWidth <= 0 || contentHeight <= 0) {
    return;
  }

  const scale = Math.min(availableWidth / contentWidth, availableHeight / contentHeight);
  heightmapScale.style.transform = `scale(${scale})`;
}

function renderStep(stepIndex) {
  currentStepIndex = stepIndex;
  stepSlider.value = String(stepIndex);
  stepInput.value = String(stepIndex);
  stepBackButton.disabled = stepIndex <= 0;
  stepForwardButton.disabled = stepIndex >= simulation.finalStepIndex;

  const snapshot = simulation.snapshots[stepIndex];
  setStatEntries(snapshot);
  renderWarnings(snapshot);
  renderToppleLog(snapshot);
  renderHeightmap(snapshot);
  if (originHeightChart && topplesChart) {
    originHeightChart.data.datasets[1].data = [{ x: stepIndex, y: originHeights[stepIndex] }];
    originHeightChart.update("none");
    topplesChart.data.datasets[1].data = [{ x: stepIndex, y: Math.max(TOPPLE_ZERO_FLOOR, topplesPerStep[stepIndex]) }];
    topplesChart.update("none");
  }
}

stepSlider.addEventListener("input", () => {
  renderStep(Number.parseInt(stepSlider.value, 10));
});

stepInput.addEventListener("change", () => {
  const nextValue = Number.parseInt(stepInput.value, 10);
  if (!Number.isInteger(nextValue)) {
    stepInput.value = String(currentStepIndex);
    return;
  }
  renderStep(Math.max(0, Math.min(simulation.finalStepIndex, nextValue)));
});

function updateSimulationOptionInputs() {
  const nextOptions = normalizeSimulationOptions({
    maxTopplesPerTick: Number.parseInt(maxTopplesInput.value, 10),
    slopeThreshold: Number.parseInt(thresholdInput.value, 10),
  });

  const optionsChanged = nextOptions.maxTopplesPerTick !== simulationOptions.maxTopplesPerTick
    || nextOptions.slopeThreshold !== simulationOptions.slopeThreshold;
  simulationOptions = nextOptions;
  maxTopplesInput.value = String(simulationOptions.maxTopplesPerTick);
  thresholdInput.value = String(simulationOptions.slopeThreshold);
  if (optionsChanged) {
    regenerateSimulation(Math.min(currentStepIndex, simulation.finalStepIndex));
  }
}

maxTopplesInput.addEventListener("change", updateSimulationOptionInputs);
thresholdInput.addEventListener("change", updateSimulationOptionInputs);
resetParametersButton.addEventListener("click", () => {
  maxTopplesInput.value = "64";
  thresholdInput.value = "3";
  updateSimulationOptionInputs();
});

stepBackButton.addEventListener("click", () => {
  renderStep(Math.max(0, currentStepIndex - 1));
});

stepForwardButton.addEventListener("click", () => {
  renderStep(Math.min(simulation.finalStepIndex, currentStepIndex + 1));
});

window.addEventListener("keydown", (event) => {
  if (event.defaultPrevented) {
    return;
  }

  const target = event.target;
  if (target instanceof HTMLElement) {
    const tagName = target.tagName;
    if (tagName === "INPUT" || tagName === "TEXTAREA" || target.isContentEditable) {
      return;
    }
  }

  if (event.key === "ArrowLeft") {
    event.preventDefault();
    renderStep(Math.max(0, currentStepIndex - 1));
    return;
  }

  if (event.key === "ArrowRight") {
    event.preventDefault();
    renderStep(Math.min(simulation.finalStepIndex, currentStepIndex + 1));
  }
});

initializeCharts();
{
  fitChartWidth();
  const chartHeight = originHeightChartCanvas.parentElement ? originHeightChartCanvas.parentElement.clientHeight : 270;
  if (originHeightChart && topplesChart) {
    originHeightChart.resize(8000, chartHeight);
    topplesChart.resize(8000, chartHeight);
  }
}
syncSimulationControls();
updateChartData();
renderStep(currentStepIndex);

window.addEventListener("resize", fitHeightmap);
window.addEventListener("resize", () => {
  fitChartWidth();
  const chartHeight = originHeightChartCanvas.parentElement ? originHeightChartCanvas.parentElement.clientHeight : 270;
  if (originHeightChart && topplesChart) {
    originHeightChart.resize(8000, chartHeight);
    topplesChart.resize(8000, chartHeight);
  }
});
