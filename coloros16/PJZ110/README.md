# MFGA ColorOS 16.1 Global Routing TEST

Target: OnePlus PJZ110, Android 16 / ColorOS 16.1.

This test changes the strategy from generic MFGA XML flattening to OEM-aware routing based on the stock ColorOS font topology extracted from the target device.

## Routing

- `sans-serif`, `sys-sans-en`, `op-sans-en` -> Source Sans 3 weight family.
- `serif` and ColorOS `sys-serif` (OPPO Serif route) -> MFGA main LXGW WenKai weight family.
- `zh-Hans` and `zh-Hant/zh-Bopo` OEM CJK fallbacks -> LXGW WenKai.
- `monospace` and `serif-monospace` -> JetBrains Mono.
- `osans-solid-digits` remains the stock ColorOS variable digit family.
- Legacy `zdigit*` names are compatibility aliases to `osans-solid-digits`; the broken `ZDigit-*.ttf` references are removed.
- MFGA Unicode16/17/18, PUA, symbol, math and other supplemental fallback families are retained.

## Direct-file compatibility

The R1 stripped overrides for `SysFont-Regular.ttf`, `SysSans-En-Regular.ttf`, `Roboto-Regular.ttf`, and `RobotoFlex-Regular.ttf` are removed so the intact stock ColorOS files remain visible for components that bypass family routing and open those files directly.

## Weight behavior

Source Sans 3 has multiple real static weights, so ColorOS family-weight selection can choose a close weight. LXGW WenKai currently has three real masters (Light / Regular / Medium), so CJK weight adjustment is routed through the ColorOS OEM family machinery but is still stepped rather than a true continuous variable `wght` axis. A later iteration can address continuous CJK weight only if a compatible variable CJK source is selected or produced safely.

## Safety

The test build is device-gated to PJZ110 / SDK 36 / ColorOS 16.1.x and keeps per-partition stock XML topology instead of replacing every discovered font XML with one generic file.
