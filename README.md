<h1 align="center">Axymorrsen</h1>

<p align="center">
  Android/Linux systems · Kernel & root · Device trust · AI clients & agents · Build engineering
</p>

I build practical systems software around Android and Linux, with current work spanning kernel engineering, root infrastructure, trusted execution, mobile AI clients, agent runtimes, audio stacks, networking and reproducible build systems.

## Current focus

- **Kernel & root** — OnePlus 13 / OnePlus 6 kernels, KernelPatch/KPM, ReSukiSU/SUSFS, congestion control and reproducible build pipelines.
- **TEE / device trust** — TEE simulation, Android RKP/StrongBox experiments and device-side trust infrastructure.
- **AI clients & agents** — mobile-first AI clients, rootless agent runtimes, provider integration, tool execution and persistent environments.
- **System extensions** — download acceleration, audio processing, lyrics/translation experiments and Android framework tooling.
- **Engineering systems** — CI/CD, upstream tracking, source-base maintenance, deployment infrastructure and validation-first releases.

## Selected projects

| Project | Direction |
| --- | --- |
| [PatchNest Module](https://github.com/Zhanfg/PatchNest-Module) | KernelPatch/KPM-oriented Android system module and WebUI work |
| [TEESimulator](https://github.com/Zhanfg/TEESimulator) | TEE simulation and Android trust-stack experiments |
| [Android RKP Bridge](https://github.com/Zhanfg/Android-RKP-Bridge) | RKP / device attestation integration experiments |
| [OnePlus 13 Kernel](https://github.com/Zhanfg/OnePlus13-kernel) | OnePlus 13 kernel integration, validation and release workflows |
| [OnePlus 6 Kernel](https://github.com/Zhanfg/abk-op6-kernel) | OnePlus 6 kernel work with ReSukiSU/SUSFS-oriented build variants |
| [TCP Optimiser](https://github.com/Zhanfg/TCP_Optimiser_RS) | Dynamic TCP tuning, congestion-control work and Android system integration |
| [System Download Accelerator](https://github.com/Zhanfg/SystemDownloadAccelerator) | Android download-path acceleration and configurable system rules |
| [Kelivo](https://github.com/Zhanfg/kelivo) | Mobile AI client with chat, story and agent-oriented workflows |
| [Nova Agent](https://github.com/Zhanfg/nova-agent) | Agent runtime and mobile execution experiments |
| [Rootless JamesDSP](https://github.com/Zhanfg/RootlessJamesDSP) | Rootless Android audio processing and DSP integration |

## Where things live

| Space | Role |
| --- | --- |
| [Zhanfg](https://github.com/Zhanfg) | Maintained products, representative research and user-facing projects |
| [ZhanfgBuild](https://github.com/ZhanfgBuild) | Build/release infrastructure, source bases, upstream/reference forks and supporting engineering |
| [axymorrsen.cc](https://axymorrsen.cc) | Project and writing hub |

The repository layout is being reorganized around this boundary. During migration, some canonical repository locations may change while project identity and history remain intact.

## Working principles

- Evidence before claims: distinguish verified behavior from planned or device-pending work.
- Prefer small, reviewable changes with clear rollback paths.
- Track upstream aggressively, but validate compatibility before integration.
- Treat documentation, CI and reproducible builds as part of the implementation.
- Keep credentials, tokens, private device data and unreleased material out of public repositories.

中文交流 / English documentation welcome.
