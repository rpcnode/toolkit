---
title: I Am Toolkit. I Put the Full Node on Your Server, Not a Badge on an Empty Ledger.
published: true
tags: blockchain, devops, opensource, web3
canonical_url: https://rpcnode.dev/blog/the-config-said-full-node
---

I am RpcNode Toolkit.

I am not a hosted RPC URL and I am not a light wallet. I am a control plane you install on machines you already own. You give me an Ubuntu 24.04 LTS server. I give you a full-history node you can operate from a panel — Bitcoin, Ethereum, TRON, Solana, and twenty other networks as profiles of the same product, not twenty separate installers.

I do not hold your chain. I do not hold your RPC port. I do not hold your agent token. Your disks, your firewall, your keys. There is no license and no seat. You pay the hardware you already bought.

## What I am

I live in two places, and I am careful not to confuse them.

On an ops host I am the panel. Docker. A UI. I talk to agents. I am not the fullnode. I do not store the ledger there.

On each node server I am one agent. One curl. systemd. That agent is the identity of the host. Bitcoin and TRON and Solana are profiles on that agent, not three products you install three times.

When you add a node I walk the path that used to be a weekend of docs: ports, install options when the chain has them, the catalog client, start, sync. After that the same page is how you live with the node — config, logs, client pin, restart, public RPC.

```bash
curl -fsSL "https://toolkit.rpcnode.dev/install/agent.sh" | sudo bash
```

I print an Agent URL and an `AGENT_API_TOKEN`. You paste those into the panel. There is no remote SSH from my cloud into your box. I am not in your box unless you put me there.

## What I give you

I give you a full node. Not a seven-day window that still answers RPC. Not a pruned Bitcoin that still returns a height. Not `eth_syncing=false` painted as Synced while genesis was never on disk.

Every node I install is full history. Bitcoin ships `prune=0` and `txindex=1`. EVM chains run unpruned / archive as the network expects. Stellar keeps history instead of the stock week. If the chain has an official snapshot, Snapshot is a step on day one, not a note for later.

I give you an honest percent. Snapshot download, IBD, slots behind — the number on the bar is the number in the journal. I will show you 12 percent of a real catch-up. I will not show you Healthy because the process started.

Synced, for me, is two things at once: a live tip, and proof that history is actually there. Height alone is not enough. A green systemd unit is not enough. I do not paint Synced until that proof is done.

I give you the live config, not a ticket to SSH and guess the path. Node Config loads what I wrote — `bitcoin.conf`, java-tron HOCON, xrpld, Geth flags — and lets you edit and apply. Install choices stay on the node: which TRON snapshot, how much XRPL history, how the disks are laid out.

I give you logs you can read without leaving the page. The node journal. The agent. A short host audit for the ops that matter — provision, start, snapshot, errors — not the chatter of Restart=always.

I give you a catalog client and a pin. When a new version lands you re-apply it from the node, not from a wiki of download links.

I give you public JSON-RPC through a local Go proxy on that same host, sized for concurrent load. Clients talk to me. I talk to localhost. The chain never has to face the internet raw.

I give you Telegram when disk, CPU, RPC, or the node itself actually breaks — and when it comes back.

## How you use me

1. Run the panel on an ops host you operate. I talk to agents from there. I am not the chain.
2. Put the agent on each Ubuntu 24.04 LTS server. One host, one agent, as many networks as the machine and the chain allow.
3. In the UI: network → environment → server. Confirm ports. Pick install options if that chain has flavors. Install. Start. Watch the node page.

That is the whole product. Add a host. Add a node. Stay on the node.

## What I will not give you

I will not run the node for you. If you want an HTTPS URL and no disk, that is managed RPC on rpcnode.dev. I am the other door: same catalog of networks, your servers.

I will not support a random OS and pretend I tested it. Ubuntu 24.04 LTS, amd64 or arm64. That is the floor.

I will not call a pruned or windowed client a full node because the RPC answered. If you need a light endpoint, I am the wrong tool.

I will not add a second dashboard to fix a badge I never checked. The proof lives on the node you can see.

## What you walk away with

A fleet you can hold in one panel. A node that still has last year when someone asks. A percent that means the thing it says. A config you can change without SSH folklore. An RPC that is yours.

I am free. I am self-hosted. I am the install path if you want the chain on your metal.

[toolkit.rpcnode.dev](https://toolkit.rpcnode.dev/) · [how to install](https://toolkit.rpcnode.dev/how-to-install/) · [networks](https://toolkit.rpcnode.dev/networks/) · [github.com/rpcnode/toolkit](https://github.com/rpcnode/toolkit)
