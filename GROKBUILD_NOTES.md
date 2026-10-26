# Notas do grokbuild-v3

A pasta `android/` não foi alterada. O site foi alinhado ao que essa pasta já faz. Sugestões só para o app Android:

1. O plano de tiros ainda chama `finish(true)` sozinho quando o relógio chega em `DONE` (`WorkoutService`, laço do `goLive`). No site, a corrida agora só encerra no botão Finalizar: o plano acaba, o apito toca, o relógio segue e o resumo só abre no Sim. Vale o Android fazer o mesmo, senão o comportamento diverge.
2. Na Pista, `isAppearanceLightStatusBars` fica sempre verdadeiro, mesmo no tema Azul. Se o céu noturno escurecer de verdade, os ícones da barra somem. Melhor ligar isso à luminosidade do topo, não só ao fato de estar na Pista.
3. As folhas grandes (`runsheet_*.webp`, ~400 KB cada) e as pequenas convivem. A tela inicial usa a grande; o resto cabe na pequena. Dá para gerar a grande só na hora, ou nem empacotá-la em aparelhos com pouca memória.
4. Os chips de preset e a folha Presets aplicam a mesma coisa. Os chips são rápidos; a folha é a lista completa. Se a lista crescer, os chips podem sair e ficar só o botão.
5. O mapa ao vivo ocupa o espaço que sobra. Em tela baixa, o relógio grande empurra o mapa para uma faixa fina. Um modo compacto do relógio (ou o mapa com altura mínima maior) deixa o trajeto sempre legível.
6. A mão da frente mostra o dorso da luva no ciclo novo. Se um quadro ainda mostrar a palma, o conserto é na folha, não num espelhamento no código — senão a mão de trás inverte junto.
