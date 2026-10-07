<!-- reviewed against current public repositories: 2026-10-07 -->

<p align="center">
  <img src="./assets/profile-banner.svg" alt="Axymorrsen — Android / Linux systems, edge AI and infrastructure" width="100%" />
</p>

<p align="center">
  <a href="https://axymorrsen.cc">axymorrsen.cc</a>
  ·
  <a href="https://github.com/ZhanfgBuild">build / upstream workspace</a>
</p>

I work mostly on Android/Linux systems, edge inference, and developer infrastructure.  
The common rule is simple: **build it, measure it, verify it, then document the boundary.**

## Selected work

<table>
<tr>
<td width="50%" valign="top">

<strong><a href="https://github.com/Zhanfg/Voxera">Voxera</a></strong><br>
<sub>Offline semantic-prosodic voice conversion for edge devices. The native staged pipeline is in place; current M4 work exports and validates the ONNX deployment bundle contract.</sub><br><br>
<code>pre-alpha · edge audio / ML</code>

</td>
<td width="50%" valign="top">

<strong><a href="https://github.com/Zhanfg/TCP_Optimiser_RS">TCP Optimiser RS</a></strong><br>
<sub>Android TCP congestion-control module with a Rust core, network-aware policy application, runtime verification, and a Material Design 3 WebUI.</sub><br><br>
<code>v3.0.0 · Android networking</code>

</td>
</tr>

<tr>
<td width="50%" valign="top">

<strong><a href="https://github.com/Zhanfg/OnePlus13-CameraBoost">OnePlus13 CameraBoost</a></strong><br>
<sub>Clean-room OnePlus/OPlus camera capability research. Includes an official-source feature catalog plus guarded probe and 10-bit still enablement module variants.</sub><br><br>
<code>research / validation · camera stack</code>

</td>
<td width="50%" valign="top">

<strong><a href="https://github.com/Zhanfg/axymorrsen-infra-mcp">Axymorrsen Infra MCP</a></strong><br>
<sub>Security-first MCP gateway with executable provider adapters, scoped OAuth/JWT authorization, safety modes, audit metadata, and reproducible release checks.</sub><br><br>
<code>MCP · infrastructure</code>

</td>
</tr>

<tr>
<td width="50%" valign="top">

<strong><a href="https://github.com/Zhanfg/Android-DataBackup">Android DataBackup</a></strong><br>
<sub>Active maintenance work on the DataBackup codebase. Recent work reorganizes backup/restore architecture and adds the Rustic restore workflow with progress and result handling.</sub><br><br>
<code>maintenance · Android backup</code>

</td>
<td width="50%" valign="top">

<strong><a href="https://github.com/Zhanfg/Miband-OPlusBridge">Miband OPlusBridge</a></strong><br>
<sub>Bridges supported Xiaomi bands into ColorOS Device Space and OPPO Health. Mi Band 11 is device-verified, with connection, health-data, notification, call, music, and background-recovery paths implemented.</sub><br><br>
<code>device-verified · Android integration</code>

</td>
</tr>
</table>

<details>
<summary><strong>More maintained / experimental work</strong></summary>
<br>

- **[OnePlus13-kernel](https://github.com/Zhanfg/OnePlus13-kernel)** — experimental OnePlus 13 OKI/kernel integration and validation workspace. Full build evidence exists; boot validation is still explicitly incomplete.
- **[RootlessViPER4Android](https://github.com/Zhanfg/RootlessViPER4Android)** — rootless Android audio processing based on JamesDSP with native ViPER-style effects and a reorderable DSP chain.
- **[workbench-extensions](https://github.com/Zhanfg/workbench-extensions)** — public Workbench extension distribution catalog; the current published catalog includes the novel-tool extension package.
- **[UpstreamRadar](https://github.com/Zhanfg/UpstreamRadar)** — dependency-free Python scheduler for prioritizing upstream inspection under bounded time/API budgets.
- **[susfs4ksu](https://github.com/Zhanfg/susfs4ksu)** — GitHub mirror and development entry point for SUSFS v2.3.0, preserving upstream branch history.

</details>

## Repository layout

- **[Zhanfg](https://github.com/Zhanfg)** — public projects, device work, experiments, and maintained forks.
- **[ZhanfgBuild](https://github.com/ZhanfgBuild)** — source bases, build/release infrastructure, upstream mirrors, and supporting engineering.
- **[axymorrsen.cc](https://axymorrsen.cc)** — project and writing hub.

## Working notes

I keep experimental results labeled as experimental, and I do not treat a visible option, successful compile, or passing CI job as proof of device behavior. Public repositories should stay free of credentials, private device data, and proprietary blobs.

<sub>中文交流 / English documentation.</sub>
