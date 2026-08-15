# Third-party components

This repository contains an integration layer and Compose configuration. It does not redistribute the source of the third-party services below; Docker pulls their images independently.

- `itzg/docker-minecraft-bedrock-server` — upstream license/terms apply.
- `vmorganp/Lazytainer` — MIT License upstream.
- `haproxy` official Docker image — upstream HAProxy and base-image licenses apply.
- `openai/tunnel-client` — Apache License 2.0 upstream.
- Eclipse Temurin / Maven official images — their upstream licenses apply.
- Spring Boot, Spring AI, Gson and transitive Maven dependencies — their respective upstream licenses apply.

Minecraft Bedrock Dedicated Server itself is downloaded/used subject to Microsoft's/Mojang's applicable terms. `EULA=TRUE` is required by the selected BDS container and means the operator accepts the relevant Minecraft EULA.
