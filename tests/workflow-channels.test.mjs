import { test } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const root = new URL("../", import.meta.url);
const step2 = await readFile(
  new URL("workflow/embedded/02_auto_orient_epidermis.groovy.src", root), "utf8");
const runner = await readFile(new URL("workflow/CoreAlign.groovy", root), "utf8");

// The subset controls what the run delivers, not how fast it is. An earlier comment here
// claimed 5x from a benchmark that read all the channels first and the subset second at the
// same coordinates, so the subset was reading a warm cache. Measured properly it is 1.18x,
// and a full run came out 18:28 with six channels against 15:27 with all nineteen.
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

// A channel-subset server reports FLOAT32 even when the slide is UINT16, and the OME writer
// judged the view. Every core of a research run was refused with "supports UINT8/UINT16;
// found FLOAT32" and not one file was written. The samples are bit-exact integers · checked
// on 4.86 million of them · so the guard has to ask the slide, not the view.
test("the OME writer judges the slide's pixel type, not the channel view's", () => {
  const writer = step2.slice(step2.indexOf("def writeRotatedMultichannelOme"),
                             step2.indexOf("def scaleForPreview"));
  assert.match(writer, /String sourcePixelType = sourceServer\.getPixelType\(\)/,
    "the pixel type must come from the slide");
  assert.ok(!/String sourcePixelType = server\.getPixelType\(\)/.test(writer),
    "reading the view's type refuses every core of a channel-selected research run");
  // The guard still has to refuse a genuinely floating-point slide.
  assert.match(writer, /Rotated multichannel OME-TIFF supports UINT8\/UINT16/,
    "a float slide is still not writable as an integer OME-TIFF");
  // Truncating a float raster is exact today. Make it loud rather than silent if it stops being.
  assert.match(writer, /outOfRange\+\+/,
    "a sample that does not fit the target type must be counted, not quietly wrapped");
  assert.match(writer, /should not be trusted for quantification/,
    "and it must say so, because this file is the one people measure from");
});

// Channel count and channel names describe what is being written, so those stay on the view.
test("the OME writer takes its channel count and names from the view", () => {
  const writer = step2.slice(step2.indexOf("def writeRotatedMultichannelOme"),
                             step2.indexOf("def scaleForPreview"));
  assert.match(writer, /Math\.min\(server\.nChannels\(\), bands\)/,
    "the file holds the selected channels, so the count comes from the view");
  assert.match(writer, /server\.getMetadata\(\)\.getChannels\(\)\[c\]\.getName\(\)/,
    "and their names come from the view too");
});
