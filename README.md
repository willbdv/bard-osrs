# Bard

An account-aware Old School RuneScape assistant for RuneLite. Bard knows your
account mode (Ironman, HCIM, UIM, GIM), build (main, zerker, 1-def pure,
10 HP, skiller), stats, quests and bank, and answers from the OSRS Wiki.

Status: working in the dev client (chat, wiki lookups, account and bank awareness,
goals); being prepared for the Plugin Hub.

## Layout
The RuneLite plugin is this repository: Java 11, no extra dependencies, sources
in `src/main/java/com/bardosrs`.

## Local development

Requirements: JDK 17 (builds to a Java 11 target), no other tools. The Gradle wrapper downloads everything else.

```bash
./gradlew run          # starts a RuneLite dev client with Bard loaded
./gradlew compileJava compileTestJava   # compile only, no client
```

First login (once): in the dev client, open the **Jagex auth** panel in the
sidebar, click **Add Jagex Account**, finish the Jagex login in the window that
opens, pick your character, then click **Play Now**. Later launches only need
you to pick the character. Only you can do this step, because it needs your
Jagex login. Close the normal RuneLite client first: both use `~/.runelite`.

Bard settings live in the RuneLite plugin list under **Bard**: set the endpoint
(default `http://localhost:11434/v1`, a local Ollama server) and the model name.

### Using DeepSeek (hosted)

In the Bard settings: Endpoint `https://api.deepseek.com`, Model `deepseek-flash`
(or `deepseek-v4-pro`), and paste your own API key into **API key**. The key is
stored in RuneLite's local settings only; never commit it.
Streaming and tool calling work with DeepSeek.

## Agent tools

Bard runs a small tool loop: the model can call a tool, Bard runs it locally,
and the result goes back to the model before it answers (up to 8 rounds and 16
calls per question). Tools live in `com.bardosrs.tools`; add one by implementing
`AgentTool` and registering it in `ToolRegistry`.

- `xp_calculator`: exact xp between levels (or from current xp), optionally
  with an xp-per-action rate to count actions. The model is told to use it
  instead of estimating.

### Wiki tools

- `wiki_search`: keyword search over the OSRS Wiki (titles and snippets).
- `wiki_page`: reads a page as plain text (infoboxes and tables become `a | b`
  rows), or one named section of it. Redirects that point at a section open
  that section. Long pages are cut short and list their sections so the model
  can ask for the one it needs.

Wiki lookups are **off by default**: turn on **Look things up on the OSRS Wiki**
in Bard's settings (RuneLite shows a warning first). While it is off, Bard is not
offered these tools, makes no request to the wiki, and answers from the model's own
knowledge, telling you it may be out of date. When on, the tools call the public
MediaWiki API at `oldschool.runescape.wiki` over HTTPS and send only the search
words or page title: no account data. Responses are cached
for 15 minutes. Wiki text is available under CC BY-NC-SA 3.0, and answers are
told to name the page they used. RuneLite sets the User-Agent for plugin HTTP
requests, so it cannot be customised here.

To see exactly what the model receives from the live wiki:

```bash
./gradlew wikiLive -PliveCalls='wiki_page={"title":"Dragon scimitar","section":"Combat stats"}'
```

## Account awareness (opt-in)

Off by default. Turn on **Share account data with the model** in Bard's
settings (RuneLite shows a warning first). Then, each time you ask a question,
Bard reads your own character on the game thread and adds this to the prompt
sent to your model endpoint:

- account type (regular, ironman, ultimate, hardcore, group) with the rules it
  implies, e.g. an ironman is never told to buy from the Grand Exchange
- build (zerker, 1 Defence pure, 10 HP, level 3 skiller, skiller, main),
  guessed from your levels; override it with **Account build**
- combat and total level, quest points, every skill level and xp, and whether
  you are on a members world
- quest counts; the model can call `get_quests` for names and states

Optionally, **Also share bank, inventory and equipment** (off by default, needs
the setting above) adds what you hold, so Bard will not tell you to gather what
you already have. Inventory and equipment are read live. The game only sends
your bank while it is open, so Bard remembers it from the last time you opened
it (shown as e.g. `Bank: 412 items (25m ago)`) and saves that copy in your
RuneLite settings for the character, so it survives a relog; RuneLite's own
profile sync would include it. The prompt carries a summary only, and the model
looks up specific items with a `search_items` tool.

Never sent: your character name, location, chat or friends, and never any other
player's data. Nothing is read or sent while sharing is
off, and the panel shows what was used (e.g. `Ironman | Zerker (auto) | CB 87`).
The read happens once per question, with a 3 second limit; if the game does not
answer in time Bard answers without it.

## Goals

Ask Bard to set a goal ("set a goal: 56 Crafting for the colossal pouch") or use
the **Goals** tab. Goal types:

- **skill**: reach a level; progress is xp to go
- **quest**: finish a quest; the name is checked against the real quest list
- **item**: hold a quantity of an item (needs item sharing to track)
- **custom**: free text you tick off yourself

Goals are saved per character in RuneLite's settings, up to 30, and reached
goals are marked done automatically. The Goals tab reads your progress from the
game for display only; nothing is sent to the model for that. Bard can list
goals (`list_goals`) and add, complete or remove them (`edit_goal`), but is
told to do so only when you ask.

## Privacy: what leaves your machine

Bard has no server of its own. Everything runs inside the plugin, and it talks to
exactly two kinds of place:

- **Your model endpoint** (Ollama on your own machine by default, or a hosted
  provider you choose). It receives your messages and the wiki text Bard fetched.
  With **Share account data** on it also receives the account summary above, and
  with **Also share bank, inventory and equipment** on, your items. Both are off by
  default and RuneLite shows a warning before you can turn them on.
- **The OSRS Wiki** (`oldschool.runescape.wiki`), only if you turn on **Look things
  up on the OSRS Wiki** (off by default, with the same RuneLite warning). It receives
  search words and page titles, never account data.

Your API key is a RuneLite secret setting. Bard reads only your own character:
never other players, and it exposes nothing over HTTP.

## Plugin Hub notes

Compliance checklist (see `AGENTS.md` for the Hub's rules): Java 11 only, no extra
dependencies, the client's own `OkHttpClient` and `Gson`, no reflection, file I/O,
subprocesses, `Thread.sleep` or local server, config group `bardosrs`, and the
third-party-server opt-ins carry the required warning text. This command should
print nothing:

```bash
grep -rnE "java\.lang\.reflect|java\.io\.File|HttpURLConnection|Thread\.sleep|new Gson\(|new OkHttpClient\(|java\.net\.http|ProcessBuilder|Class\.forName" src/main
```
