# Third-party notices

## mcsrc.dev

This project's `core` module ports Java source from [FabricMC/mcsrc](https://github.com/FabricMC/mcsrc)
([mcsrc.dev](https://mcsrc.dev/)): `ClassData`, `MemberData`, `Entry`, `IndexData`, `Indexer`,
`ClassIndexVisitor`, `ClassFileRemapper`, `LocalRenameVisitor`, `BytecodePrinter`.
The `server` module's `search_classes` tool logic (`SearchClassesTool.kt`) is likewise a
line-for-line port of mcsrc.dev's `src/logic/Search.ts` scoring algorithm.

Those portions remain under the upstream project's MIT License, reproduced here in full as
that license requires:

```
MIT License

Copyright (c) 2025

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Dependencies

Direct and transitive dependencies used by this project:

| Library | License | Used by |
|---|---|---|
| [ASM](https://asm.ow2.io/) | BSD-3-Clause | `core` — bytecode reading/writing |
| [Fabric `mapping-io` / `mapping-io-extras`](https://github.com/FabricMC/mapping-io) | Apache-2.0 | `core` — ProGuard mapping parsing/remapping |
| [Kotlin MCP SDK](https://github.com/modelcontextprotocol/kotlin-sdk) (`kotlin-sdk-server`) | MIT | `server` — MCP protocol/stdio transport |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | Apache-2.0 | `cache`, `server` — JSON parsing |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache-2.0 | `server` |
| [kotlinx-io](https://github.com/Kotlin/kotlinx-io) | Apache-2.0 | `server` — stdio transport |
| [Vineflower](https://github.com/Vineflower/vineflower) | Apache-2.0 | `server` — decompiler behind `get_class_source` |
| [JUnit 5](https://junit.org/junit5/) | EPL-2.0 | test-only, all modules |

## Minecraft content

Mojang's obfuscation mapping files, downloaded at runtime into this server's cache, carry
their own license terms. Those terms accompany the `client_mappings.txt` Mojang serves and
are not reproduced here.

Every tool that fetches or serves Minecraft content is gated behind acceptance of the
[Minecraft EULA](https://www.minecraft.net/en-us/eula). Accept it by setting
`MCSRC_MCP_ACCEPT_EULA=1` in the server's environment, or by writing `accepted` to
`eula-accepted.txt` in the cache root. `get_instructions`, `list_versions`, and `clear_cache`
are deliberately left ungated — they serve none of that content.
