import { describeCell, getColumnSnapshot, runBasicEmitterSimulation } from "./simulator.mjs";

const stepSlider = document.getElementById("step-slider");
const stepInput = document.getElementById("step-input");
const stepBackButton = document.getElementById("step-back");
const stepForwardButton = document.getElementById("step-forward");
const stepTotal = document.getElementById("step-total");
const simulationStatus = document.getElementById("simulation-status");
const stats = document.getElementById("stats");
const heightmap = document.getElementById("heightmap");
const heightmapScale = document.getElementById("heightmap-scale");
const chartScroll = document.querySelector(".chart-scroll");
const originHeightChartCanvas = document.getElementById("origin-height-chart");

const simulation = runBasicEmitterSimulation();
let currentStepIndex = simulation.finalStepIndex;
const originHeights = simulation.snapshots.map((snapshot) => {
  const column = getColumnSnapshot(snapshot, 0, 0);
  return (column.stableSandBlocks * 16) + column.activeLayers;
});
const maxOriginHeight = Math.max(...originHeights);

function buildXAxisTickValues(finalStepIndex) {
  const sectionCount = Math.min(12, Math.max(6, Math.floor(finalStepIndex / 40) + 1));
  const values = new Set([0, finalStepIndex]);
  for (let index = 1; index < sectionCount; index += 1) {
    values.add(Math.round((finalStepIndex * index) / sectionCount));
  }
  return Array.from(values).sort((left, right) => left - right);
}

const xAxisTickValues = buildXAxisTickValues(simulation.finalStepIndex);
const yAxisStepSize = Math.min(4, Math.max(1, Math.ceil(maxOriginHeight / 8)));

function fitChartWidth() {
  const chartHeight = chartScroll ? chartScroll.clientHeight : 420;
  originHeightChartCanvas.style.width = "8000px";
  originHeightChartCanvas.style.height = `${chartHeight}px`;
}

const originHeightChart = new Chart(originHeightChartCanvas, {
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
      legend: {
        display: false,
      },
      tooltip: {
        callbacks: {
          title: (items) => {
            if (items.length === 0) {
              return "";
            }
            return `Step ${items[0].parsed.x}`;
          },
          label: (item) => `Height: ${item.parsed.y}`,
        },
      },
    },
    scales: {
      x: {
        type: "linear",
        min: 0,
        max: simulation.finalStepIndex,
        title: {
          display: true,
          text: "Step",
          color: "#f4f4f4",
          font: {
            size: 10,
          },
        },
        ticks: {
          color: "#f4f4f4",
          autoSkip: false,
          callback: (value) => xAxisTickValues.includes(Number(value)) ? value : "",
          font: {
            size: 10,
          },
        },
        grid: {
          color: "rgba(255,255,255,0.08)",
        },
      },
      y: {
        title: {
          display: true,
          text: "Height",
          color: "#f4f4f4",
          font: {
            size: 10,
          },
        },
        beginAtZero: true,
        suggestedMax: Math.ceil(maxOriginHeight / yAxisStepSize) * yAxisStepSize,
        ticks: {
          color: "#f4f4f4",
          stepSize: yAxisStepSize,
          font: {
            size: 10,
          },
        },
        grid: {
          color: "rgba(255,255,255,0.08)",
        },
      },
    },
  },
});

fitChartWidth();
originHeightChart.resize(8000, chartScroll ? chartScroll.clientHeight : 420);

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
  renderHeightmap(snapshot);
  originHeightChart.data.datasets[1].data = [{ x: stepIndex, y: originHeights[stepIndex] }];
  originHeightChart.update("none");
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

renderStep(currentStepIndex);

window.addEventListener("resize", fitHeightmap);
window.addEventListener("resize", () => {
  fitChartWidth();
  originHeightChart.resize(8000, chartScroll ? chartScroll.clientHeight : 420);
});
