<!-- public profile, reviewed against repository code/CI/config on 2026-10-07 -->

<p align="center">
  <img src="./assets/profile-banner.svg" alt="AXYMORRSEN — public work index" width="100%" />
</p>

I build Android/Linux systems, edge inference, device integrations and developer infrastructure. This page is an index of work that already has code, tests, tooling or reproducible engineering evidence; unfinished ideas are intentionally left out.

## INDEX / 01 — active systems

<table>
<tr>
<td width="50%" valign="top">
<sub>EDGE AUDIO / ML</sub><br>
<strong><a href="https://github.com/Zhanfg/Voxera">Voxera</a></strong><br>
Offline semantic-prosodic voice conversion for edge devices. The repository contains the native staged path, prosody and semantic sidecars, training/export tooling, tests, and the current M4 ONNX deployment-bundle contract.<br><br>
<code>pre-alpha · M4</code>
</td>
<td width="50%" valign="top">
<sub>ANDROID AGENT RUNTIME</sub><br>
<strong><a href="https://github.com/Zhanfg/nova-agent">Nova</a></strong><br>
Android agent runtime with provider adapters, tool schemas, browser/device/accessibility paths, bounded root execution, task scheduling, skills, memory and Android UI integration.<br><br>
<code>Android · runtime</code>
</td>
</tr>
<tr>
<td width="50%" valign="top">
<sub>KERNELPATCH TOOLING</sub><br>
<strong><a href="https://github.com/Zhanfg/PatchNest">PatchNest</a></strong><br>
Consolidated KernelPatch user-space surface with CLI, module/WebUI and KPM paths. Each path has its own build or validation workflow and provenance record.<br><br>
<code>CLI · module · KPM</code>
</td>
<td width="50%" valign="top">
<sub>ANDROID NETWORKING</sub><br>
<strong><a href="https://github.com/Zhanfg/TCP_Optimiser_RS">TCP Optimiser RS</a></strong><br>
Rust-based Android congestion-control module with interface-aware policy application, runtime verification, recovery logic, statistics and a responsive WebUI.<br><br>
<code>v3.0.0 · Rust</code>
</td>
</tr>
<tr>
<td width="50%" valign="top">
<sub>DEVICE CONTROL PLANE</sub><br>
<strong><a href="https://github.com/Zhanfg/OP13-FeatureLab">OP13 FeatureLab</a></strong><br>
Clean-room control plane for guarded OnePlus 13 feature experiments: compatibility profiles, preflight capture, transactional mounts/properties, media-provider contracts, WebUI status and release gates.<br><br>
<code>clean-room · guarded runtime</code>
</td>
<td width="50%" valign="top">
<sub>DEVELOPER INFRASTRUCTURE</sub><br>
<strong><a href="https://github.com/Zhanfg/axymorrsen-infra-mcp">Axymorrsen Infra MCP</a></strong><br>
Security-first MCP gateway with provider adapters, scoped OAuth/JWT authorization, safety modes, audit metadata, secret backends, remote/stdio bridging and release verification.<br><br>
<code>MCP · infrastructure</code>
</td>
</tr>
</table>

## INDEX / 02 — research & device lines

| Surface | What is actually present |
| --- | --- |
| [OnePlus13 CameraBoost](https://github.com/Zhanfg/OnePlus13-CameraBoost) | Official-source camera catalog, compatibility profiles, Android hook module, tests and guarded 10-bit still experiments. |
| [OnePlus13 kernel](https://github.com/Zhanfg/OnePlus13-kernel) | OnePlus 13 OKI/kernel integration and build workspace. Build evidence exists; boot/device validation is still a separate gate. |
| [UpstreamRadar](https://github.com/Zhanfg/UpstreamRadar) | Automated upstream inspection system with Python/Rust/C++ cores, conformance fixtures, scheduled reports, discovery and maintenance workflows. |

## INDEX / 03 — maintained downstreams

These repositories are explicitly downstream work. Their upstream ownership remains part of the public record.

| Repository | Upstream | Local work |
| --- | --- | --- |
| [Android-DataBackup](https://github.com/Zhanfg/Android-DataBackup) | `XayahSuSuSu/Android-DataBackup` | backup/restore architecture and Rustic restore work |
| [RootlessViPER4Android](https://github.com/Zhanfg/RootlessViPER4Android) | `alienware377/RootlessViPER4Android` | rootless ViPER-style DSP development on the downstream audio branch |
| [Miband-OPlusBridge](https://github.com/Zhanfg/Miband-OPlusBridge) | `MiaM1ku/Miband-OPlusBridge` | ColorOS / OPPO Health integration; Mi Band 11 path is device-verified |
| [Bettbox](https://github.com/Zhanfg/Bettbox) | `appshubcc/Bettbox` | maintained network-client downstream |

## INDEX / 04 — workspaces

<table>
<tr>
<td width="50%" valign="top">
<strong><a href="https://github.com/ZhanfgBuild">ZhanfgBuild</a></strong><br>
Source bases, build/release plumbing, tracked upstreams and supporting engineering.
</td>
<td width="50%" valign="top">
<strong><a href="https://github.com/limbweave-lab">LimbWeave</a></strong><br>
Modular wearable mechatronics: mechanics, embedded, control, digital twin and evidence.
</td>
</tr>
<tr>
<td width="50%" valign="top">
<strong><a href="https://github.com/nullweave-lab">nullweave</a></strong><br>
A deliberately sparse research namespace for runtime integrity, proof systems and matter-anchored computing.
</td>
<td width="50%" valign="top">
<strong><a href="https://github.com/yuezhou-build">yuezhou-build</a></strong><br>
Original field experiments plus clearly attributed upstream/reference forks.
</td>
</tr>
</table>

<p align="center">
  <sub><a href="https://axymorrsen.cc">axymorrsen.cc</a> · 中文交流 / English documentation</sub>
</p>
