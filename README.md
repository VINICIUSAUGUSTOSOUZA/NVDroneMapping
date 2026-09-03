# NV Drone Mapping

Aplicativo Android para planejamento de missões de mapeamento aéreo, com foco inicial no fluxo DJI Fly / DJI Mini 5 Pro.

## Fluxo principal

1. Importe KML, KMZ ou DXF como referência visual, se necessário.
2. Desenhe o quadro de voo no mapa.
3. Defina altura manual ou use o planejamento automático por GSD.
4. Configure velocidade e sobreposições frontal/lateral.
5. Gere as faixas de levantamento.
6. Revise fotos previstas, rota, bateria e divisão em partes.
7. Exporte o KMZ DJI.
8. Reabra a missão no DJI Fly e confira todos os parâmetros antes da execução.

## Modelo de missão DJI

A missão separa três conceitos:

- **Pontos previstos de foto (`photoPoints`)**: usados para visualização, estatísticas e retomada.
- **Faixas de levantamento (`surveyLines`)**: trechos em que a câmera deve fotografar.
- **Rota DJI (`routeWaypoints`)**: geometria enxuta enviada ao DJI, sem transformar cada fotografia em waypoint.

No KMZ, as fotografias são programadas por distância (`multipleDistance`) somente dentro das faixas de levantamento. O início de cada faixa recebe uma fotografia explícita para manter a cobertura. Waypoints intermediários usam passagem contínua quando a geometria permite um `waypointTurnDampingDist` seguro.

A estrutura exportada continua:

```text
KMZ
└── wpmz
    ├── template.kml
    └── waylines.wpml
```

Namespace WPML utilizado:

```text
http://www.uav.com/wpmz/1.0.2
```

## Altura

O aplicativo deixa explícito qual modo está ativo:

- **ALTURA MANUAL**: o valor digitado é usado no planejamento e exportado para o DJI.
- **ALTURA AUTOMÁTICA POR GSD**: o NV Mapping calcula a altura e bloqueia o campo manual para evitar ambiguidade.

Ao abrir um projeto salvo, a altura resolvida do projeto é preservada como valor manual. Projetos antigos sem a nova geometria de faixas são regenerados automaticamente quando possível; o exportador bloqueia planos legados que não possam ser convertidos com segurança.

## Bateria e divisão

A estimativa não adiciona mais tempo artificial de parada por fotografia, porque o disparo ocorre durante o voo. A divisão por bateria:

- considera ida, levantamento contínuo, retorno e margem operacional;
- prefere encerrar cada parte no final de uma faixa;
- respeita o limite configurado de waypoints reais enviados ao DJI;
- permite sobreposição de fotos na retomada entre baterias.

## Compatibilidade e segurança

O exportador preserva altura, velocidade, gimbal e ações configuradas no plano. O modo antigo de “parar em cada ponto e fotografar” não é usado como fallback: se uma missão legada não puder ser atualizada para a geometria contínua, a exportação é bloqueada e o usuário deve regenerar o plano.

Antes de qualquer voo:

- confira a missão no DJI Fly;
- confira altura, velocidade, Home/RTH, gimbal e ações de câmera;
- faça o primeiro teste em uma área aberta, pequena e segura;
- não use a primeira missão de validação em uma operação de produção.

## Testes

O CI executa testes unitários do planejador/exportador e compila um APK debug de validação em pull requests.
