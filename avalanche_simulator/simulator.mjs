const CARDINALS = [
  [0, -1],
  [1, 0],
  [0, 1],
  [-1, 0],
];

const DEFAULTS = {
  slopeThreshold: 3,
  maxTopplesPerTick: 64,
  maxSimulationSteps: 2048,
  emitter: { x: 0, z: 0 },
};

function keyOf(x, z) {
  return `${x},${z}`;
}

function cloneColumns(columns) {
  return new Map(Array.from(columns.entries(), ([key, value]) => [key, { ...value }]));
}

function getColumn(columns, x, z) {
  const key = keyOf(x, z);
  let column = columns.get(key);
  if (!column) {
    column = { stableSandBlocks: 0, activeLayers: 0 };
    columns.set(key, column);
  }
  return column;
}

function getTotalLayers(column) {
  return column.stableSandBlocks * 16 + column.activeLayers;
}

function hasEmitterOccupancy(x, z, emitter) {
  return x === emitter.x && z === emitter.z;
}

function canAcceptHorizontalLayers(columns, x, z, emitter) {
  const column = getColumn(columns, x, z);
  if (!hasEmitterOccupancy(x, z, emitter)) {
    return true;
  }
  return column.stableSandBlocks === 0;
}

function settleColumn(columns, x, z, emitter) {
  const column = getColumn(columns, x, z);
  if (column.activeLayers < 16) {
    return false;
  }

  const createdSandBlocks = Math.floor(column.activeLayers / 16);
  const remainder = column.activeLayers % 16;
  column.stableSandBlocks += createdSandBlocks;
  column.activeLayers = hasEmitterOccupancy(x, z, emitter) && column.stableSandBlocks > 0 ? 0 : remainder;
  return true;
}

function addHorizontalLayers(columns, x, z, layers, emitter) {
  if (layers <= 0 || !canAcceptHorizontalLayers(columns, x, z, emitter)) {
    return 0;
  }

  const column = getColumn(columns, x, z);
  column.activeLayers += layers;
  return layers;
}

function seedQueue(columns, slopeThreshold) {
  const queue = [];
  const seen = new Set();

  for (const [key, column] of columns.entries()) {
    if (column.activeLayers > slopeThreshold) {
      queue.push(key);
      seen.add(key);
    }
  }

  return { queue, seen };
}

function enqueueNeighbors(queueState, columns, slopeThreshold, x, z) {
  const enqueue = (nextX, nextZ) => {
    const key = keyOf(nextX, nextZ);
    if (queueState.seen.has(key)) {
      return;
    }

    const column = getColumn(columns, nextX, nextZ);
    if (column.activeLayers <= slopeThreshold) {
      return;
    }

    queueState.queue.push(key);
    queueState.seen.add(key);
  };

  enqueue(x, z);
  for (const [dx, dz] of CARDINALS) {
    enqueue(x + dx, z + dz);
  }
}

function runAvalancheTick(columns, options) {
  const { slopeThreshold, maxTopplesPerTick, emitter } = options;
  const queueState = seedQueue(columns, slopeThreshold);
  let processedTopples = 0;
  let anyChange = false;

  while (queueState.queue.length > 0 && processedTopples < maxTopplesPerTick) {
    const key = queueState.queue.shift();
    queueState.seen.delete(key);

    const [xString, zString] = key.split(",");
    const x = Number.parseInt(xString, 10);
    const z = Number.parseInt(zString, 10);
    const sourceColumn = getColumn(columns, x, z);
    const sourceHeight = sourceColumn.activeLayers;
    if (sourceHeight <= 0) {
      continue;
    }

    const candidates = [];
    for (let directionIndex = 0; directionIndex < CARDINALS.length; directionIndex += 1) {
      const [dx, dz] = CARDINALS[directionIndex];
      const neighborX = x + dx;
      const neighborZ = z + dz;
      if (!canAcceptHorizontalLayers(columns, neighborX, neighborZ, emitter)) {
        continue;
      }

      const neighborColumn = getColumn(columns, neighborX, neighborZ);
      const delta = sourceHeight - neighborColumn.activeLayers;
      if (delta <= slopeThreshold) {
        continue;
      }

      candidates.push({ directionIndex, x: neighborX, z: neighborZ });
    }

    if (candidates.length === 0) {
      if (settleColumn(columns, x, z, emitter)) {
        anyChange = true;
        enqueueNeighbors(queueState, columns, slopeThreshold, x, z);
      }
      continue;
    }

    const transferLayers = Math.max(0, sourceHeight - slopeThreshold);
    if (transferLayers <= 0) {
      continue;
    }

    const baseShare = Math.floor(transferLayers / candidates.length);
    let remainder = transferLayers % candidates.length;
    const shares = candidates.map(() => baseShare);

    for (let directionIndex = 0; directionIndex < CARDINALS.length && remainder > 0; directionIndex += 1) {
      for (let candidateIndex = 0; candidateIndex < candidates.length && remainder > 0; candidateIndex += 1) {
        if (candidates[candidateIndex].directionIndex !== directionIndex) {
          continue;
        }
        shares[candidateIndex] += 1;
        remainder -= 1;
      }
    }

    sourceColumn.activeLayers -= transferLayers;
    anyChange = true;

    enqueueNeighbors(queueState, columns, slopeThreshold, x, z);
    for (let candidateIndex = 0; candidateIndex < candidates.length; candidateIndex += 1) {
      const candidate = candidates[candidateIndex];
      const added = addHorizontalLayers(columns, candidate.x, candidate.z, shares[candidateIndex], emitter);
      if (added > 0) {
        enqueueNeighbors(queueState, columns, slopeThreshold, candidate.x, candidate.z);
      }
    }

    processedTopples += 1;
  }

  return { anyChange, processedTopples };
}

