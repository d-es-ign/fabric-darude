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
const combinedChartCanvas = document.getElementById("combined-chart");

const TOPPLE_ZERO_FLOOR = 0.5;
const DEFAULT_THRESHOLD = 3;
const DEFAULT_MAX_TOPPLES = 64;
const CHART_WIDTH = 8000;
const CHART_FALLBACK_HEIGHT = 270;

let simulationOptions = normalizeSimulationOptions();
let simulation = runBasicEmitterSimulation(simulationOptions);
let currentStepIndex = simulation.finalStepIndex;
let originHeights = [];
let topplesPerStep = [];
let maxOriginHeight = 0;
let maxTopplesPerStep = 0;
let yAxisStepSize = 1;
let toppleTickValues = [TOPPLE_ZERO_FLOOR, 1];
let combinedChart = null;

const combinedChartOverlayPlugin = {
  id: "combinedChartOverlayPlugin",
  beforeDatasetsDraw(chart, _args, pluginOptions) {
    const { ctx, chartArea, scales } = chart;
    if (!chartArea || !scales?.x || !scales?.height || !scales?.topples) {
      return;
    }

    ctx.save();

    const y16 = scales.height.getPixelForValue(16);
    if (Number.isFinite(y16)) {
      ctx.strokeStyle = "hsl(20, 88%, 29%)";
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.moveTo(chartArea.left, y16);
      ctx.lineTo(chartArea.right, y16);
      ctx.stroke();
    }

    if (Number.isFinite(pluginOptions?.topplesLimit) && pluginOptions.topplesLimit > 0) {
      const yToppleLimit = scales.topples.getPixelForValue(pluginOptions.topplesLimit);
      if (Number.isFinite(yToppleLimit)) {
        ctx.strokeStyle = "hsl(185, 70%, 20%)";
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.moveTo(chartArea.left, yToppleLimit);
        ctx.lineTo(chartArea.right, yToppleLimit);
        ctx.stroke();
      }
    }

    ctx.restore();
  },
  afterDatasetsDraw(chart, _args, pluginOptions) {
    const { ctx, chartArea, scales } = chart;
    if (!chartArea || !scales?.x) {
      return;
    }

    if (!Number.isFinite(pluginOptions?.currentStep)) {
      return;
    }

    ctx.save();
    const x = scales.x.getPixelForValue(pluginOptions.currentStep);
    ctx.strokeStyle = "#7CFC00";
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(x, chartArea.top);
    ctx.lineTo(x, chartArea.bottom);
    ctx.stroke();
    ctx.restore();
  },
};

