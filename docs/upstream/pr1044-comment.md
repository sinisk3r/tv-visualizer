<!-- Draft comment for https://github.com/projectM-visualizer/projectm/pull/1044. Not posted; post only with the user's OK. -->

Test results from GLES on a low-end Android TV, in case they help the review.

I backported this PR (fills and additive borders, same rules) to the 4.1.8 renderer, which still uses raw VAO/VBOs,
and ran it in an Android TV app: Mali-G52 (OpenGL ES 3.2, driver r44p1), 4x Cortex-A73 at 1.39 GHz, preset held at
854x480, fps averaged over 10 s:

| Preset | Shape instances/frame | Before | After |
|---|---|---|---|
| cope - ants on acid orchestrate chaos - flx roam i hate myself | 1,257 | 9.9 | 30.9 |
| martin - amandio c - a different view of the green machine (additive, thick borders) | 809 | 15.5 | 54.8 |
| suksma - big swede (additive fills) | 512 | 22.8 | 60.0 |
| amandio c - interference pattern 2 (plain fills) | 501 | 23.8 | 39.4 |
| suksma - phlegmployant (non-additive borders) | 1,046 | 7.2 | 12.1 |

- GLES: `GL_LINES` for the batched borders draws fine there (no `GL_LINE_SMOOTH` on GLES, as before). I saw no
  visual difference by eye; I did not do a pixel comparison.
- One difference in the backport: each flush uploads with `glBufferData` (orphaning) rather than updating a fixed
  buffer, so the driver never has to wait for an earlier draw from the same buffer. That mattered on Mali in other
  places; I haven't measured it separately here.
- The remaining slow case is non-additive borders (still one draw per instance and outline pass), e.g.
  phlegmployant above.

The backport is here if it's useful: <link to patches/projectm/0001-batch-custom-shape-draws.patch on GitHub>
