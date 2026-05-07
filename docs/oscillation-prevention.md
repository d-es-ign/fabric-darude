## Horizontal oscillation

Oscillation families:
A. Direct 2-cell reversal
- A -> B -> A
- Status: confirmed in the current flat single-emitter simulator/model.

B. Split-and-return
- A -> {B,C}
- then B -> A and/or C -> A
- Status: unverified hypothesis for the current reduced model.
- Note: the earlier example using a source jumping from 6 to 11 active layers was invalid.
- Correction: that example would also be invalid in a flat multi-emitter model, because each emitter still only adds one layer at its own location, so that jump is not naturally reachable there either.

C. Chain return
- A -> B -> C -> A
- Status: unverified hypothesis for the current reduced model.

D. Multi-step recirculation
- A -> {B, C}
- but the return flow does not come directly from B or C back to A
- instead it propagates through intermediate cells first
- e.g.
  - A -> {B, C}
  - B -> D
  - D -> F
  - F -> A
- Status: unverified hypothesis for the current reduced model.

Working rule for this doc:
- Only treat a family as established once it has been shown to arise from a simulator-reachable state under the reduced flat-plane model.
- Until then, keep it marked as an unverified hypothesis.
