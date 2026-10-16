# Quadros

Cadastro de quadro, sessão por perfil e lista no Galaxy Tab A11+. O banco do servidor é um arquivo SQLite. A engenharia envia o PDF pelo painel (`POST /api/projetos`). O nome `CODIGO-REV.X.pdf` vira revisão pendente. Nome fora do padrão vai para validação manual. O envio não publica. Montagem não envia.

A publicação troca a vigente numa transação. O tablet baixa o PDF com retomada e confirma a leitura com `operacao_id`. Sem rede, a observação, a dúvida, a foto, a pendência e a etapa entram na fila local. A tela mostra “na fila” até a API aceitar. A etapa só aparece como concluída depois desse aceite. `informativa` não trava a etapa. `reavaliacao` trava o aparelho que ainda não confirmou. `bloqueio` trava até todos os tablets ativos da bancada, vistos nas últimas 24 horas, confirmarem. Pendência com bloqueio de etapa recusa a conclusão até alguém resolver no painel. A foto sobe em faixas e retoma do byte já gravado.

## API

```bash
cd api
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Ensaios: `cd api && .venv/bin/pytest`.

Logins semeados, senha igual ao perfil: `ana.ribeiro` / `engenharia`, `carlos.mota` / `montagem`, `helena.dias` / `supervisao`, `rita.sol` / `admin`.

O tablet desta fase usa o identificador `tablet-fase-1`, já liberado. Outro aparelho nasce pendente e não entra. O admin vincula a bancada e ativa. Bloquear o aparelho revoga a sessão e tira ele da conta do bloqueio crítico.

O SQLite não tem papel separado de banco. Quem escreve é o processo da API, dono do arquivo em modo `0600`. Auditoria e publicação não aceitam `UPDATE`: o gatilho aborta.

## Backup

Uma vez por dia, com a API no ar, a admin chama `POST /api/backup`. A cópia do SQLite usa a API de backup do próprio SQLite, para não apanhar o arquivo no meio de uma escrita, e o disco interno sai no mesmo ciclo. Cada pasta fica em `api/data/backups/AAAAMMDDTHHMMSSZ`, com o banco em modo `0600`. Pastas com mais de 30 dias saem na geração seguinte.

Para restaurar: pare a API, troque o arquivo SQLite e o diretório de arquivos pela pasta da cópia e suba de novo. Se o PDF da vigente não estiver no disco, o download responde erro e o quadro continua mostrando o mesmo ponteiro.

## Painel

```bash
cd painel
npm install
npm run dev
```

Abre em `http://localhost:5173`. A engenharia cadastra, publica e cancela a vigente enquanto ninguém confirmou a leitura. O admin cria usuário, vincula e bloqueia tablet. Supervisão e engenharia filtram a auditoria. Montagem recebe recusa no painel.

## Tablet

Abra a pasta `android` no Android Studio e rode no Galaxy Tab A11+ físico. Na tela de entrada, o servidor é o endereço da máquina da API na rede da fábrica, por exemplo `http://192.168.0.10:8000`.

O HTTP desta fase é para o ensaio na rede interna. TLS entra quando houver certificado da fábrica.
