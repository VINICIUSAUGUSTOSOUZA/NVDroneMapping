# NV Drone Mapping 1.0.0

Aplicativo Android para planejar missões fotogramétricas e gerar arquivos de missão DJI Fly em KMZ/WPML, com foco inicial no DJI Mini 5 Pro.

## O que já está implementado

- mapa interativo OpenStreetMap;
- desenho do perímetro por toques no mapa;
- edição dos vértices arrastando os marcadores;
- desfazer e limpar desenho;
- localização GPS;
- importação de KML e KMZ com polígono;
- cálculo de área em m²/ha;
- cálculo automático da grade de voo;
- direção automática para reduzir o trajeto;
- direção manual em graus;
- altura, velocidade e overlap frontal/lateral configuráveis;
- preset **2D AUTO** (60 m, 80/70, nadir);
- preset **CRUZADO AUTO** para duas direções de voo;
- cálculo de GSD, espaçamento de linhas, espaçamento de fotos, quantidade de fotos, distância e tempo estimado;
- câmera inicial modelada com FOV 84° e imagem 8192 × 6144;
- captura **full-auto**: uma ação `takePhoto` em cada waypoint;
- gimbal configurável (padrão -90°);
- geração de `wpmz/template.kml` + `wpmz/waylines.wpml` dentro do KMZ;
- limite configurável de waypoints e divisão automática em várias missões (padrão 190 por parte);
- código de drone DJI configurável (padrão 68 para o perfil Mini 5 Pro usado nesta versão);
- prévia KML para abrir no Google Earth;
- salvar, abrir e excluir projetos localmente;
- exportar e compartilhar KMZ;
- guia dentro do app para levar a missão ao DJI Fly;
- GitHub Actions para testar e compilar APK automaticamente.

## Importante sobre o DJI Fly / Mini 5 Pro

O app **não pilota o drone diretamente**. Ele planeja a missão e gera o KMZ. O DJI Fly continua sendo o software que executa a missão no drone.

No fluxo de drones DJI consumer, não existe um botão genérico de "Importar KMZ" equivalente ao DJI Pilot 2. Por isso, a V1 gera o arquivo e ensina o fluxo de criar uma missão Waypoint temporária no DJI Fly e substituir o KMZ correspondente no armazenamento. O local exato da pasta pode variar entre celular, versão do Android e controle.

O valor `droneEnumValue = 68` é deixado editável porque a compatibilidade do formato consumer foi inferida/validada pela comunidade e pode mudar com firmware/DJI Fly. Antes do primeiro voo real, **abra a missão no DJI Fly e confira visualmente todos os pontos, altura, RTH, gimbal e ações de câmera**.

## Primeiro teste recomendado

1. Use uma área aberta e pequena.
2. Faça um polígono de aproximadamente 20 × 30 m.
3. Use 30 m de altura apenas para o ensaio inicial, respeitando as regras e condições do local.
4. Use 80% frontal / 70% lateral.
5. Gere a missão.
6. Confira a rota no próprio app.
7. Exporte a **PRÉVIA KML** e confira no Google Earth.
8. Exporte o **KMZ DJI**.
9. Leve o KMZ ao DJI Fly.
10. No DJI Fly, revise a missão inteira antes de autorizar a decolagem.

## Compilar pelo GitHub

1. Crie um repositório vazio no GitHub.
2. Envie **todo o conteúdo desta pasta** para a raiz do repositório.
3. Abra a aba **Actions**.
4. Abra **Build Android APK**.
5. Use **Run workflow**.
6. O workflow roda os testes, compila a versão debug e publica o artefato **NVDroneMapping-debug**.
7. Dentro dele estará `app-debug.apk`.

O workflow também roda automaticamente em push para `main`/`master` e em pull requests.

## Estrutura principal

- `MainActivity.kt` — interface, mapa, projetos, importação/exportação;
- `GridPlanner.kt` — cálculo da malha e divisão de missões;
- `GeoMath.kt` — projeção local, área e distâncias;
- `KmzExporter.kt` — geração do KMZ/WPML DJI;
- `KmlImporter.kt` — leitura de KML/KMZ;
- `ProjectStore.kt` — projetos salvos;
- `CorePlannerTest.kt` — testes automáticos do planejador e KMZ;
- `.github/workflows/android.yml` — build do APK.

## Testes já executados nesta entrega

O núcleo JVM foi compilado e executado localmente com testes de fumaça:

- retângulo ~120 × 80 m: rota e estatísticas geradas;
- polígono estreito ~8 × 100 m: uma faixa central foi gerada corretamente;
- missão densa: divisão automática em partes de no máximo 190 pontos;
- KMZ: presença de `wpmz/template.kml` e `wpmz/waylines.wpml`;
- XML interno: parse válido;
- modo full-auto: uma ação `takePhoto` por waypoint.

A compilação Android completa fica automatizada no GitHub Actions, pois esta entrega foi produzida em um ambiente sem Android SDK local.

## Próximas evoluções recomendadas

- transferência assistida ainda mais automática para a pasta de missão do DJI Fly, quando o Android/controle permitir;
- mapa offline;
- terreno/elevação (terrain follow);
- cálculo de bateria por trecho;
- ponto de decolagem selecionável;
- corredores/linhas;
- missão oblíqua 3D com modelo de footprint específico para câmera inclinada;
- exportação de relatório da missão.

## Referências técnicas usadas no desenho da V1

- DJI WPML / Waypoint Markup Language: documentação oficial DJI Developer.
- Especificações da câmera DJI Mini 5 Pro: DJI.
- Formato consumer DJI Fly/RC2 e testes Mini 5 Pro: projeto open-source FlyPath (dronnix-io/FlyPath).

## Licença

MIT. Use e modifique por sua conta, sempre validando a missão no DJI Fly antes do voo.
