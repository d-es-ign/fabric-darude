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

function totalMass(columns) {
  let total = 0;
  for (const column of columns.values()) {
    total += getTotalLayers(column);
  }
  return total;
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
    return null;
  }

  if (!hasEmitterOccupancy(x, z, emitter)) {
    return {
      type: "violation",
      kind: "non_origin_overflow",
      location: { x, z },
      beforeActiveHeight: column.activeLayers,
    };
  }

  const createdSandBlocks = Math.floor(column.activeLayers / 16);
  const remainder = column.activeLayers % 16;
  const beforeActiveHeight = column.activeLayers;
  column.stableSandBlocks += createdSandBlocks;
  column.activeLayers = hasEmitterOccupancy(x, z, emitter) && column.stableSandBlocks > 0 ? 0 : remainder;
  return {
    type: "settle",
    location: { x, z },
    beforeActiveHeight,
    afterStableSandBlocks: column.stableSandBlocks,
    afterActiveHeight: column.activeLayers,
  };
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
  const events = [];
  const toppleLimit = maxTopplesPerTick === 0 ? Number.POSITIVE_INFINITY : maxTopplesPerTick;

  while (queueState.queue.length > 0 && processedTopples < toppleLimit) {
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
      const settleEvent = settleColumn(columns, x, z, emitter);
      if (settleEvent?.type === "settle") {
        anyChange = true;
        events.push(settleEvent);
        enqueueNeighbors(queueState, columns, slopeThreshold, x, z);
      } else if (settleEvent?.type === "violation") {
        events.push(settleEvent);
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
    const event = {
      source: { x, z },
      sourceHeight,
      resultingSourceHeight: sourceColumn.activeLayers,
      transferLayers,
      targets: [],
    };

    enqueueNeighbors(queueState, columns, slopeThreshold, x, z);
    for (let candidateIndex = 0; candidateIndex < candidates.length; candidateIndex += 1) {
      const candidate = candidates[candidateIndex];
      const targetColumn = getColumn(columns, candidate.x, candidate.z);
      const targetBefore = getTotalLayers(targetColumn);
      const targetBeforeActive = targetColumn.activeLayers;
      const added = addHorizontalLayers(columns, candidate.x, candidate.z, shares[candidateIndex], emitter);
      const targetAfter = getTotalLayers(getColumn(columns, candidate.x, candidate.z));
      event.targets.push({ x: candidate.x, z: candidate.z, layers: added, before: targetBefore, after: targetAfter, beforeActive: targetBeforeActive, afterActive: getColumn(columns, candidate.x, candidate.z).activeLayers });
      if (added > 0) {
        enqueueNeighbors(queueState, columns, slopeThreshold, candidate.x, candidate.z);
      }
    }
    events.push(event);

    processedTopples += 1;
  }

  const capHit = Number.isFinite(toppleLimit) && processedTopples >= toppleLimit;
  return { anyChange, processedTopples, events, capHit };
}

function applyEmitterPlacement(columns, options) {
  const origin = getColumn(columns, options.emitter.x, options.emitter.z);
  if (origin.stableSandBlocks > 0) {
    return { blocked: true, changed: false };
  }

  origin.activeLayers += 1;
  return { blocked: false, changed: true };
}

function countResidualUnstableCells(columns, options) {
  const { slopeThreshold, emitter } = options;
  let count = 0;
  for (const [key, column] of columns.entries()) {
    if (column.activeLayers <= slopeThreshold) {
      continue;
    }

    const [xString, zString] = key.split(",");
    const x = Number.parseInt(xString, 10);
    const z = Number.parseInt(zString, 10);
    let canStillTopple = false;

    for (const [dx, dz] of CARDINALS) {
      const neighborX = x + dx;
      const neighborZ = z + dz;
      if (!canAcceptHorizontalLayers(columns, neighborX, neighborZ, emitter)) {
        continue;
      }

      const neighborColumn = getColumn(columns, neighborX, neighborZ);
      if (column.activeLayers - neighborColumn.activeLayers > slopeThreshold) {
        canStillTopple = true;
        break;
      }
    }

    if (canStillTopple) {
      count += 1;
    }
  }
  return count;
}

