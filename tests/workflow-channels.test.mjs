import { test } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const root = new URL("../", import.meta.url);
const step2 = await readFile(
  new URL("workflow/embedded/02_auto_orient_epidermis.groovy.src", root), "utf8");
const runner = await readFile(new URL("workflow/CoreAlign.groovy", root), "utf8");

// Reading every channel of a 19-channel slide cost 6.1-6.9 s per core against 1.23-1.47 s for
// six, measured on the reference slide. The subset is the whole reason this feature exists.
test("step 2 can read a subset of the channels", () => {
  assert.match(step2, /TransformedServerBuilder/,
    "the subset is built with QuPath's channel-extracting server");
  assert.match(step2, /cfgIntList\('tma\.orientation\.channelIndices'\)/,
    "the selection arrives as a config key, not as a hardcoded list");
  assert.match(runner, /'tma\.orientation\.channelIndices': orientationConfig\.channelIndices/,
    "the runner must flatten the key or step 2 never sees it");
});

// An empty or absent selection has to reproduce the old behaviour exactly. A run configured
// before this feature existed must not change its output by being run again.
test("no selection means every channel, with no transformed server built", () => {
  const block = step2.slice(step2.indexOf("boolean channelSubsetActive"),
                            step2.indexOf("int imgW"));
  assert.match(block, /!keptChannelIndices\.isEmpty\(\)/,
    "an empty selection must not activate the subset");
  assert.match(block, /keptChannelIndices != allChannelIndices/,
    "selecting everything in order is a no-op, not a transform");
  assert.match(block, /else \{\s*\n\s*keptChannelIndices = allChannelIndices/,
    "the no-subset path leaves the original server in place");
});

// This one blocked a real run. The approval checkpoint written by step 3 carries the slide's
// name; a TransformedServerBuilder view reports its own. Comparing the two failed with "the
// current grid does not match its approved checkpoint" and no core was ever processed.
// Same trap for imageStem, which decides the state and output directory names.
test("slide identity is read from the source server, never from the channel view", () => {
  const identityReads = [
    /String imageNameForApproval = sourceServer\.getMetadata\(\)\.getName\(\)/,
    /\(approval\.imageWidth as long\) == sourceServer\.getWidth\(\)/,
    /\(approval\.imageHeight as long\) == sourceServer\.getHeight\(\)/,
    /String imageStem = sourceServer\.getMetadata\(\)\.getName\(\)/,
    /def fileUri = sourceServer\.getURIs\(\)/,
  ];
  for (const pattern of identityReads) {
    assert.match(step2, pattern, `slide identity still reads the channel view: ${pattern}`);
  }
  // And nothing new may reintroduce it: every getName() left on `server` would be a name that
  // changes the moment somebody selects channels.
  const stray = [...step2.matchAll(/(?<!source)\bserver\.getMetadata\(\)\.getName\(\)/g)];
  assert.deepEqual(stray.map((m) => m.index), [],
    "server.getMetadata().getName() names the channel view, not the slide");
  const strayUri = [...step2.matchAll(/(?<!source)\bserver\.getURIs\(\)/g)];
  assert.deepEqual(strayUri.map((m) => m.index), [],
    "server.getURIs() names the channel view, not the slide");
});

// A correction is not idempotent and a cached core is not free. Changing the channels changes
// both the angle that comes out and the files delivered, so both identities have to move.
test("a different channel selection is a different run", () => {
  const removed = runner.slice(runner.indexOf("orientationProcessingConfig"),
                               runner.indexOf("def detectionIdentity"));
  assert.ok(!removed.includes("channelIndices"),
    "channelIndices must stay in the processing identity: it changes the computed angle");
  const output = runner.slice(runner.indexOf("def outputIdentity"),
                              runner.indexOf("String detectionConfigHash"));
  assert.match(output, /channelIndices/,
    "channelIndices must join the output identity: it changes the delivered files");
});