function nextPowerOfTwoAbove(value) {
  const normalizedValue = Math.max(1, value);
  return 2 ** Math.ceil(Math.log2(normalizedValue + 1));
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

function readInitialStepFromUrl(defaultStep, maxStep) {
  const params = new URLSearchParams(window.location.search);
  const rawStep = params.get("step");
  if (rawStep === null) {
    return defaultStep;
  }

  const parsedStep = Number.parseInt(rawStep, 10);
  if (!Number.isInteger(parsedStep)) {
    return defaultStep;
  }

  return Math.max(0, Math.min(maxStep, parsedStep));
}

function refreshDerivedSimulationState() {
  originHeights = simulation.snapshots.map((snapshot) => {
    const column = getColumnSnapshot(snapshot, 0, 0);
    return (column.stableSandBlocks * 16) + column.activeLayers;
  });
  topplesPerStep = simulation.snapshots.map((snapshot) => snapshot.stepSummary.topples);
  maxOriginHeight = Math.max(0, ...originHeights);
  maxTopplesPerStep = Math.max(0, ...topplesPerStep);
  yAxisStepSize = Math.min(4, Math.max(1, Math.ceil(maxOriginHeight / 8)));
  toppleTickValues = buildPowerOfTwoTickValues(
    nextPowerOfTwoAbove(simulationOptions.maxTopplesPerTick === 0 ? Math.max(1, maxTopplesPerStep) : simulationOptions.maxTopplesPerTick),
  );
}

function fitChartSize() {
  const chartHeight = combinedChartCanvas.parentElement ? combinedChartCanvas.parentElement.clientHeight : CHART_FALLBACK_HEIGHT;
  combinedChartCanvas.style.width = `${CHART_WIDTH}px`;
  combinedChartCanvas.style.height = `${chartHeight}px`;
}

function initializeChart() {
  if (typeof Chart === "undefined") {
    return;
  }

  if (combinedChart) {
    combinedChart.destroy();
    combinedChart = null;
  }

  combinedChart = new Chart(combinedChartCanvas, {
    plugins: [combinedChartOverlayPlugin],
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
          yAxisID: "height",
        },
        {
          label: "Topples per step",
          data: topplesPerStep.map((value, index) => ({ x: index, y: Math.max(TOPPLE_ZERO_FLOOR, value) })),
          borderColor: "#20b2aa",
          backgroundColor: "rgba(32, 178, 170, 0.2)",
          borderWidth: 2,
          pointRadius: 0,
          tension: 0.15,
          yAxisID: "topples",
        },
        {
          label: "Current step topples",
          data: [],
          borderColor: "#8b0000",
          backgroundColor: "#8b0000",
          pointRadius: 0,
          pointHoverRadius: 0,
          showLine: false,
          yAxisID: "topples",
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
        combinedChartOverlayPlugin: {
          currentStep: currentStepIndex,
          topplesLimit: simulationOptions.maxTopplesPerTick,
        },
        legend: { display: false },
        tooltip: {
          callbacks: {
            title: (items) => items.length === 0 ? "" : `Step ${items[0].parsed.x}`,
            label: (item) => item.dataset.yAxisID === "height"
              ? `Height: ${item.parsed.y}`
              : `Topples: ${topplesPerStep[item.dataIndex]}`,
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
            callback: (value) => Number.isInteger(Number(value)) ? value : "",
            font: { size: 10 },
          },
          grid: { color: "rgba(255,255,255,0.08)" },
        },
        height: {
          type: "linear",
          position: "left",
          title: { display: true, text: "Height", color: "#f2a23a", font: { size: 10 } },
          beginAtZero: true,
          suggestedMax: Math.ceil(maxOriginHeight / yAxisStepSize) * yAxisStepSize,
          ticks: { color: "#f2a23a", stepSize: yAxisStepSize, font: { size: 10 } },
          grid: { color: "rgba(255,255,255,0.08)" },
        },
        topples: {
          type: "logarithmic",
          position: "right",
          min: TOPPLE_ZERO_FLOOR,
          max: nextPowerOfTwoAbove(simulationOptions.maxTopplesPerTick === 0 ? Math.max(1, maxTopplesPerStep) : simulationOptions.maxTopplesPerTick),
          title: { display: true, text: "Topples", color: "#20b2aa", font: { size: 10 } },
          ticks: {
            color: "#20b2aa",
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
          grid: { drawOnChartArea: false, color: "rgba(32,178,170,0.2)" },
        },
      },
    },
  });
}

function updateChartData() {
  if (!combinedChart) {
    return;
  }

  combinedChart.data.datasets[0].data = originHeights.map((value, index) => ({ x: index, y: value }));
  combinedChart.data.datasets[1].data = topplesPerStep.map((value, index) => ({ x: index, y: Math.max(TOPPLE_ZERO_FLOOR, value) }));
  combinedChart.data.datasets[2].data = [];
  combinedChart.options.scales.x.max = simulation.finalStepIndex;
  combinedChart.options.scales.height.suggestedMax = Math.ceil(maxOriginHeight / yAxisStepSize) * yAxisStepSize;
  combinedChart.options.scales.height.ticks.stepSize = yAxisStepSize;
  combinedChart.options.scales.topples.max = nextPowerOfTwoAbove(simulationOptions.maxTopplesPerTick === 0 ? Math.max(1, maxTopplesPerStep) : simulationOptions.maxTopplesPerTick);
  combinedChart.options.plugins.combinedChartOverlayPlugin.currentStep = currentStepIndex;
  combinedChart.options.plugins.combinedChartOverlayPlugin.topplesLimit = simulationOptions.maxTopplesPerTick;
}