function collectNonOriginOverflowViolations(events) {
  return events.filter((event) => event.type === "violation" && event.kind === "non_origin_overflow");
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

function computeGlobalBounds(snapshots, emitter) {
  let minX = emitter.x - 1;
  let maxX = emitter.x + 1;
  let minZ = emitter.z - 1;
  let maxZ = emitter.z + 1;

  for (const snapshot of snapshots) {
    minX = Math.min(minX, snapshot.bounds.minX);
    maxX = Math.max(maxX, snapshot.bounds.maxX);
    minZ = Math.min(minZ, snapshot.bounds.minZ);
    maxZ = Math.max(maxZ, snapshot.bounds.maxZ);
  }

  return { minX, maxX, minZ, maxZ };
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
  return totalMass(columns);
}

function collectInvariantDiagnostics(columns, options, stepSummary) {
  const diagnostics = {
    massConserved: totalMass(columns) === stepSummary.placements,
    negativeHeightCells: [],
    invalidToppleEvents: [],
    teleportationViolations: [],
  };

  for (const [key, column] of columns.entries()) {
    if (column.stableSandBlocks < 0 || column.activeLayers < 0) {
      const [xString, zString] = key.split(",");
      diagnostics.negativeHeightCells.push({ x: Number.parseInt(xString, 10), z: Number.parseInt(zString, 10), stableSandBlocks: column.stableSandBlocks, activeLayers: column.activeLayers });
    }
  }

  for (const event of stepSummary.events ?? []) {
    if (event.type !== "topple") {
      continue;
    }

    for (const target of event.targets) {
      if (target.layers <= 0) {
        continue;
      }

      const manhattanDistance = Math.abs(target.x - event.source.x) + Math.abs(target.z - event.source.z);
      if (manhattanDistance !== 1) {
        diagnostics.teleportationViolations.push({ source: event.source, target: { x: target.x, z: target.z }, layers: target.layers });
      }

      if (event.sourceHeight <= target.beforeActive || event.sourceHeight - target.beforeActive <= options.slopeThreshold) {
        diagnostics.invalidToppleEvents.push({
          source: event.source,
          sourceHeight: event.sourceHeight,
          target: { x: target.x, z: target.z },
          targetBeforeActive: target.beforeActive,
        });
      }
    }
  }

  diagnostics.noNegativeHeights = diagnostics.negativeHeightCells.length === 0;
  diagnostics.topplesStrictlyDownhill = diagnostics.invalidToppleEvents.length === 0;
  diagnostics.noTeleportation = diagnostics.teleportationViolations.length === 0;
  diagnostics.locallyStableIfCapNotHit = stepSummary.capHit || stepSummary.residualUnstableCells === 0;
  return diagnostics;
}

export function normalizeSimulationOptions(customOptions = {}) {
  const slopeThreshold = Math.max(1, Math.round(customOptions.slopeThreshold ?? DEFAULTS.slopeThreshold));
  const rawMaxTopplesPerTick = Math.max(0, Math.round(customOptions.maxTopplesPerTick ?? DEFAULTS.maxTopplesPerTick));
  return {
    ...DEFAULTS,
    ...customOptions,
    slopeThreshold,
    maxTopplesPerTick: rawMaxTopplesPerTick,
    emitter: { ...DEFAULTS.emitter, ...(customOptions.emitter ?? {}) },
  };
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
  const options = normalizeSimulationOptions(customOptions);

  const columns = new Map();
  const initialSummary = { placements: 0, blocked: false, topples: 0, events: [], capHit: false, residualUnstableCells: 0, nonOriginOverflowViolations: [], originPlacementIncrementOk: true };
  initialSummary.invariants = collectInvariantDiagnostics(columns, options, initialSummary);
  const snapshots = [snapshotFrom(columns, 0, initialSummary, options)];
  let blocked = false;

  for (let placements = 1; placements <= options.maxSimulationSteps; placements += 1) {
    const originBeforePlacement = getColumn(columns, options.emitter.x, options.emitter.z).activeLayers;
    const placement = applyEmitterPlacement(columns, options);
    const avalancheTick = runAvalancheTick(columns, options);
    blocked = getColumn(columns, options.emitter.x, options.emitter.z).stableSandBlocks > 0;
    const residualUnstableCells = countResidualUnstableCells(columns, options);
    const nonOriginOverflowViolations = collectNonOriginOverflowViolations(avalancheTick.events);
    const stepSummary = {
      placements,
      blocked,
      topples: avalancheTick.processedTopples,
      events: avalancheTick.events,
      capHit: avalancheTick.capHit,
      residualUnstableCells,
      nonOriginOverflowViolations,
      originBeforePlacement,
      originAfterPlacementBeforeAvalanche: placement.changed ? originBeforePlacement + 1 : originBeforePlacement,
    };
    stepSummary.originPlacementIncrementOk = stepSummary.originAfterPlacementBeforeAvalanche - originBeforePlacement <= 1;
    stepSummary.invariants = collectInvariantDiagnostics(columns, options, stepSummary);
    snapshots.push(
      snapshotFrom(
        columns,
        placements,
        stepSummary,
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
    globalBounds: computeGlobalBounds(snapshots, options.emitter),
    blocked,
    finalStepIndex: finalSnapshot.stepIndex,
    totalLayers: totalLayers(finalSnapshot.columns),
    occupiedColumns: countOccupiedColumns(finalSnapshot.columns),
  };
}
