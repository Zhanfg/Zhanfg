<p align="center"><img src="./assets/profile-banner.svg" alt="Axymorrsen" width="100%"></p>

<p align="center">
  <code>Android / Linux</code>&nbsp;&nbsp;·&nbsp;&nbsp;
  <code>edge ML</code>&nbsp;&nbsp;·&nbsp;&nbsp;
  <code>systems tooling</code>&nbsp;&nbsp;·&nbsp;&nbsp;
  <code>build engineering</code>
</p>

I build systems that sit close to the device boundary: Android/Linux internals, local inference, networking, tooling, and infrastructure. Public pages stay evidence-first: experimental work is labeled as experimental, and upstream-derived work is kept separate from original projects.

### active build surfaces

<table>
<tr>
<td width="50%" valign="top">
<strong><a href="https://github.com/Zhanfg/Voxera">Voxera</a></strong><br>
<sub>Offline semantic-prosodic voice conversion for edge devices. The native M1–M3 path is implemented; current M4 work builds and validates deployable ONNX runtime bundles.</sub><br><br>
<code>pre-alpha / Python / ONNX</code>
</td>
<td width="50%" valign="top">
<strong><a href="https://github.com/Zhanfg/UpstreamRadar">UpstreamRadar</a></strong><br>
<sub>Budget-aware upstream inspection and scheduling. The repository contains the SKOPRÆD scheduler, tests, a web surface, and Python/Rust/C++/Julia implementations used for conformance and experimentation.</sub><br><br>
<code>active / algorithms / tooling</code>
</td>
</tr>
<tr>
<td valign="top">
<strong><a href="https://github.com/Zhanfg/axymorrsen-infra-mcp">Axymorrsen Infra MCP</a></strong><br>
<sub>Security-first MCP gateway for developer infrastructure, with provider adapters, resource-scoped authorization, step-up policy, safety modes, audit events, and release verification.</sub><br><br>
<code>0.9.0 / TypeScript / MCP</code>
</td>
<td valign="top">
<strong><a href="https://github.com/Zhanfg/TCP_Optimiser_RS">TCP Optimiser RS</a></strong><br>
<sub>Android congestion-control module with a Rust daemon, adaptive interface handling, qdisc reconciliation, runtime capability checks, packaging validation, and a Material 3 WebUI.</sub><br><br>
<code>3.0.0 / Rust / Android</code>
</td>
</tr>
<tr>
<td valign="top">
<strong><a href="https://github.com/Zhanfg/OnePlus13-CameraBoost">OnePlus13 CameraBoost</a></strong><br>
<sub>Clean-room OPlus camera research toolkit with an official-source feature catalog, capability probes, guarded OnePlus 13 targeting, and a 10-bit still configuration path.</sub><br><br>
<code>research / Android / camera stack</code>
</td>
<td valign="top">
<strong><a href="https://github.com/Zhanfg/PatchNest">PatchNest</a></strong><br>
<sub>Canonical monorepo for the PatchNest userspace CLI, root module/WebUI, and KPM workspace, with component-level CI and preserved imported history.</sub><br><br>
<code>monorepo / kernel tooling</code>
</td>
</tr>
</table>

### experimental / validation-bound

**[OnePlus13-kernel](https://github.com/Zhanfg/OnePlus13-kernel)** is the control repository for the OnePlus 13 OKI/kernel integration line. A full official 6.6.118 build has evidence; the custom-common integration still has unresolved upstream conflicts and has **not** completed device boot validation.

### maintained downstream work

Some repositories here are deliberate downstream maintenance rather than original upstream projects:

- [Android-DataBackup](https://github.com/Zhanfg/Android-DataBackup) ← `XayahSuSuSu/Android-DataBackup`
- [Miband-OPlusBridge](https://github.com/Zhanfg/Miband-OPlusBridge) ← `MiaM1ku/Miband-OPlusBridge`
- [RootlessViPER4Android](https://github.com/Zhanfg/RootlessViPER4Android) ← `alienware377/RootlessViPER4Android`

The upstream relationship is part of the project identity, not something to hide.

### spaces

`Zhanfg` is the public project surface.  
`ZhanfgBuild` holds source bases, build/release plumbing, and tracked upstream forks.  
`nullweave-lab` is a narrow research namespace.  
`limbweave-lab` is a private R&D workspace.  
`yuezhou-build` is used as a low-profile build/mirror namespace.

<p align="center"><sub><a href="https://axymorrsen.cc">axymorrsen.cc</a> · 中文交流 / English documentation</sub></p>