function stepIndexFromChartClick(chart, event) {
  if (!chart) {
    return null;
  }
  const tooltipElements = chart.tooltip?.getActiveElements?.() ?? [];
  if (tooltipElements.length > 0) {
    const point = chart.data.datasets[tooltipElements[0].datasetIndex]?.data?.[tooltipElements[0].index];
    const step = typeof point === "object" && point !== null ? point.x : tooltipElements[0].index;
    if (Number.isInteger(step)) {
      return Math.max(0, Math.min(simulation.finalStepIndex, step));
    }
  }
  if (!Chart.helpers?.getRelativePosition) {
    return null;
  }
  const xScale = chart.scales.x;
  const position = Chart.helpers.getRelativePosition(event, chart);
  const rawValue = xScale.getValueForPixel(position.x);
  if (!Number.isFinite(rawValue)) {
    return null;
  }
  return Math.max(0, Math.min(simulation.finalStepIndex, Math.round(rawValue)));
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

function setStatEntries(snapshot) {
  const snapshotColumns = Array.from(snapshot.columns.values());
  const snapshotTotalLayers = snapshotColumns.reduce((total, column) => total + (column.stableSandBlocks * 16) + column.activeLayers, 0);
  const snapshotOccupiedColumns = snapshotColumns.filter((column) => (column.stableSandBlocks * 16) + column.activeLayers > 0).length;
  const statEntries = [
    ["Step", String(snapshot.stepIndex)],
    ["Total layers", String(snapshotTotalLayers)],
    ["Occupied columns", String(snapshotOccupiedColumns)],
    ["Topples this step", String(snapshot.stepSummary.topples)],
    ["Cap hit", snapshot.stepSummary.capHit ? "yes" : "no"],
    ["2-cell short-circuit", snapshot.stepSummary.shortCircuitHit ? "yes" : "no"],
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
  stats.innerHTML = statEntries.map(([label, value]) => `<div><dt>${label}</dt><dd>${value}</dd></div>`).join("");
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
  if (!snapshot.stepSummary.invariants?.massConserved) warningItems.push("Mass conservation failed.");
  if (!snapshot.stepSummary.invariants?.noNegativeHeights) warningItems.push(`Negative height cells detected: ${snapshot.stepSummary.invariants.negativeHeightCells.length}.`);
  if (!snapshot.stepSummary.originPlacementIncrementOk) warningItems.push("Origin placement increment exceeded 1 before avalanche.");
  if (!snapshot.stepSummary.invariants?.topplesStrictlyDownhill) warningItems.push(`Invalid topple directions detected: ${snapshot.stepSummary.invariants.invalidToppleEvents.length}.`);
  if (!snapshot.stepSummary.invariants?.noTeleportation) warningItems.push(`Teleportation violations detected: ${snapshot.stepSummary.invariants.teleportationViolations.length}.`);
  if (!snapshot.stepSummary.invariants?.locallyStableIfCapNotHit) warningItems.push("Residual instability remained even though the topple cap was not hit.");
  warnings.innerHTML = warningItems.length === 0 ? "" : `
    <div class="warnings__title">Warnings</div>
    <ul class="warnings__list">${warningItems.map((item) => `<li>${item}</li>`).join("")}</ul>
  `;
}

function renderToppleLog(snapshot) {
  const events = snapshot.stepSummary.events ?? [];
  if (events.length === 0) {
    toppleLog.innerHTML = '<li class="topple-log__item">No topples.</li>';
    if (snapshot.stepSummary.shortCircuitHit) {
      toppleLog.innerHTML += '<li class="topple-log__item topple-log__item--termination">Direct 2-cell reversal short-circuit triggered.</li>';
    }
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
  if (snapshot.stepSummary.shortCircuitHit) {
    toppleLog.innerHTML += '<li class="topple-log__item topple-log__item--termination">Direct 2-cell reversal short-circuit triggered.</li>';
  }
}

function renderHeightmap(snapshot) {
  const { minX, maxX, minZ, maxZ } = snapshot.bounds;
  const headerCells = ["<th></th>"];
  for (let x = minX; x <= maxX; x += 1) headerCells.push(`<th>${x}</th>`);
  const rows = [`<tr>${headerCells.join("")}</tr>`];
  for (let z = minZ; z <= maxZ; z += 1) {
    const cells = [`<th>${z}</th>`];
    for (let x = minX; x <= maxX; x += 1) {
      const column = getColumnSnapshot(snapshot, x, z);
      const text = describeCell(column);
      const classNames = [];
      if (text === "0") classNames.push("heightmap__cell--zero", "td--zero");
      if (x === 0 && z === 0) classNames.push("td--emitter");
      const content = x === 0 && z === 0 ? `<strong>${text}</strong>` : text;
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
  if (!wrapper) return;
  heightmapScale.style.transform = "scale(1)";
  heightmapScale.style.marginTop = "0";
  const availableWidth = wrapper.clientWidth;
  const availableHeight = wrapper.clientHeight;
  const contentWidth = heightmap.offsetWidth;
  const contentHeight = heightmap.offsetHeight;
  if (availableWidth <= 0 || availableHeight <= 0 || contentWidth <= 0 || contentHeight <= 0) return;
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
  if (combinedChart) {
    combinedChart.options.plugins.combinedChartOverlayPlugin.currentStep = stepIndex;
    combinedChart.update("none");
  }
}

function regenerateSimulation(nextStepIndex = 0) {
  simulation = runBasicEmitterSimulation(simulationOptions);
  refreshDerivedSimulationState();
  currentStepIndex = Math.max(0, Math.min(simulation.finalStepIndex, nextStepIndex));
  syncSimulationControls();
  updateChartData();
  fitChartSize();
  const chartHeight = combinedChartCanvas.parentElement ? combinedChartCanvas.parentElement.clientHeight : CHART_FALLBACK_HEIGHT;
  if (combinedChart) combinedChart.resize(CHART_WIDTH, chartHeight);
  renderStep(currentStepIndex);
}

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
  if (optionsChanged) regenerateSimulation(Math.min(currentStepIndex, simulation.finalStepIndex));
}

combinedChartCanvas.addEventListener("click", (event) => {
  const nextStep = stepIndexFromChartClick(combinedChart, event);
  if (nextStep === null) return;
  renderStep(nextStep);
});

stepSlider.addEventListener("input", () => renderStep(Number.parseInt(stepSlider.value, 10)));
stepInput.addEventListener("change", () => {
  const nextValue = Number.parseInt(stepInput.value, 10);
  if (!Number.isInteger(nextValue)) {
    stepInput.value = String(currentStepIndex);
    return;
  }
  renderStep(Math.max(0, Math.min(simulation.finalStepIndex, nextValue)));
});
maxTopplesInput.addEventListener("change", updateSimulationOptionInputs);
thresholdInput.addEventListener("change", updateSimulationOptionInputs);
resetParametersButton.addEventListener("click", () => {
  maxTopplesInput.value = String(DEFAULT_MAX_TOPPLES);
  thresholdInput.value = String(DEFAULT_THRESHOLD);
  updateSimulationOptionInputs();
});
stepBackButton.addEventListener("click", () => renderStep(Math.max(0, currentStepIndex - 1)));
stepForwardButton.addEventListener("click", () => renderStep(Math.min(simulation.finalStepIndex, currentStepIndex + 1)));

window.addEventListener("keydown", (event) => {
  if (event.defaultPrevented) return;
  const target = event.target;
  if (target instanceof HTMLElement) {
    const tagName = target.tagName;
    if (tagName === "INPUT" || tagName === "TEXTAREA" || target.isContentEditable) return;
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

refreshDerivedSimulationState();
initializeChart();
fitChartSize();
{
  const chartHeight = combinedChartCanvas.parentElement ? combinedChartCanvas.parentElement.clientHeight : CHART_FALLBACK_HEIGHT;
  if (combinedChart) combinedChart.resize(CHART_WIDTH, chartHeight);
}
syncSimulationControls();
updateChartData();
currentStepIndex = readInitialStepFromUrl(currentStepIndex, simulation.finalStepIndex);
renderStep(currentStepIndex);

window.addEventListener("resize", fitHeightmap);
window.addEventListener("resize", () => {
  fitChartSize();
  const chartHeight = combinedChartCanvas.parentElement ? combinedChartCanvas.parentElement.clientHeight : CHART_FALLBACK_HEIGHT;
  if (combinedChart) combinedChart.resize(CHART_WIDTH, chartHeight);
});
