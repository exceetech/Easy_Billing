# Phone Compatibility — Phase 9 Final Testing Checklist

All code changes (Phases 1–8) are complete and verified (XML validity + ID parity between phone and tablet layouts, single Kotlin call sites confirmed for every redesigned screen). This phase is the final on-device pass — it needs to be run on a real phone (and a tablet, for regression) since I can't launch a device myself.

## Final code-level sanity sweep (done, just now)

- No `requestedOrientation` / `SCREEN_ORIENTATION_*` overrides remain anywhere in the codebase — confirmed via full-tree grep.
- Manifest: 0 remaining `sensorLandscape`, all screens `unspecified`.
- All `dialog_*` / `popup_*` layouts re-checked: every root is `match_parent` width (Phase 2 held up).
- `layout-sw600dp/` contains exactly the 6 tablet-preserved files expected: Invoice, Purchase, Bill Details, Add Product, Login (`activity_main.xml`), Register.

## What to test on a phone (portrait, a few different screen sizes if possible)

**Core flow**
- Login screen — branding pane sits as a header now, form below; swipe left/right still switches to Register; fields reachable without keyboard covering the button.
- Register screen — same check as Login.
- Dashboard — left drawer and cart drawer open to a sensible width (not full-bleed, not clipped); tile grid re-flows to fewer columns; notification panel slides in at the same width as the cart drawer.
- Confirm Payment popup — opens centered, doesn't overflow screen width.
- Premium upgrade dialog — same check.

**Redesigned screens (the big ones)**
- Add Product — Product Details, Pricing & Tax, GST & Compliance (4 fields stacked), Opening Stock all stack in one column, scroll smoothly, no cut-off fields.
- Invoice — Customer, Items, Charges, GST Options, GST Summary, Totals all stack in one column and scroll; card backgrounds look correct (not just on the old two-pane background).
- Purchase — Invoice Details, Supplier, Imported Goods, GST Compliance, Line Items, Payment Option, Totals stack correctly; each card keeps its own background/border.
- Bill Details — Items card, Notes, then the new Summary card (customer, subtotal/GST/discount/total, action buttons) stack in one column with visible card borders.

**Everything else (from the original audit, unchanged code but worth a quick look)**
- Reports screens, all Settings sub-screens, Customer Transactions, Workspace Changed screen (icon/text/button fit without scrolling on a typical phone, scrolls if font size is bumped up).

## What to re-test on a tablet (regression check)

Open each of the 6 redesigned screens and confirm they look pixel-identical to before this work: Login, Register, Invoice, Purchase, Bill Details, Add Product. Since `layout-sw600dp/` is a byte-identical copy of the original tablet layouts (confirmed via `diff` at each phase), there should be zero visible change — this is really just confirming Android is picking the right layout folder per device.

## If something looks off

Note which screen, what looks wrong (cut off, overlapping, wrong spacing), and whether it's phone or tablet — that's enough for me to trace it back to the specific layout file and fix it.
