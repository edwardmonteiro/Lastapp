# Caderno Vivo

Caderno de notas Android cuja memória entende significado. Quatro modelos da NVIDIA (API gratuita do build.nvidia.com) trabalham em papéis complementares:

| Papel | Modelo padrão | O que faz |
|---|---|---|
| Rápido | `nvidia/nemotron-3-nano-30b-a3b` | titula e etiqueta cada nota |
| Visão | `nvidia/nemotron-3-nano-omni-30b-a3b-reasoning` | lê e descreve fotos |
| Memória | `nvidia/llama-3.2-nv-embedqa-1b-v2` | embeddings para busca por significado |
| Pensar | `nvidia/nemotron-3-super-120b-a12b` | responde perguntas e conecta ideias |

Voz entra pelo reconhecimento do Android (pt-BR) e a resposta pode ser ouvida pelo TTS do aparelho.

## Usar

1. Instale o APK, abra **03 Ajustes** e cole sua chave `nvapi-` (gratuita em build.nvidia.com).
2. Toque em **Detectar modelos**: o app consulta o catálogo e troca IDs que não existirem mais.
3. **01 Caderno**: escreva, fale ou fotografe. **02 Pensar**: pergunte ao caderno ou peça conexões.

As notas ficam só no aparelho; a chave também. O texto e as imagens que você captura são enviados à API da NVIDIA.

## Build

Push na branch `caderno-vivo` roda o workflow `build-apk`, que publica o APK na branch `apk-build` (`caderno-vivo.apk`) e como artefato da execução.
