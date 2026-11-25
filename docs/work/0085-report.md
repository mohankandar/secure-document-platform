# Report 0085: recognize the document type, measurement first

Date: 2026-11-25
Branch: `feature/0085-document-type`, from 0084's branch
Outcome: **Step 1 DONE. Step 2 NOT BUILT: 92.3% on unseen documents, under the 95% the task asks for** (4 questions)

## Measured
12 types and "other", from the text of the beginning of a document (first 4,000 characters), `qwen3.5:9b`, Ollama 0.40.18, answer schema in a fixed order, temperature 0, one run per set. Every fourth document has about 4% of its characters damaged. All documents are invented; the layouts are written by hand.

| Set | Right | Damaged ones | ms a document |
|---|---|---|---|
| A (the prompt was tried on it) | 155 of 156 (99.4%) | 26 of 27 | 417 |
| B (held out, **but written before the prompt, by the same author, and the prompt names some of its documents**) | 153 of 159 (96.2%) | 25 of 26 | 427 |
| **C (written after the prompt was frozen: the one that counts)** | **144 of 156 (92.3%)** | 22 of 22 | 435 |

B alone would have passed. It is not used for the decision, for the reason in the table.

**Confusion** (every type not listed is 100% right in all three sets: 12 of 12 each, "other" also 15 of 15 in B):

| True type | A | B | C |
|---|---|---|---|
| Court order | 11 of 12 (1 as motion) | **6 of 12** (6 as motion) | **0 of 12** (6 as motion, 6 as other) |

- **Only court orders fail**, and they fail badly: an order that grants or denies a motion reads as a motion to the model; a decree or letters of guardianship as "other".
- **Confidence does not help**: in C at 0.95 or more 152 answers, 9 wrong; at 0.99 or more 10 answers, 3 wrong. An "Unknown type" for low confidence would not catch these.

## Not shown by this
- **No real documents.** One text per layout and few layouts (24 to 31 a set): the 100% of the other types is a best case. Real deeds, statements and pay stubs vary far more.
- The benchmark samples (0033) have no type in their answer files and most have no readable title; they were not scored, though the task names them.
- Scanned pages without text, packages with several documents in one PDF, and other languages were not tried.

## What was done
`test-tools/document-type`: the generator with its three sets, the prompt (`prompt-v1.txt`) and the measurement. **No product code, migration or setting.** So no gate, product tests or frontend run.

## Commands run and results
| What | Result |
|---|---|
| `SET=A` / `B` / `C` `node test-tools/document-type/measure.mjs` | the tables above |
| `check-product-names.sh` | clean |

## Questions for the owner
1. **One more round?** A second prompt that tells orders from motions, checked on a fourth set written afterwards. Recommended: yes, as a small task. I did not tune on C and measure C again.
2. **Real documents** before any product part: some of each type, with their type, in the `customer\` samples. Recommended: yes; without them 95% says little.
3. **Start without court types** (the loan types only)? They were 100% here. Recommended: not before question 2.
4. **Types in the benchmark's answer files**, so that later runs can score them? Recommended: yes, with question 1.

No other product's name or behaviour in code.
