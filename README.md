<p align="center">
  <img src="./assets/profile-banner.svg" alt="Axymorrsen — Android / Linux, edge ML and infrastructure" width="100%" />
</p>

<p align="center">
  <sub>systems work, kept explicit about provenance, validation and unfinished edges</sub>
</p>

I work across Android/Linux systems, edge inference, device integration and developer infrastructure. Public repositories are treated as engineering records rather than feature brochures: upstream work stays attributed, experimental results stay labeled, and a successful build is not described as device validation.

## 01 / primary work

<table>
<tr>
<td width="50%" valign="top">
<sub>EDGE AUDIO / ML</sub><br>
<strong><a href="https://github.com/Zhanfg/Voxera">Voxera</a></strong><br>
Compact offline semantic-prosodic voice conversion. The staged native path, prosody/semantic sidecars and test suite are present; current M4 work defines and verifies the ONNX deployment-bundle contract.<br><br>
<code>pre-alpha · M4</code>
</td>
<td width="50%" valign="top">
<sub>ANDROID AGENT RUNTIME</sub><br>
<strong><a href="https://github.com/Zhanfg/nova-agent">Nova</a></strong><br>
Android autonomous-agent runtime with provider routing, tool schemas, browser/device/accessibility paths, bounded root execution, skills and memory plumbing.<br><br>
<code>Android · agent runtime</code>
</td>
</tr>
<tr>
<td width="50%" valign="top">
<sub>KERNELPATCH TOOLING</sub><br>
<strong><a href="https://github.com/Zhanfg/PatchNest">PatchNest</a></strong><br>
Consolidated KernelPatch user-space surface covering CLI, module/WebUI and KPM paths, with path-specific CI and validation contracts.<br><br>
<code>CLI · module · KPM</code>
</td>
<td width="50%" valign="top">
<sub>ANDROID NETWORKING</sub><br>
<strong><a href="https://github.com/Zhanfg/TCP_Optimiser_RS">TCP Optimiser RS</a></strong><br>
Rust-based Android congestion-control module with interface-aware policies, runtime verification, statistics, recovery logic and a responsive WebUI.<br><br>
<code>v3.0.0 · Rust</code>
</td>
</tr>
<tr>
<td width="50%" valign="top">
<sub>CAMERA RESEARCH</sub><br>
<strong><a href="https://github.com/Zhanfg/OnePlus13-CameraBoost">OnePlus13 CameraBoost</a></strong><br>
Clean-room OPlus camera capability research with an official-source feature catalog, compatibility data, device probes and guarded 10-bit still gate experiments.<br><br>
<code>research · validation-first</code>
</td>
<td width="50%" valign="top">
<sub>DEVELOPER INFRASTRUCTURE</sub><br>
<strong><a href="https://github.com/Zhanfg/axymorrsen-infra-mcp">Axymorrsen Infra MCP</a></strong><br>
MCP gateway with provider adapters, scoped OAuth/JWT authorization, safety modes, audit metadata, remote/stdio transport bridging and release verification.<br><br>
<code>MCP · security boundary</code>
</td>
</tr>
</table>

## 02 / maintained downstreams

These are explicitly downstream works, not presented as original upstream ownership.

| Repository | Upstream boundary | Current local surface |
| --- | --- | --- |
| [Android-DataBackup](https://github.com/Zhanfg/Android-DataBackup) | fork of <code>XayahSuSuSu/Android-DataBackup</code> | backup/restore architecture work and Rustic restore path |
| [Miband-OPlusBridge](https://github.com/Zhanfg/Miband-OPlusBridge) | fork of <code>MiaM1ku/Miband-OPlusBridge</code> | ColorOS / OPPO Health integration; Mi Band 11 path is device-verified |
| [RootlessViPER4Android](https://github.com/Zhanfg/RootlessViPER4Android) | fork of <code>alienware377/RootlessViPER4Android</code> | rootless ViPER-style DSP work on the downstream audio branch |
| [Bettbox](https://github.com/Zhanfg/Bettbox) | fork of <code>appshubcc/Bettbox</code> | maintained network-client downstream |

## 03 / workspaces

<table>
<tr>
<td><strong><a href="https://github.com/ZhanfgBuild">ZhanfgBuild</a></strong><br><sub>source bases, CI/release plumbing and tracked upstreams</sub></td>
<td><strong><a href="https://github.com/limbweave-lab">LimbWeave</a></strong><br><sub>modular wearable mechatronics engineering</sub></td>
</tr>
<tr>
<td><strong><a href="https://github.com/nullweave-lab">nullweave</a></strong><br><sub>runtime-integrity and proof-systems research namespace</sub></td>
<td><strong><a href="https://github.com/yuezhou-build">yuezhou-build</a></strong><br><sub>upstream tracking, maintenance and isolated experiments</sub></td>
</tr>
</table>

<p align="center">
  <sub><a href="https://axymorrsen.cc">axymorrsen.cc</a> · 中文交流 / English documentation</sub>
</p>