function applyEmitterPlacement(columns, options) {
  const origin = getColumn(columns, options.emitter.x, options.emitter.z);
  if (origin.stableSandBlocks > 0) {
    return { blocked: true, changed: false };
  }

  if (origin.activeLayers < 15) {
    origin.activeLayers += 1;
    return { blocked: false, changed: true };
  }

  origin.stableSandBlocks += 1;
  origin.activeLayers = 0;
  return { blocked: true, changed: true };
}

function computeBounds(columns, emitter) {
  const occupied = [];
  for (const [key, column] of columns.entries()) {
    if (getTotalLayers(column) <= 0) {
      continue;
    }
    const [xString, zString] = key.split(",");
    occupied.push([Number.parseInt(xString, 10), Number.parseInt(zString, 10)]);
  }

  if (occupied.length === 0) {
    return { minX: emitter.x - 1, maxX: emitter.x + 1, minZ: emitter.z - 1, maxZ: emitter.z + 1 };
  }

  let minX = emitter.x;
  let maxX = emitter.x;
  let minZ = emitter.z;
  let maxZ = emitter.z;
  for (const [x, z] of occupied) {
    minX = Math.min(minX, x);
    maxX = Math.max(maxX, x);
    minZ = Math.min(minZ, z);
    maxZ = Math.max(maxZ, z);
  }

  return { minX: minX - 1, maxX: maxX + 1, minZ: minZ - 1, maxZ: maxZ + 1 };
}

function snapshotFrom(columns, stepIndex, stepSummary, options) {
  return {
    stepIndex,
    bounds: computeBounds(columns, options.emitter),
    columns: cloneColumns(columns),
    stepSummary,
  };
}

function countOccupiedColumns(columns) {
  let occupied = 0;
  for (const column of columns.values()) {
    if (getTotalLayers(column) > 0) {
      occupied += 1;
    }
  }
  return occupied;
}

function totalLayers(columns) {
  let total = 0;
  for (const column of columns.values()) {
    total += getTotalLayers(column);
  }
  return total;
}

export function describeCell(column) {
  if (!column || (column.stableSandBlocks === 0 && column.activeLayers === 0)) {
    return "0";
  }
  if (column.stableSandBlocks === 0) {
    return String(column.activeLayers);
  }
  if (column.activeLayers === 0) {
    return String(column.stableSandBlocks * 16);
  }
  return `${column.stableSandBlocks * 16}+${column.activeLayers}`;
}

export function getColumnSnapshot(snapshot, x, z) {
  return snapshot.columns.get(keyOf(x, z)) ?? { stableSandBlocks: 0, activeLayers: 0 };
}

export function runBasicEmitterSimulation(customOptions = {}) {
  const options = {
    ...DEFAULTS,
    ...customOptions,
    emitter: { ...DEFAULTS.emitter, ...(customOptions.emitter ?? {}) },
  };

  const columns = new Map();
  const snapshots = [snapshotFrom(columns, 0, { placements: 0, blocked: false, topples: 0, quiesceTicks: 0 }, options)];
  let blocked = false;

  for (let placements = 1; placements <= options.maxSimulationSteps; placements += 1) {
    const placement = applyEmitterPlacement(columns, options);
    const avalancheTick = runAvalancheTick(columns, options);
    blocked = placement.blocked;
    snapshots.push(
      snapshotFrom(
        columns,
        placements,
        {
          placements,
          blocked,
          topples: avalancheTick.processedTopples,
        },
        options,
      ),
    );

    if (blocked) {
      break;
    }
  }

  const finalSnapshot = snapshots[snapshots.length - 1];
  return {
    options,
    snapshots,
    blocked,
    finalStepIndex: finalSnapshot.stepIndex,
    totalLayers: totalLayers(finalSnapshot.columns),
    occupiedColumns: countOccupiedColumns(finalSnapshot.columns),
  };
}
